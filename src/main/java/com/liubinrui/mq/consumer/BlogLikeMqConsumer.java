package com.liubinrui.mq.consumer;

import com.liubinrui.common.ErrorCode;
import com.liubinrui.config.RabbitMqConfig;
import com.liubinrui.exception.ThrowUtils;
import com.liubinrui.mapper.BlogMapper;
import com.liubinrui.mapper.ThumbMapper;
import com.liubinrui.model.dto.mq.BlogLikeMqDTO;
import com.liubinrui.model.entity.Thumb;
import com.liubinrui.service.BlogService;
import com.liubinrui.service.ThumbService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Component
@Slf4j
public class BlogLikeMqConsumer {

    @Autowired
    private ThumbMapper thumbMapper;
    @Autowired
    private ThumbService thumbService;
    @Resource
    @Qualifier("dbExecutor")
    private Executor dbExecutor;
    @Autowired
    private BlogMapper blogMapper;

    // 使用线程安全的集合
    private final Queue<BlogLikeMqDTO> likeQueue = new ConcurrentLinkedQueue<>();
    private final Queue<BlogLikeMqDTO> unlikeQueue = new ConcurrentLinkedQueue<>();

    // 批量处理阈值
    private static final int BATCH_SIZE = 1000;
    // 批量处理时间间隔（毫秒）
    private static final long BATCH_INTERVAL_MS = 3000;

    private final ScheduledExecutorService batchScheduler = Executors.newSingleThreadScheduledExecutor();

    // 所以即使你设置了 BATCH_SIZE = 1000，只要队列里的数据不到 1000 条，定时器到时间就会触发，把你现有的那点数据全部取走
    @PostConstruct
    public void init() {
        // 定时批量处理（每3秒执行一次）,让数据收集的更多
        batchScheduler.scheduleAtFixedRate(this::flushBatches, 0, BATCH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("BlogLikeMqConsumer初始化完成");
    }

    /**
     * 批量刷新：将队列中的数据批量写入数据库
     */
    private void flushBatches() {
        // 分别处理点赞和取消点赞
        flushBatchByType(1);  // 点赞
        flushBatchByType(0);  // 取消点赞
    }

    /**
     * 按类型处理单个队列
     */
    private void flushBatchByType(Integer type) {
        Queue<BlogLikeMqDTO> sourceQueue = (type == 1) ? likeQueue : unlikeQueue;

        if (sourceQueue.isEmpty()) {
            return;
        }

        // 一次性取出所有待处理的数据
        List<BlogLikeMqDTO> batchList = new ArrayList<>();
        BlogLikeMqDTO dto;
        while ((dto = sourceQueue.poll()) != null) {
            batchList.add(dto);
        }

        log.info("处理{}消息队列，消息容量：{}", type == 1 ? "点赞" : "取消点赞", batchList.size());

        // 修复：使用 blogId + dianZanId 联合去重（防止同一用户对同一博客的重复消息）
        Map<String, BlogLikeMqDTO> latestMap = new HashMap<>();
        for (BlogLikeMqDTO item : batchList) {
            String uniqueKey = item.getBlogId() + "_" + item.getDianZanId();
            latestMap.put(uniqueKey, item);
        }

        // 构建 Thumb 集合和博客ID集合
        Set<Thumb> thumbSet = new HashSet<>();
        Set<Long> blogIdSet = new HashSet<>();

        for (BlogLikeMqDTO dtoItem : latestMap.values()) {
            if (dtoItem.getBlogId() != null && dtoItem.getBlogId() > 0 &&
                    dtoItem.getDianZanId() != null && dtoItem.getDianZanId() > 0) {
                thumbSet.add(dtoItem.getToObj());
                blogIdSet.add(dtoItem.getBlogId());
            }
        }

        if (thumbSet.isEmpty()) {
            return;
        }

        // 执行数据库操作
        try {
            if (type == 1) {
                if (!thumbSet.isEmpty()) {
                    thumbService.saveBatch(thumbSet);
                }
                if (!blogIdSet.isEmpty()) {
                    blogMapper.batchUpdateThumbCount(blogIdSet, 1);
                }
            } else {
                if (!thumbSet.isEmpty()) {
                    thumbMapper.batchDelete(thumbSet);
                }
                if (!blogIdSet.isEmpty()) {
                    blogMapper.batchUpdateThumbCount(blogIdSet, -1);
                }
            }
            log.info("批量处理成功，type={}, 处理数量={}", type, thumbSet.size());
        } catch (Exception e) {
            log.error("批量处理数据库失败，type={}", type, e);
        }
    }

    @PreDestroy
    public void destroy() {
        log.info("应用关闭，开始刷新剩余数据...");
        // 最后一次刷新
        flushBatches();

        // 关闭线程池
        batchScheduler.shutdown();
        try {
            if (!batchScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                batchScheduler.shutdownNow();
            }

        } catch (InterruptedException e) {
            batchScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("BlogLikeMqConsumer已关闭");
    }

    /**
     * 消费 MQ 消息
     */
    @RabbitListener(queues = RabbitMqConfig.BLOG_LIKE_QUEUE)
    public void consume(BlogLikeMqDTO dto) {
        log.info("消费消息，当前线程: {}", Thread.currentThread().getName());
        if (dto == null || dto.getBlogId() == null || dto.getDianZanId() == null) {
            log.warn("收到无效消息: {}", dto);
            return;
        }

        // 根据类型放入对应的队列
        if (dto.getType() == 1) {
            likeQueue.offer(dto);
        } else {
            unlikeQueue.offer(dto);
        }

        // 检查是否需要立即刷新（达到批量阈值）
        if (likeQueue.size() >= BATCH_SIZE) {
            flushBatchByType(1);
        }
        if (unlikeQueue.size() >= BATCH_SIZE) {
            flushBatchByType(0);
        }
    }
}