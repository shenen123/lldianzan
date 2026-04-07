package com.liubinrui.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.liubinrui.common.ErrorCode;
import com.liubinrui.exception.BusinessException;
import com.liubinrui.exception.ThrowUtils;
import com.liubinrui.mapper.ThumbMapper;
import com.liubinrui.mapper.UserFollowMapper;
import com.liubinrui.model.dto.follow.UserFollowDTO;
import com.liubinrui.model.dto.follow.UserFollowRequest;
import com.liubinrui.model.entity.Thumb;
import com.liubinrui.model.entity.User;
import com.liubinrui.model.entity.UserFollow;
import com.liubinrui.redis.RedisNullCache;
import com.liubinrui.redis.RedisUserFollowService;
import com.liubinrui.service.ThumbService;
import com.liubinrui.service.UserFollowService;
import com.liubinrui.service.UserService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.groovy.util.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
public class UserFollowServiceImpl extends ServiceImpl<UserFollowMapper, UserFollow> implements UserFollowService {

    @Value("${app.test.enabled:false}")
    private boolean testMode;
    @Autowired
    private UserFollowMapper userFollowMapper;
    @Autowired
    private UserService userService;
    @Autowired
    private RedisUserFollowService redisUserFollowService;

    private final Queue<UserFollowDTO> addQueue = new ConcurrentLinkedQueue<>();
    private final Queue<UserFollowDTO> cancelQueue = new ConcurrentLinkedQueue<>();

    // 批量的阈值
    private static final int BATCH_SIZE = 100;
    private static final long BATCH_INTERVAL_MS = 1000;
    private final ScheduledExecutorService batchScheduler = Executors.newSingleThreadScheduledExecutor();

    @PostConstruct
    public void init() {
        // 修复：时间单位应该是 MILLISECONDS，不是 MICROSECONDS
        batchScheduler.scheduleAtFixedRate(this::flushBatches, 0, BATCH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("UserFollowServiceImpl初始化完成，testMode={}", testMode);
    }

    private void flushBatches() {
        flushBatchByType(1); // 关注
        flushBatchByType(0); // 取消关注
    }

    private void flushBatchByType(int type) {
        Queue<UserFollowDTO> sourceQueue = (type == 1) ? addQueue : cancelQueue;
        if (sourceQueue.isEmpty()) {
            return;
        }
        // 修复：一次性取出所有数据，不要用 while + poll 两次
        List<UserFollowDTO> userFollowDTOList = new ArrayList<>();
        UserFollowDTO dto;
        while ((dto = sourceQueue.poll()) != null) {
            userFollowDTOList.add(dto);
        }

        if (userFollowDTOList.isEmpty()) {
            return;
        }
        log.info("处理{}队列，消息容量：{}", type == 1 ? "关注" : "取消关注", userFollowDTOList.size());
        // 转换为实体并批量插入
        List<UserFollow> userFollowList = userFollowDTOList.stream()
                .map(UserFollowDTO::dtoToEntity)
                .toList();
        try {
            if (type == 1) {
                this.saveBatch(userFollowList);
                log.info("批量关注处理成功, 处理数量={}", userFollowList.size());
            } else {
                userFollowMapper.batchDelete(userFollowList);
                log.info("批量处理取消关注成功，处理数量={}", userFollowList.size());
            }
        } catch (Exception e) {
            log.error("批量处理失败，type={}", type, e);
        }
    }

    @Override
    @Transactional
    public void addFollow(UserFollowRequest userFollowRequest, HttpServletRequest httpRequest) {
        Long followId = userFollowRequest.getFollowId();
        ThrowUtils.throwIf(followId == null || followId <= 0, ErrorCode.PARAMS_ERROR);

        Long followerId;
        if (testMode) {
            followerId = userFollowRequest.getFollowerId();
            ThrowUtils.throwIf(followerId == null || followerId <= 0,
                    ErrorCode.PARAMS_ERROR, "压测模式下需要传入 followerId");
        } else {
            User loginUser = userService.getLoginUser(httpRequest);
            ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
            followerId = loginUser.getId();
        }

        ThrowUtils.throwIf(followId.equals(followerId), ErrorCode.PARAMS_ERROR, "不能关注自己");

        // ===== 重要：先检查 Redis 是否已关注 =====
        boolean alreadyFollowed = redisUserFollowService.isFollowed(followId, followerId);
        if (alreadyFollowed) {
            log.info("用户已关注过，followId={}, followerId={}", followId, followerId);
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "已经关注过了");
        }
        // ===== 再放入 DB 批量队列 =====
        UserFollowDTO userFollowDTO = new UserFollowDTO();
        userFollowDTO.setUserId(followId);
        userFollowDTO.setFollowerId(followerId);
        userFollowDTO.setCreateTime(LocalDateTime.now());
        userFollowDTO.setType(1);
        addQueue.offer(userFollowDTO);

        if (addQueue.size() >= BATCH_SIZE) {
            flushBatchByType(1);
        }
        // ===== 先写入 Redis（立即执行，不是异步）=====
        // 使用同步方法立即写入 Redis，保证后续请求能检查到
        redisUserFollowService.batchAddFollow(followId, followerId);
    }

    @Override
    @Transactional
    public void cancelFollow(UserFollowRequest userFollowRequest, HttpServletRequest httpRequest) {
        Long followId = userFollowRequest.getFollowId();
        ThrowUtils.throwIf(followId == null || followId <= 0, ErrorCode.PARAMS_ERROR);

        Long followerId;
        if (testMode) {
            // 压测模式：从请求参数获取 followerId
            followerId = userFollowRequest.getFollowerId();
            ThrowUtils.throwIf(followerId == null || followerId <= 0,
                    ErrorCode.PARAMS_ERROR, "压测模式下需要传入 followerId");
        } else {
            // 正常模式：从 Session 获取登录用户
            User loginUser = userService.getLoginUser(httpRequest);
            ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
            followerId = loginUser.getId();
        }

        // 校验不能关注自己
        ThrowUtils.throwIf(followId.equals(followerId), ErrorCode.PARAMS_ERROR, "不能关注自己");

        // 2. 放入 Redis 批量队列（替换原来的单次调用）
        boolean alreadyFollowed = redisUserFollowService.isFollowed(followId, followerId);
        if (!alreadyFollowed) {
            log.info("用户未关注过，followId={}, followerId={}", followId, followerId);

            throw new BusinessException(ErrorCode.PARAMS_ERROR, "没有关注过");
        }
        // 构建 DTO 放入队列
        UserFollowDTO userFollowDTO = new UserFollowDTO();
        userFollowDTO.setUserId(followId);
        userFollowDTO.setFollowerId(followerId);
        userFollowDTO.setCreateTime(LocalDateTime.now());
        userFollowDTO.setType(0);

        addQueue.offer(userFollowDTO);

        // 达到阈值立即刷新
        if (addQueue.size() >= BATCH_SIZE) {
            flushBatchByType(0);
        }
        redisUserFollowService.batchCancelFollow(followId, followerId);
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
        log.info("UserFollowServiceImpl已关闭");
    }
}