package com.liubinrui.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.liubinrui.common.ErrorCode;
import com.liubinrui.exception.BusinessException;
import com.liubinrui.mapper.UserFollowMapper;
import com.liubinrui.model.entity.UserFollow;
import com.liubinrui.redis.RedisService;
import com.liubinrui.service.UserFollowService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class UserFollowServiceImpl implements UserFollowService {

    @Resource
    private UserFollowMapper userFollowMapper;

    @Resource
    private RedisService redisService;

    // 必须注入 JdbcTemplate 以执行动态 SQL
    @Resource
    private JdbcTemplate jdbcTemplate;

    // 明星用户阈值 (测试用设为5，生产环境建议设大一点，如 10000)
    private static final long STAR_THRESHOLD = 5L;

    // 标记用户已迁移的 Redis Key (Set)
    private static final String STAR_USER_SET_KEY = "star:user:ids";

    /**
     * 关注用户：先查是否已关注 → 写入MySQL → 更新Redis → 检查是否触发分表
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean followUser(Long userId, Long followerId) {
        // 1. 判断该博主是否已经是明星用户 (已分表)
        if (isStarUserMigrated(userId)) {
            return followUserInShardTable(userId, followerId); // 走分表逻辑
        } else {
            return followUserInMainTable(userId, followerId); // 走原主表逻辑
        }
    }

    /**
     * 分表中的关注逻辑
     */
    private boolean followUserInShardTable(Long userId, Long followerId) {
        String tableName = getStarTableName(userId);

        // 检查分表中是否存在
        String checkSql = "SELECT count(*) FROM " + tableName + " WHERE user_id = ? AND follower_id = ? AND is_cancel = 0";
        Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class, userId, followerId);

        if (count != null && count > 0) {
            return false; // 已关注
        }

        // 插入分表
        String insertSql = "INSERT INTO " + tableName + " (user_id, follower_id, create_time, last_active_time, is_cancel) VALUES (?, ?, ?, ?, 0)";
        jdbcTemplate.update(insertSql, userId, followerId, LocalDateTime.now(), LocalDateTime.now());

        // 更新 Redis
        redisService.addFollower(userId, followerId);
        redisService.incrFollowerCount(userId);

        return true;
    }

    /**
     * 主表中的关注逻辑
     */
    private boolean followUserInMainTable(Long userId, Long followerId) {
        // 1. 空值校验
        if (userId == null || followerId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "被关注者ID/粉丝ID不能为空");
        }

        // 2. 先查 Redis 缓存
        boolean isFollowedInRedis = redisService.checkIsFollowed(userId, followerId);
        if (isFollowedInRedis) {
            return false;
        }

        // 3. 查 MySQL 兜底
        LambdaQueryWrapper<UserFollow> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(UserFollow::getUserId, userId)
                .eq(UserFollow::getFollowerId, followerId)
                .eq(UserFollow::getIsCancel, 0);

        UserFollow existFollow = userFollowMapper.selectOne(queryWrapper);
        if (existFollow != null) {
            // 缓存不一致，回写缓存
            redisService.addFollower(userId, followerId);
            redisService.incrFollowerCount(userId);
            return false;
        }

        // 4. 写入 MySQL 主表
        UserFollow userFollow = new UserFollow();
        userFollow.setUserId(userId);
        userFollow.setFollowerId(followerId);
        userFollow.setCreateTime(LocalDateTime.now());
        userFollow.setIsCancel(0);
        userFollow.setLastActiveTime(LocalDateTime.now());
        userFollowMapper.insert(userFollow);

        // 5. 更新 Redis 缓存
        redisService.addFollower(userId, followerId);
        redisService.incrFollowerCount(userId);

        // 6. 获取最新粉丝数并检查阈值
        // 注意：确保你的 RedisService 中有 getFollowerCount 方法且返回 Long
        Long currentFollowerCount = redisService.getFollowerCount(userId);

        // 如果 RedisService 没有直接返回计数的方法，可以用下面这行替代 (需确保 incrFollowerCount 返回了新值，或者单独查询)
        // Long currentFollowerCount = redisService.opsForValue().increment("user:follower:count:" + userId, 0); // 伪代码，视具体实现而定

        if (currentFollowerCount != null && currentFollowerCount >= STAR_THRESHOLD) {
            try {
                // 🚀 触发迁移
                // 注意：这里是在事务内调用另一个事务方法。
                // 由于是在同一个类中调用，@Transactional 在 migrateToStarTable 上可能不会按预期开启新事务（除非用 AopContext 或异步）。
                // 但为了简单起见，这里直接调用。如果迁移失败，会被下面的 catch 捕获，不会回滚外部的“关注”操作。
                migrateToStarTable(userId);
                log.info("🎉 用户 {} 粉丝数达到 {}，已成功触发分表迁移", userId, currentFollowerCount);
            } catch (Exception e) {
                // ⚠️ 关键：迁移失败不能影响用户当前的关注操作
                log.error("⚠️ 用户 {} 迁移分表失败，将稍后重试或人工介入。错误信息：{}", userId, e.getMessage(), e);
                // 这里可以选择发送一个 MQ 消息通知后台任务重试，或者仅仅记录日志
            }
        }

        return true;
    }

    /**
     * 取消关注逻辑
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelFollow(Long userId, Long followerId) {
        // 1. 空值校验
        if (userId == null || followerId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "被关注者ID/粉丝ID不能为空");
        }

        // 2. 先查是否已关注 (checkIsFollowed 内部已经处理了分表/主表的路由查询)
        boolean isFollowed = checkIsFollowed(userId, followerId);
        if (!isFollowed) {
            return false;
        }

        int updateCount = 0;

        // 3. 【核心修改】根据是否迁移，选择更新策略
        if (isStarUserMigrated(userId)) {
            // --- 情况 A: 用户已迁移到分表 ---
            String tableName = getStarTableName(userId);
            String updateSql = "UPDATE " + tableName +
                    " SET is_cancel = 1, last_active_time = ? " +
                    " WHERE user_id = ? AND follower_id = ? AND is_cancel = 0";

            updateCount = jdbcTemplate.update(updateSql, LocalDateTime.now(), userId, followerId);

            if (updateCount == 0) {
                // 理论上 checkIsFollowed 已通过，这里为 0 可能是并发取消或数据不一致
                log.warn("分表更新失败：用户 {} 在表 {} 中未找到有效的关注记录", userId, tableName);
                // 可以选择抛出异常或返回 false，这里为了稳健性返回 false
                return false;
            }

        }
            // --- 情况 B: 用户仍在主表 ---
            LambdaUpdateWrapper<UserFollow> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(UserFollow::getUserId, userId)
                    .eq(UserFollow::getFollowerId, followerId)
                    .eq(UserFollow::getIsCancel, 0);

            updateCount = userFollowMapper.update(
                    null,
                    updateWrapper.set(UserFollow::getIsCancel, 1)
                            .set(UserFollow::getLastActiveTime, LocalDateTime.now())
            );

            if (updateCount == 0) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "取消关注失败：未找到对应的关注记录");
            }

        // 4. 更新 Redis (无论哪种表，Redis 逻辑一致)
        redisService.removeFollower(userId, followerId);
        redisService.decrFollowerCount(userId);

        return true;
    }

    /**
     * 检查是否已关注
     */
    public boolean checkIsFollowed(Long userId, Long followerId) {
        if (userId == null || followerId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "被关注者ID/粉丝ID不能为空");
        }

        boolean isFollowedInRedis = redisService.checkIsFollowed(userId, followerId);
        if (isFollowedInRedis) {
            return true;
        }

        LambdaQueryWrapper<UserFollow> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(UserFollow::getUserId, userId)
                .eq(UserFollow::getFollowerId, followerId)
                .eq(UserFollow::getIsCancel, 0);

        UserFollow existFollow = userFollowMapper.selectOne(queryWrapper);
        boolean isFollowedInDB = Optional.ofNullable(existFollow).isPresent();

        if (isFollowedInDB) {
            redisService.addFollower(userId, followerId);
        }
        return isFollowedInDB;
    }

    /**
     * 【核心逻辑】将用户迁移到独立分表
     * ✅ 已修复：使用标准的 JdbcTemplate 单条 SQL 批处理模式
     */
    @Transactional(rollbackFor = Exception.class)
    public void migrateToStarTable(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户ID不能为空");
        }

        // 1. 幂等检查
        if (isStarUserMigrated(userId)) {
            log.info("用户 {} 已经是明星用户 (已迁移)，跳过", userId);
            return;
        }

        String tableName = getStarTableName(userId);
        log.info("🚀 开始为用户 {} 迁移数据到表：{}", userId, tableName);

        try {
            // 2. 动态建表
            createStarTableIfNotExists(tableName, userId);

            // 3. 分批迁移数据
            int batchSize = 1000;
            long offset = 0;
            int totalMoved = 0;

            // ✅ 修复后的 SQL：单行插入模板
            String insertSql = "INSERT INTO " + tableName +
                    " (user_id, follower_id, create_time, last_active_time, is_cancel) " +
                    "VALUES (?, ?, ?, ?, ?)";

            while (true) {
                // 从主表查询该用户的未取消关注记录
                LambdaQueryWrapper<UserFollow> queryWrapper = new LambdaQueryWrapper<>();
                queryWrapper.eq(UserFollow::getUserId, userId)
                        .eq(UserFollow::getIsCancel, 0)
                        .orderByAsc(UserFollow::getId)
                        .last("LIMIT " + offset + ", " + batchSize);

                List<UserFollow> batchData = userFollowMapper.selectList(queryWrapper);

                if (batchData == null || batchData.isEmpty()) {
                    break;
                }

                // ✅ 修复后的参数构建：构建 List<Object[]>
                List<Object[]> batchArgs = new ArrayList<>(batchData.size());
                for (UserFollow u : batchData) {
                    batchArgs.add(new Object[]{
                            u.getUserId(),
                            u.getFollowerId(),
                            u.getCreateTime(),
                            u.getLastActiveTime(),
                            u.getIsCancel()
                    });
                }

                // ✅ 修复后的执行：使用标准批处理
                // Spring 会将 batchArgs 中的每一组参数，代入 insertSql 执行一次，底层打包发送
                int[] results = jdbcTemplate.batchUpdate(insertSql, batchArgs);

                offset += batchSize;
                totalMoved += results.length;
                log.debug("批次完成，已迁移 {} 条数据...", totalMoved);
            }

            log.info("✅ 用户 {} 数据迁移完成，共迁移 {} 条记录", userId, totalMoved);

            // 4. 更新 Redis 标记
            markUserAsMigrated(userId);

            log.info("✨ 用户 {} 晋升为明星用户流程结束", userId);

        } catch (Exception e) {
            log.error("❌ 用户 {} 迁移分表失败", userId, e);
            // 抛出异常让上层 catch 处理，或者根据需求决定是否吞掉异常
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "迁移失败：" + e.getMessage());
        }
    }

    /**
     * 动态创建分表
     */
    private void createStarTableIfNotExists(String tableName, Long userId) {
        String createTableSql = String.format(
                "CREATE TABLE IF NOT EXISTS %s (" +
                        "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                        "user_id BIGINT NOT NULL, " +
                        "follower_id BIGINT NOT NULL, " +
                        "create_time DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                        "last_active_time DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                        "is_cancel TINYINT DEFAULT 0, " +
                        "INDEX idx_follower (follower_id), " +
                        "INDEX idx_user_active (user_id, last_active_time)" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝分表：用户%s'",
                tableName, userId
        );
        jdbcTemplate.execute(createTableSql);
        log.info("表 {} 创建成功 (如果之前不存在)", tableName);
    }

    private String getStarTableName(Long userId) {
        return "user_follow_" + userId;
    }

    private boolean isStarUserMigrated(Long userId) {
        return Boolean.TRUE.equals(redisService.opsForSet().isMember(STAR_USER_SET_KEY, userId.toString()));
    }

    private void markUserAsMigrated(Long userId) {
        redisService.opsForSet().add(STAR_USER_SET_KEY, userId.toString());
    }
}