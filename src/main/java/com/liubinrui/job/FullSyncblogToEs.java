package com.liubinrui.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liubinrui.mapper.BlogMapper;
import com.liubinrui.model.dto.blog.BlogEsDTO;
import com.liubinrui.model.dto.blog.BlogEsDao;
import com.liubinrui.model.entity.Blog;
import com.liubinrui.service.BlogService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

@Component
@Slf4j
public class FullSyncblogToEs implements CommandLineRunner {

    @Autowired
    private BlogService blogService;
    @Resource
    private BlogEsDao blogEsDao;
    @Autowired
    @Qualifier("esSyncExecutor")
    private ThreadPoolTaskExecutor esExecutor;

    private static final int BATCH_SIZE = 500;
    private static final int CONSUMER_THREADS = 5;
    private static final int CURSOR_STEP = 1000;
    private static final int QUEUE_CAPACITY = 10;

    private static final String FULL_SYNC_TIME_KEY = "blog:es:full_sync_time";

    // 配置参数（可以放到配置文件中）
    @Value("${blog.es.sync.force:false}")
    private boolean forceFullSync;  // 是否强制全量同步
    @Autowired
    private BlogMapper blogMapper;
    @Autowired
    private RedissonClient redissonClient;


    @Override
    public void run(String... args) throws Exception {
        // 都记得补充开始时间和结束时间，便于统计效率
        try {
            if (shouldExecuteFullSync()) {
                executeFullSync();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean shouldExecuteFullSync() {
        if (forceFullSync) {
            // 这个根据数据库要求看开机到底强不强制
            return true;
        }
        // 先判断DB是否有数据
        long dbCount = blogService.count();
        if (dbCount == 0) {
            log.info("数据库数据为空，无需同步");
            return false;
        }
        long esCount = blogEsDao.count();
        if ((double) esCount / dbCount < 0.8) {
            return true;
        }
        //根据时间选择强行同步
        RBucket<Long> rBucket = redissonClient.getBucket(FULL_SYNC_TIME_KEY);
        Long time = rBucket.get();
        if (time == null)
            return true;
        else {
            if (System.currentTimeMillis() - time >= 7 * 86400 * 1000)
                return true;
        }
        return false;
    }

    private void executeFullSync() {
        // 之后判断数量关系
        blogEsDao.deleteAll();
        long dbCount = blogService.count();
        if (dbCount < 1000) {
            // 批量同步
            batchInsertES();
        }
        // 游标同步
        else curSorInsertES();
    }

    // 批量插入采用线程池
    private void batchInsertES() {
        Long startTime = System.currentTimeMillis();
        long dbCount = blogService.count();
        int batchSize = BATCH_SIZE;
        int pages = (int) Math.ceil((double) dbCount / batchSize);

        log.info("开始分页并行同步: 总数={}, 批次数={}, 每批={},开始时间：{}", dbCount, pages, batchSize, startTime);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < pages; i++) {
            int offset = i * batchSize;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    List<Blog> blogList = blogMapper.selectPageList(offset, batchSize);
                    if (!blogList.isEmpty()) {
                        List<BlogEsDTO> blogEsDTOList = blogList.stream()
                                .map(BlogEsDTO::objToDto)
                                .toList();
                        retrySaveToES(blogEsDTOList, 3);
                    }
                } catch (Exception e) {
                    log.error("同步到ES失败, offset={}", offset, e);
                    throw new RuntimeException(e);
                }
            }, esExecutor);
            futures.add(future);
        }
        // 2. 使用 allOf 等待所有任务完成
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.MINUTES);  // 阻塞等待，最多30分钟

            long cost = System.currentTimeMillis() - startTime;
            long endTime = System.currentTimeMillis();
            log.info("ES并行同步完成，耗时: {}ms,结束时间:{}", cost, endTime);

            // 3. 更新同步时间
            RBucket<Long> rBucket = redissonClient.getBucket(FULL_SYNC_TIME_KEY);
            rBucket.set(System.currentTimeMillis());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("ES同步被中断", e);
        } catch (ExecutionException e) {
            log.error("ES同步执行异常", e);
        } catch (TimeoutException e) {
            log.error("ES同步超时(30分钟)，部分任务未完成", e);
            // 取消未完成的任务
            futures.forEach(f -> f.cancel(true));
        }
    }

    private void retrySaveToES(List<BlogEsDTO> data, int maxRetries) {
        for (int i = 0; i < maxRetries; i++) {
            try {
                blogEsDao.saveAll(data);
                return;  // 成功，退出
            } catch (Exception e) {
                if (i == maxRetries - 1) {
                    log.error("保存ES失败，已重试{}次", maxRetries, e);
                    throw e;
                }
                log.warn("保存ES失败，第{}次重试", i + 1, e);
                try {
                    Thread.sleep(1000 * (i + 1));  // 指数退避
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
            }
        }
    }

    // 游标采用生产者-消费者
    private void curSorInsertES() {
        // 加上处理时间
        long startTime = System.currentTimeMillis();
        log.info("开始游标方式并行同步数据到ES,开始时间：{}", startTime);
        BlockingQueue<List<BlogEsDTO>> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        try {
            startProducer(queue);
            List<CompletableFuture<Void>> consumers = startConsumer(queue);
            // 将 List<CompletableFuture<Void>> 转换为 CompletableFuture[] 数组,就相当于 redis、db就是热门服务初始化那里
            CompletableFuture.allOf(consumers.toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.MINUTES);
            log.info("同步完成");
        } catch (Exception e) {

            log.error("同步过程中发生异常", e);
        }
        RBucket<Long> rBucket = redissonClient.getBucket(FULL_SYNC_TIME_KEY);
        rBucket.set(System.currentTimeMillis());
        long endTime = System.currentTimeMillis();
        log.info("游标方式同步ES完成，耗时={}ms,结束时间：{}", endTime - startTime, endTime);

    }

    private void startProducer(BlockingQueue<List<BlogEsDTO>> queue) {
        // 先获取数量,每批获取1000条
        try {
            long lastId = 0L;
            int batchSize = 0;
            while (true) {
                LambdaQueryWrapper<Blog> blogLambdaQueryWrapper = new LambdaQueryWrapper<>();
                blogLambdaQueryWrapper.gt(Blog::getId, lastId);
                blogLambdaQueryWrapper.orderByAsc(Blog::getId);
                blogLambdaQueryWrapper.last("LIMIT " + CURSOR_STEP);
                List<Blog> batchBlogList = blogMapper.selectList(blogLambdaQueryWrapper);
                if (batchBlogList == null || batchBlogList.isEmpty())
                    break;
                lastId = batchBlogList.get(batchBlogList.size() - 1).getId();
                List<BlogEsDTO> blogEsDTOList = batchBlogList.stream().map(BlogEsDTO::objToDto).toList();
                try {
                    batchSize++;
                    queue.put(blogEsDTOList);
                    log.info("第{}批数据插入到队列成功,一共{}条", batchSize, batchBlogList.size());
                } catch (Exception e) {
                    log.error("第{}批数据插入到队列失败", batchSize);
                }
            }
            // 发送结束信号
            queue.put(new ArrayList<>());
        } catch (InterruptedException e) {
            log.error("生产者被中断", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("生产者异常", e);
        }
    }

    private List<CompletableFuture<Void>> startConsumer(BlockingQueue<List<BlogEsDTO>> queue) {
        List<CompletableFuture<Void>> consumers = new ArrayList<>();
        // 这里是不知道生产者有几个批次，只知道每个批次1000条
        for (int i = 0; i < CONSUMER_THREADS; i++) {
            int consumerId = i;
            CompletableFuture<Void> consumer = CompletableFuture.runAsync(() -> {
                int processedCount = 0;
                while (true) {
                    log.info("消费者{}启动", consumerId);
                    try {
                        List<BlogEsDTO> blogEsDTOList = queue.poll(1, TimeUnit.SECONDS);
                        if (blogEsDTOList == null) {
                            log.info("消费者-{} 等待超时，退出", consumerId);
                            break;
                        }
                        if (blogEsDTOList.isEmpty()) {
                            log.info("消费者-{} 收到结束信号", consumerId);
                            break;
                        }
                        try {
                            blogEsDao.saveAll(blogEsDTOList);
                            processedCount += blogEsDTOList.size();
                            log.info("消费者{},已写入{}条数据", consumerId, processedCount);
                        } catch (Exception e) {
                            log.error("消费者-{} 写入ES失败", consumerId, e);
                        }
                    } catch (Exception e) {
                        log.error("消费者-{} 被中断", consumerId);
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                log.info("消费者-{} 完成，共处理 {} 条", consumerId, processedCount);
            }, esExecutor);
            consumers.add(consumer);
        }
        return consumers;
    }

    // 批量插入采用线程池
//    private void batchInsertES() {
//        Long startTime = System.currentTimeMillis();
//        long dbCount = blogService.count();
//        int batchSize = 4000;
//        // 使用 CountDownLatch 等待所有任务完成
//        int pages = Math.toIntExact((dbCount + batchSize - 1) / batchSize);
//        CountDownLatch latch = new CountDownLatch(pages);
//        List<CompletableFuture<Void>> futures = new ArrayList<>();
//        for (int i = 0; i < pages; i++) {
//            int offset = i * batchSize;
//            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
//                try {
//                    List<Blog> blogList = blogMapper.selectPageList(offset, batchSize);
//                    List<BlogEsDTO> blogEsDTOList = blogList.stream().map(BlogEsDTO::objToDto).toList();
//                    retrySaveToES(blogEsDTOList, 3);
//
//                } catch (Exception e) {
//                    log.info("同步到ES失败，失败原因：{}", e);
//                    throw new RuntimeException(e);
//                } finally {
//                    latch.countDown();
//                }
//            }, esExecutor);
//            futures.add(future);
//        }
//
//        try {
//            // 等待所有任务完成，最多等待30分钟
//            boolean completed = latch.await(30, TimeUnit.MINUTES);
//            if (!completed) {
//                log.warn("ES同步超时，部分任务未完成");
//                // 取消未完成的任务
//                futures.forEach(f -> f.cancel(true));
//            }
//            log.info("ES并行同步完成,用时：{}", System.currentTimeMillis() - startTime);
//            RBucket<Long> rBucket = redissonClient.getBucket(FULL_SYNC_TIME_KEY);
//            rBucket.set(System.currentTimeMillis());
//        } catch (InterruptedException e) {
//            Thread.currentThread().interrupt();
//            log.error("ES同步被中断", e);
//        }
//    }
}

