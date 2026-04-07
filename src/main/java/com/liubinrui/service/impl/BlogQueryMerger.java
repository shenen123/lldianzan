package com.liubinrui.service.impl;

import com.liubinrui.mapper.BlogMapper;
import com.liubinrui.model.entity.Blog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 博客查询请求合并器
 * 将短时间内的多个请求合并成一次批量查询
 */
@Slf4j
@Component
public class BlogQueryMerger {

    @Autowired
    private BlogMapper blogMapper;

    // 批量请求队列
    private final BlockingQueue<BatchRequest> batchQueue = new LinkedBlockingQueue<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // 每批最大数量
    private static final int MAX_BATCH_SIZE = 100;
    // 最大等待时间(毫秒)
    private static final long MAX_WAIT_MS = 200;

    @PostConstruct
    public void init() {
        scheduler.scheduleAtFixedRate(this::processBatch, MAX_WAIT_MS, MAX_WAIT_MS, TimeUnit.MILLISECONDS);
        log.info("BlogQueryMerger 初始化完成");
    }

    /**
     * 合并查询入口
     */
    public CompletableFuture<Blog> queryBlog(Long blogId) {
        CompletableFuture<Blog> future = new CompletableFuture<>();
        batchQueue.offer(new BatchRequest(blogId, future));
        return future;
    }

    /**
     * 处理批量请求
     */
    private void processBatch() {
        if (batchQueue.isEmpty()) {
            return;
        }

        List<BatchRequest> requests = new ArrayList<>();
        // 最多取出100次请求
        batchQueue.drainTo(requests, MAX_BATCH_SIZE);

        if (requests.isEmpty()) {
            return;
        }

        // 去重
        Set<Long> blogIdSet = requests.stream()
                .map(BatchRequest::getBlogId)
                .collect(Collectors.toSet());

        log.info("========== 请求合并统计 ==========");
        log.info("原始请求数: {}", requests.size());
        log.info("去重后博客数: {}", blogIdSet.size());
        log.info("合并率:{}%", (blogIdSet.size() / (double) requests.size()) * 100);
        log.info("博客ID列表: {}", blogIdSet);
        log.info("==================================");

        // 批量查询
        List<Blog> blogs = blogMapper.selectBatchIds(blogIdSet);
        Map<Long, Blog> blogMap = blogs.stream()
                .collect(Collectors.toMap(Blog::getId, blog -> blog));

        // 返回结果
        for (BatchRequest request : requests) {
            Blog blog = blogMap.get(request.getBlogId());
            request.getFuture().complete(blog); // 唤醒等待的线程
        }
    }

    @PreDestroy
    public void destroy() {
        processBatch();
        scheduler.shutdown();
    }

    private static class BatchRequest {
        private final Long blogId;
        private final CompletableFuture<Blog> future;

        public BatchRequest(Long blogId, CompletableFuture<Blog> future) {
            this.blogId = blogId;
            this.future = future;
        }

        public Long getBlogId() { return blogId; }
        public CompletableFuture<Blog> getFuture() { return future; }
    }
}