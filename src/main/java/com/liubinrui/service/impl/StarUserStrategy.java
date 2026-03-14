package com.liubinrui.service.impl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class StarUserStrategy {

    @Autowired
    private  RedisTemplate<String, Long> redisTemplate;

    // Redis Key 定义
    private static final String STAR_USER_SET_KEY = "star:user:ids";

    // 阈值配置 (也可以放在 Nacos/Apollo 配置中心动态调整)
    private static final long STAR_THRESHOLD = 5L;

    /**
     * 判断用户是否为明星用户
     * 时间复杂度: O(1)
     */
    public boolean isStarUser(Long userId) {
        if (userId == null) {
            return false;
        }
        Boolean isMember = redisTemplate.opsForSet().isMember(STAR_USER_SET_KEY, userId);
        return Boolean.TRUE.equals(isMember);
    }

    /**
     * 获取明星用户专属表名
     */
    public String getStarTableName(Long userId) {
        return "user_follow_star_" + userId;
    }

    /**
     * 将用户标记为明星用户 (添加到 Redis Set)
     * 调用时机：数据迁移完成后，或后台手动升级时
     */
    public void addStarUser(Long userId) {
        if (userId == null) return;

        redisTemplate.opsForSet().add(STAR_USER_SET_KEY, userId);
        log.info("✅ 用户 {} 已加入明星用户集合 (Redis)", userId);
    }

    /**
     * 移除明星用户标记 (降级时使用)
     */
    public void removeStarUser(Long userId) {
        if (userId == null) return;

        redisTemplate.opsForSet().remove(STAR_USER_SET_KEY, userId);
        log.info("📉 用户 {} 已从明星用户集合移除", userId);
    }

    /**
     * 【辅助方法】检查粉丝数并自动升级
     * 注意：高并发下建议配合分布式锁，防止重复迁移
     */
    public boolean checkAndPromote(Long userId, long currentFansCount) {
        // 如果已经是明星，直接返回
        if (isStarUser(userId)) {
            return false;
        }

        // 检查是否达到阈值
        if (currentFansCount >= STAR_THRESHOLD) {
            addStarUser(userId);
            return true; // 返回 true 表示触发了升级，需要执行迁移逻辑
        }
        return false;
    }

    /**
     * 获取当前明星用户总数 (用于监控)
     */
    public Long getStarUserCount() {
        return redisTemplate.opsForSet().size(STAR_USER_SET_KEY);
    }
}