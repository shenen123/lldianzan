package com.liubinrui.job;

import cn.hutool.core.collection.CollUtil;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.liubinrui.mapper.BlogMapper;
import com.liubinrui.model.dto.blog.BlogEsDTO;
import com.liubinrui.model.dto.blog.BlogEsDao;
import com.liubinrui.model.entity.Blog;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Component
@Slf4j
//@Component 让 Spring 管理 Bean 生命周期  所以不推荐再自己搭建一个单一的任务的线程池
//但又自己创建线程池，不受 Spring 管理
//导致两套生命周期管理，容易出问题
public class IncSyncblogToEs {

    @Autowired
    private BlogMapper blogMapper;
    @Autowired
    private BlogEsDao blogEsDao;
    @Autowired
    private RedissonClient redissonClient;

    private static final String LAST_SYNC_TIME_KEY = "blog:es:last_sync_time";  // 改用最后同步时间
    private static final String LOCK_KEY = "lock:blog:sync:es";

    // 首次同步：查询最近24小时的数据
    private static final long FIRST_SYNC_HOURS = 24;
    // 增量同步间隔：1分钟
    private static final long INCREMENTAL_SYNC_SECONDS = 60;
    // 批量大小
    private static final int BATCH_SIZE = 500;
    // 批量处理超时时间
    private static final long BATCH_TIMEOUT_SECONDS = 30;

    @Autowired
    @Qualifier("esSyncExecutor")
    private ThreadPoolTaskExecutor esExecutor;

    @PostConstruct
    public void init() {
        log.info("IncSyncblogToEs 初始化完成");
    }

    @Scheduled(fixedRate = 60 * 1000L)
    public void update() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        // 尝试获取锁（最多等待0秒，持有30秒）
        boolean locked = false;
        try {
            locked = lock.tryLock(0, 30, TimeUnit.SECONDS);
            if (!locked) {
                log.info("未获取到分布式锁，本次同步跳过");
                return;
            }

            long startTime = System.currentTimeMillis();
            log.info("开始增量同步数据到ES，时间：{}", new Date(startTime));
            // 获取上次同步时间
            RBucket<Long> lastSyncBucket = redissonClient.getBucket(LAST_SYNC_TIME_KEY);
            Long lastSyncTime = lastSyncBucket.get();
            // 计算查询起始时间
            long queryStartTime;
            if (lastSyncTime == null) {
                // 首次同步：查询最近24小时的数据
                queryStartTime = startTime - FIRST_SYNC_HOURS * 3600 * 1000L;
                log.info("首次同步，查询最近{}小时的数据", FIRST_SYNC_HOURS);
            } else {
                // 增量同步：查询上次同步时间之后的数据（加上1分钟容错）
                queryStartTime = lastSyncTime - INCREMENTAL_SYNC_SECONDS * 1000L;
                log.info("增量同步，上次同步时间：{}，查询起始时间：{}",
                        new Date(lastSyncTime), new Date(queryStartTime));
            }
            // 查询需要同步的数据
            Date queryDate = new Date(queryStartTime);
            List<Blog> blogList = blogMapper.selectAfterDate(queryDate);

            if (blogList == null || blogList.isEmpty()) {
                log.info("暂时没有需要同步到ES的数据");
                // 更新最后同步时间
                lastSyncBucket.set(startTime, 7, TimeUnit.DAYS);
                return;
            }
            log.info("共查询到 {} 条数据需要同步", blogList.size());
            // 批量同步到ES
            boolean syncSuccess = syncToEsInBatches(blogList);
            if (syncSuccess) {
                // 只有同步成功后才更新最后同步时间
                lastSyncBucket.set(startTime, 7, TimeUnit.DAYS);
                long endTime = System.currentTimeMillis();
                log.info("增量同步数据到ES完成，耗时：{} ms，同步数量：{}", endTime - startTime, blogList.size());
            } else {
                log.error("增量同步失败，不更新最后同步时间，下次任务会重试");
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("同步任务被中断", e);
        } catch (Exception e) {
            log.error("增量同步异常", e);
        } finally {
            // 释放锁（确保只有当前线程持有锁时才释放）
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("分布式锁已释放");
            }
        }
    }

    /**
     * 分批同步到ES
     * @param blogList 待同步的数据列表
     * @return 是否同步成功
     */
    private boolean syncToEsInBatches(List<Blog> blogList) {
        int total = blogList.size();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < total; i += BATCH_SIZE) {
            final int start = i;
            final int end = Math.min(i + BATCH_SIZE, total);

            // 创建独立的子列表副本，避免线程安全问题
            List<Blog> batchList = new ArrayList<>(blogList.subList(start, end));

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    List<BlogEsDTO> blogEsDTOList = batchList.stream()
                            .map(BlogEsDTO::objToDto)
                            .collect(Collectors.toList());
                    blogEsDao.saveAll(blogEsDTOList);
                    log.info("批次 [{}-{}] 同步成功，数量：{}", start, end, batchList.size());
                } catch (Exception e) {
                    log.error("批次 [{}-{}] 同步失败", start, end, e);
                    throw new RuntimeException("批次同步失败", e);
                }
            }, esExecutor);
            futures.add(future);
        }

        // 等待所有批次完成，带超时
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(BATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (TimeoutException e) {
            log.error("批量同步超时（{}秒），部分批次可能未完成", BATCH_TIMEOUT_SECONDS, e);
            // 取消未完成的任务
            futures.forEach(f -> f.cancel(true));
            return false;
        } catch (Exception e) {
            log.error("批量同步失败", e);
            return false;
        }
    }
}