package com.liubinrui.mq.consumer;

import com.liubinrui.config.RabbitMqConfig;
import com.liubinrui.enums.BlogActionEnum;
import com.liubinrui.mapper.ThumbMapper;
import com.liubinrui.model.dto.mq.BlogLikeMqDTO;
import com.liubinrui.model.entity.Thumb;
import com.liubinrui.service.BlogService;
import com.liubinrui.service.impl.HotBlogService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
public class BlogLikeMqConsumer {

    @Resource
    private ThumbMapper thumbMapper;

    @Resource
    private BlogService blogService; // 或者直接用 BlogMapper

    @Resource
    private HotBlogService hotBlogService;

    @Resource
    private RedissonClient redissonClient;

    private static final AtomicInteger COUNT = new AtomicInteger(0);

    @RabbitListener(queues = RabbitMqConfig.BLOG_LIKE_QUEUE)
    @Transactional(rollbackFor = Exception.class)
    public void consumeLikeAction(BlogLikeMqDTO dto) {
        int current = COUNT.incrementAndGet();
        log.info("✅ [点赞消费第 {} 条] 用户:{}, 博客:{}, 类型:{}", current, dto.getUserId(), dto.getBlogId(), dto.getType());

        Long userId = dto.getUserId();
        Long blogId = dto.getBlogId();
        Integer type = dto.getType();
        try {
            if (type == 1) {
                // 1. DB: 插入点赞记录
                // 注意：这里可能会因为网络重试导致重复插入，建议数据库加唯一索引 (user_id, blog_id)
                Thumb thumb = new Thumb();
                thumb.setUserId(userId);
                thumb.setBlogId(blogId);
                try {
                    thumbMapper.insert(thumb);
                } catch (Exception e) {
                    // 如果是因为唯一索引冲突，说明已经点过了，忽略异常或记录日志
                    if (e.getMessage().contains("Duplicate entry")) {
                        log.warn("重复点赞记录，忽略。User:{}, Blog:{}", userId, blogId);
                        // 即使重复，后续的计数逻辑是否需要执行？通常不需要，直接 return
                        return;
                    }
                    throw e;
                }

                // 2. DB: 博客点赞数 +1
                blogService.update()
                        .setSql("thumb_count = thumb_count + 1")
                        .eq("id", blogId)
                        .update();

                // 3. 业务: 更新热度 (原来的逻辑)
                hotBlogService.recordUserAction(userId, blogId, BlogActionEnum.LIKE);

                // 4. Redis: 全局计数 +1 (保证与 DB 一致)
                RAtomicLong atomicCount = redissonClient.getAtomicLong("blog:like:count:" + blogId);
                atomicCount.incrementAndGet();

                log.info("✍️ 点赞完成：BlogId={}", blogId);

            } else if (type == 0) {
                // === 取消点赞逻辑 ===

                // 1. DB: 删除点赞记录
                thumbMapper.deleteByUserIdAnalogId(userId, blogId);

                // 2. DB: 博客点赞数 -1
                blogService.update()
                        .setSql("thumb_count = thumb_count - 1")
                        .eq("id", blogId)
                        .update();

                // 3. 业务: 重置热度
                hotBlogService.resetLikeAction(blogId, 5);

                // 4. Redis: 全局计数 -1
                RAtomicLong atomicCount = redissonClient.getAtomicLong("blog:like:count:" + blogId);
                long count = atomicCount.decrementAndGet();
                if (count < 0) atomicCount.set(0); // 防止负数

                log.info("✍️ 取消点赞完成：BlogId={}", blogId);
            }

        } catch (Exception e) {
            log.error("💥 处理点赞消息失败! DTO: {}", dto, e);
            // 抛出异常触发重试
            throw e;
        }
    }
}
