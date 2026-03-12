package com.liubinrui.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.liubinrui.common.ErrorCode;
import com.liubinrui.exception.BusinessException;
import com.liubinrui.mapper.UserFollowMapper;
import com.liubinrui.model.entity.UserFollow;
import com.liubinrui.redis.RedisService;
import com.liubinrui.service.UserFollowService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class UserFollowServiceImpl implements UserFollowService {
    @Resource
    private UserFollowMapper userFollowMapper;
    @Resource
    private RedisService redisService;

    /**
     * 关注用户：先查是否已关注 → 写入MySQL → 更新Redis
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean followUser(Long userId, Long followerId) {
        // 1. 空值校验（必加，避免后续所有操作报错）
        if (userId == null || followerId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"被关注者ID/粉丝ID不能为空");
        }

        // 2. 先查Redis缓存，判断是否已关注（高性能优先）
        boolean isFollowedInRedis = redisService.checkIsFollowed(userId, followerId);
        if (isFollowedInRedis) {
            return false; // 已关注，直接返回
        }

        // 3. 查MySQL兜底（防止Redis缓存失效，改用规范的LambdaQueryWrapper）
        LambdaQueryWrapper<UserFollow> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(UserFollow::getUserId, userId)
                .eq(UserFollow::getFollowerId, followerId)
                .eq(UserFollow::getIsCancel, 0); // 只查未取消的关注记录

        UserFollow existFollow = userFollowMapper.selectOne(queryWrapper);
        if (existFollow != null) {
            // 4. Redis缓存失效，完整回写缓存（不仅加粉丝关系，还要更新粉丝数）
            redisService.addFollower(userId, followerId);
            // 补充：同步更新粉丝数缓存（避免粉丝数统计错误）
            redisService.incrFollowerCount(userId);
            return false;
        }

        // 5. 写入MySQL分表（逻辑不变，补充字段完整性）
        UserFollow userFollow = new UserFollow();
        userFollow.setUserId(userId);
        userFollow.setFollowerId(followerId);
        userFollow.setCreateTime(LocalDateTime.now());
        userFollow.setIsCancel(0);
        userFollow.setLastActiveTime(LocalDateTime.now());
        userFollowMapper.insert(userFollow);

        // 6. 更新Redis缓存（双写一致性）
        redisService.addFollower(userId, followerId);
        redisService.incrFollowerCount(userId);

        return true;
    }

    /**
     * 取消关注：更新MySQL → 删除Redis缓存
     */
    // 取消关注的更新逻辑
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelFollow(Long userId, Long followerId) {
        // 1. 空值校验（必加，避免SQL报错）
        if (userId == null || followerId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"被关注者ID/粉丝ID不能为空");
        }

        // 2. 先查是否已关注
        boolean isFollowed = checkIsFollowed(userId, followerId);
        if (!isFollowed) {
            return false;
        }

        // 3. 构造Lambda更新器
        LambdaUpdateWrapper<UserFollow> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(UserFollow::getUserId, userId)
                .eq(UserFollow::getFollowerId, followerId)
                .eq(UserFollow::getIsCancel, 0);

        // 4. 执行更新
        int updateCount = userFollowMapper.update(
                null, // 第一个参数传null，更新字段写在wrapper里
                updateWrapper.set(UserFollow::getIsCancel, 1)
                        .set(UserFollow::getLastActiveTime, LocalDateTime.now())
        );

        // 5. 校验更新结果
        if (updateCount == 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"取消关注失败：未找到对应的关注记录");
        }

        // 6. 删除Redis缓存
        redisService.removeFollower(userId, followerId);
        redisService.decrFollowerCount(userId);

        return true;
    }

    /**
     * 检查是否已关注（Redis优先，MySQL兜底）
     */
    /**
     * 检查是否已关注（Redis优先，MySQL兜底 + 缓存回写）
     * @param userId 被关注者ID（博主）
     * @param followerId 粉丝ID
     * @return 是否已关注
     */
    public boolean checkIsFollowed(Long userId, Long followerId) {
        // 1. 空值校验（必加，避免SQL/Redis操作报错）
        if (userId == null || followerId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"被关注者ID/粉丝ID不能为空");
        }

        // 2. 优先查Redis（高性能）
        boolean isFollowedInRedis = redisService.checkIsFollowed(userId, followerId);
        if (isFollowedInRedis) {
            return true;
        }

        // 3. Redis未命中，查MySQL
        // 修正：用LambdaQueryWrapper替代QueryWrapper.lambda()
        LambdaQueryWrapper<UserFollow> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(UserFollow::getUserId, userId)
                .eq(UserFollow::getFollowerId, followerId)
                .eq(UserFollow::getIsCancel, 0);

        UserFollow existFollow = userFollowMapper.selectOne(queryWrapper);
        boolean isFollowedInDB = Optional.ofNullable(existFollow).isPresent();

        // 4. 缓存回写（关键！MySQL命中但Redis未命中时，更新Redis，避免下次重复查库）
        if (isFollowedInDB) {
            redisService.addFollower(userId, followerId);
        }

        return isFollowedInDB;
    }
}
