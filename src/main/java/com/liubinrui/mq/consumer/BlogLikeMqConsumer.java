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
    private BlogService blogService;

    @Resource
    private HotBlogService hotBlogService;

    @Resource
    private RedissonClient redissonClient;

    private static final AtomicInteger COUNT = new AtomicInteger(0);
    private static final AtomicInteger FAIL_COUNT = new AtomicInteger(0);
    private static final int MAX_FAIL_TIMES = 4; // 模拟前 2 次失败
    @RabbitListener(queues = RabbitMqConfig.BLOG_LIKE_QUEUE)
    @Transactional(rollbackFor = Exception.class)

    public void consumeLikeAction(BlogLikeMqDTO dto) {
        try {
            int current = COUNT.incrementAndGet();
            log.info("✅ [点赞消费第 {} 条] 用户:{}, 博客:{}, 类型:{}", current, dto.getUserId(), dto.getBlogId(), dto.getType());
            // 调用提取出来的纯业务逻辑方法
            processLikeBusiness(dto);
        } catch (Exception e) {
            log.error("💥 处理点赞消息失败! DTO: {}", dto, e);
            // 抛出异常触发重试
            throw e;
        }
    }

    public void processLikeBusiness(BlogLikeMqDTO dto) {
        //测试死信队列功能
        if (FAIL_COUNT.incrementAndGet() <= MAX_FAIL_TIMES) {
            log.warn("🧪 [测试模拟] 第 {} 次调用，模拟临时故障，抛出异常！", FAIL_COUNT.get());
            throw new RuntimeException("模拟数据库连接超时...");
        }
        Long userId = dto.getUserId();
        Long blogId = dto.getBlogId();
        Integer type = dto.getType();

        if (type == 1) {
            // === 点赞逻辑 ===
            Thumb thumb = new Thumb();
            thumb.setUserId(userId);
            thumb.setBlogId(blogId);

            try {
                thumbMapper.insert(thumb);
            } catch (Exception e) {
                // 幂等性检查：如果是因为唯一索引冲突导致的重复插入
                if (e.getMessage() != null && e.getMessage().contains("Duplicate entry")) {
                    log.warn("⚠️ [幂等] 重复点赞记录，忽略。User:{}, Blog:{}", userId, blogId);
                    // 如果是重复消息，后续逻辑（计数+1）通常不需要执行，直接返回
                    // 但如果你的业务要求即使重复消息也要更新热度，可以把 return 去掉
                    return;
                }
                // 其他数据库异常，继续抛出
                throw e;
            }

            // 2. DB: 博客点赞数 +1
            blogService.update()
                    .setSql("thumb_count = thumb_count + 1")
                    .eq("id", blogId)
                    .update();

            // 3. 业务: 更新热度
            hotBlogService.recordUserAction(userId, blogId, BlogActionEnum.LIKE);

            // 4. Redis: 全局计数 +1
            RAtomicLong atomicCount = redissonClient.getAtomicLong("blog:like:count:" + blogId);
            atomicCount.incrementAndGet();

            log.info("✅ [业务逻辑] 点赞完成：BlogId={}", blogId);

        } else if (type == 0) {
            // === 取消点赞逻辑 ===
            thumbMapper.deleteByUserIdAnalogId(userId, blogId);

            blogService.update()
                    .setSql("thumb_count = thumb_count - 1")
                    .eq("id", blogId)
                    .update();

            hotBlogService.resetLikeAction(blogId, 5);

            RAtomicLong atomicCount = redissonClient.getAtomicLong("blog:like:count:" + blogId);
            long count = atomicCount.decrementAndGet();
            if (count < 0) atomicCount.set(0);

            log.info("✅ [业务逻辑] 取消点赞完成：BlogId={}", blogId);
        }
    }
}
