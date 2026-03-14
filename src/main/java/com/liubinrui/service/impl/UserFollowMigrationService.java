package com.liubinrui.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserFollowMigrationService {

    private final JdbcTemplate jdbcTemplate;
    private final StarUserStrategy starUserStrategy;

    /**
     * 将指定用户的数据从总表迁移到明星独立表
     */
    @Transactional(rollbackFor = Exception.class)
    public void migrateToStarTable(Long userId) {
        String sourceTable = "user_follow";
        String targetTable = starUserStrategy.getStarTableName(userId);

        log.info("🚀 [迁移开始] 用户 {} -> 表 {}", userId, targetTable);

        // 1. 双重检查：防止并发迁移 (虽然事务隔离，但加一层 Redis 检查更稳妥)
        if (starUserStrategy.isStarUser(userId)) {
            log.warn("⚠️ 用户 {} 已是明星用户，跳过迁移", userId);
            return;
        }

        // 2. 创建明星表 (如果不存在)
        // 注意：DDL 在某些事务配置下可能隐式提交，需确保数据库驱动支持
        String createSql = String.format("CREATE TABLE IF NOT EXISTS %s LIKE %s", targetTable, sourceTable);
        jdbcTemplate.execute(createSql);
        log.info("✅ 表结构确认: {}", targetTable);

        // 3. 迁移数据 (INSERT INTO ... SELECT ...)
        // 假设主键 id 是全局唯一的雪花算法 ID，可以直接复制
        String insertSql = String.format(
                "INSERT INTO %s (id, user_id, follower_id, create_time, is_cancel, last_active_time) " +
                        "SELECT id, user_id, follower_id, create_time, is_cancel, last_active_time FROM %s WHERE user_id = ?",
                targetTable, sourceTable
        );
        int rowsAffected = jdbcTemplate.update(insertSql, userId);
        log.info("📦 数据迁移完成: {} 条记录", rowsAffected);

        if (rowsAffected == 0) {
            log.warn("⚠️ 用户 {} 没有关注数据，仅创建空表并标记为明星", userId);
        }

        // 4. 删除源数据
        String deleteSql = "DELETE FROM " + sourceTable + " WHERE user_id = ?";
        int deletedRows = jdbcTemplate.update(deleteSql, userId);
        log.info("🗑️ 清理总表数据: {} 条", deletedRows);

        // 5. 【关键】更新 Redis 标记
        // 只有数据迁移成功后，才将用户加入 Redis 明星集合
        // 这样分片算法下次请求时就会路由到新表
        starUserStrategy.addStarUser(userId);

        log.info("🎉 [迁移结束] 用户 {} 正式成为明星用户，流量已切换至独立表", userId);
    }
}
