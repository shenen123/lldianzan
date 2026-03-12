package com.liubinrui.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.liubinrui.mapper.BlogMapper;
import com.liubinrui.mapper.MessageMapper;
import com.liubinrui.mapper.UserFollowMapper;
import com.liubinrui.model.dto.msg.PushMsgDTO;
import com.liubinrui.model.entity.Blog;
import com.liubinrui.model.entity.Message;
import com.liubinrui.redis.RedisService;
import com.liubinrui.service.BlogPushService;
import com.liubinrui.service.BlogService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class BlogPushServiceImpl extends ServiceImpl<BlogMapper, Blog> implements BlogPushService {
    @Resource
    private RedisService redisService;
    @Resource
    private UserFollowMapper userFollowMapper;
    @Resource
    private MessageMapper messageMapper;

    // 配置参数
    @Value("${blog.follow.active-days}")
    private Integer activeDays;
    @Value("${blog.push.small-v}")
    private Long smallVThreshold;
    @Value("${blog.push.medium-v}")
    private Long mediumVThreshold;

    /**
     * 异步推送（核心入口）
     */
    @Async
    public void asyncPushBlogToFollowers(Blog blog) {
        try {
            Long userId = blog.getUserId();
            Long blogId = blog.getId();
            long timestamp = System.currentTimeMillis();
            log.info("开始推送博客，博主ID：{}，博客ID：{}", userId, blogId);

            // 1. 获取粉丝数
            Long followerCount = redisService.getFollowerCount(userId);
            if (followerCount == null) {
                followerCount = userFollowMapper.countFollowerByUserId(userId);
                redisService.cacheFollowerCount(userId, followerCount);
                log.info("从数据库获取粉丝数：{}", followerCount);
            } else {
                log.info("从Redis获取粉丝数：{}", followerCount);
            }

            // 2. 分级推送
            PushMsgDTO pushMsg = new PushMsgDTO();
            pushMsg.setBlogId(blogId);
            pushMsg.setSenderId(userId);
            pushMsg.setTimestamp(timestamp);

            if (followerCount <= smallVThreshold) {
                log.info("小V推送模式，粉丝数：{}", followerCount);
                pushMode(userId, pushMsg);
            } else if (followerCount <= mediumVThreshold) {
                log.info("中V混合模式，粉丝数：{}", followerCount);
                mixMode(userId, pushMsg);
            } else {
                log.info("大V拉模式，粉丝数：{}", followerCount);
                pullMode(userId, pushMsg);
            }
            log.info("博客推送完成，博主ID：{}", userId);
        } catch (Exception e) {
            log.error("博客推送失败，博主ID：{}", blog.getUserId(), e);
        }
    }

    /**
     * 推模式（小V）：全量推活跃粉丝 + 消息表兜底
     */
    public void pushMode(Long userId, PushMsgDTO pushMsg) {
        // 1. 获取全量粉丝（小V粉丝量小，可全量）
        Set<Long> followerIds = userFollowMapper.listAllFollowerIds(userId);
        if (CollectionUtil.isEmpty(followerIds)) return;

        // 2. 推送到Redis队列
        for (Long followerId : followerIds) {
            redisService.pushMsgToFollower(followerId, pushMsg);
        }

        // 3. 批量插入消息表（兜底，防止Redis数据丢失）
        List<Message> messageList = new ArrayList<>();
        for (Long followerId : followerIds) {
            Message message = new Message();
            message.setReceiverId(followerId);
            message.setBlogId(pushMsg.getBlogId());
            message.setSenderId(pushMsg.getSenderId());
            message.setMessageType(1); // 博客更新
            message.setIsRead(0);
            message.setCreateTime(LocalDateTime.now());
            messageList.add(message);
        }
        if (!messageList.isEmpty()) {
            messageMapper.batchInsert(messageList);
        }
    }

    /**
     * 混合模式（中V）：推活跃粉丝 + 拉非活跃粉丝
     */
    public void mixMode(Long userId, PushMsgDTO pushMsg) {
        // 1. 获取活跃粉丝
        Set<Long> activeFollowerIds = redisService.getActiveFollowerIds(userId);
        if (CollectionUtil.isEmpty(activeFollowerIds)) {
            activeFollowerIds = userFollowMapper.listActiveFollowerIds(userId, activeDays);
            redisService.cacheActiveFollowerIds(userId, activeFollowerIds);
        }

        // 2. 推活跃粉丝
        for (Long followerId : activeFollowerIds) {
            redisService.pushMsgToFollower(followerId, pushMsg);
        }

        // 3. 写入聚合表（非活跃粉丝拉取）
        redisService.addBlogToAggregation(userId, pushMsg.getBlogId(), pushMsg.getTimestamp());
    }

    /**
     * 拉模式（大V）：仅写入聚合表
     */
    private void pullMode(Long userId, PushMsgDTO pushMsg) {
        redisService.addBlogToAggregation(userId, pushMsg.getBlogId(), pushMsg.getTimestamp());
    }
}

