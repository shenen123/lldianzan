package com.liubinrui.job;
import com.liubinrui.redis.RedisService;
import com.liubinrui.service.UserFollowService;
import com.liubinrui.service.impl.StarUserStrategy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@Slf4j
public class StarUserUpgradeJob {

    @Resource
    private RedisService redisService;
    @Resource
    private UserFollowService userFollowService;
    @Resource
    private StarUserStrategy starUserStrategy;

    private static final long STAR_THRESHOLD = 100000L;

    @Scheduled(fixedRate = 60000)
    public void scanAndPromote() {
        log.info("🔍 开始扫描明星用户候选...");
        // 【核心优势】一行代码直接查出所有粉丝数 >= 10万 的用户 ID
        Set<Long> candidates = redisService.getStarUserCandidates(STAR_THRESHOLD, 100);
        if (candidates.isEmpty()) {
            return;
        }
        for (Long userId : candidates) {
            try {
                // 双重检查：是否已经是明星 (检查你的明星用户标记逻辑，如 Redis Set 或 DB 字段)
                // if (starUserStrategy.isStarUser(userId)) continue;
                log.info("✨ 发现潜在明星用户：{}, 粉丝数 >= {}", userId, STAR_THRESHOLD);
                // 执行迁移逻辑
                userFollowService.migrateToStarTable(userId);
                starUserStrategy.addStarUser(userId);
            } catch (Exception e) {
                log.error("处理用户 {} 升级失败", userId, e);
            }
        }
    }
}
