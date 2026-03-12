package com.liubinrui.service.impl;
import cn.hutool.core.collection.CollectionUtil;
import com.liubinrui.mapper.UserFollowMapper;
import com.liubinrui.model.dto.msg.PushMsgDTO;
import com.liubinrui.model.entity.Blog;
import com.liubinrui.model.entity.User;
import com.liubinrui.model.vo.BlogVO;
import com.liubinrui.redis.RedisService;
import com.liubinrui.service.BlogService;
import com.liubinrui.service.FollowerMsgService;
import com.liubinrui.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class FollowerMsgServiceImpl implements FollowerMsgService {
    @Resource
    private RedisService redisService;

    @Autowired
    private UserFollowMapper userFollowMapper;

    @Resource
    private BlogService blogService;

    @Autowired
    private UserService userService;

    @Override
    public List<BlogVO> getFollowedUserDynamic(Long followerId, Integer pageSize, Long lastPullTime) {
        // 1. 获取当前用户关注的所有博主ID列表
        Set<Long> followedUserIds = getFollowedUserIds(followerId);
        if (CollectionUtil.isEmpty(followedUserIds)) {
            return Collections.emptyList();
        }
        log.info("粉丝{}关注的博主列表：{}", followerId, followedUserIds);

        // 2. 拉取所有博主的动态（Redis优先）
        Set<PushMsgDTO> allDynamic = new HashSet<>();

        // 2.1 从「小V博主」的消息队列拉取（推模式）
        List<PushMsgDTO> queueMsgList = redisService.readMsgQueue(followerId, pageSize);
        allDynamic.addAll(queueMsgList);

        // 2.2 从「中V/大V博主」的聚合表拉取（拉模式）
        for (Long userId : followedUserIds) {
            Set<PushMsgDTO> aggregationMsgSet = redisService.pullBlogsFromAggregation(userId, lastPullTime);
            allDynamic.addAll(aggregationMsgSet);
        }

        // 3. Redis无数据时，从数据库兜底（消息表/博客表）
        if (CollectionUtil.isEmpty(allDynamic)) {
            allDynamic = getDynamicFromDB(followerId, lastPullTime);
        }

        // 4. 去重 + 按时间戳倒序排序
        List<PushMsgDTO> sortedDynamic = allDynamic.stream()
                .distinct() // 去重（避免推/拉模式重复）
                .sorted((o1, o2) -> Long.compare(o2.getTimestamp(), o1.getTimestamp())) // 倒序
                .limit(pageSize) // 分页
                .collect(Collectors.toList());

        // 5. 转换为BlogVO（封装博主信息、博客内容等）
        return convertToBlogVO(sortedDynamic);
    }

    /**
     * 获取当前用户关注的博主ID列表（Redis -> 数据库）
     */
    private Set<Long> getFollowedUserIds(Long followerId) {
        // 先从Redis获取（关注列表）
        Set<String> followedStrSet = redisService.opsForSet().members("follower:" + followerId);
        if (CollectionUtil.isNotEmpty(followedStrSet)) {
            return followedStrSet.stream().map(Long::valueOf).collect(Collectors.toSet());
        }
        // Redis无数据，从数据库获取并缓存
        Set<Long> followedIds = userFollowMapper.listFollowedUserIds(followerId);
        if (CollectionUtil.isNotEmpty(followedIds)) {
            redisService.opsForSet().add("follower:" + followerId, followedIds.stream().map(String::valueOf).toArray(String[]::new));
            redisService.expire("follower:" + followerId, 3600, java.util.concurrent.TimeUnit.SECONDS);
        }
        return followedIds;
    }

    /**
     * 数据库兜底获取动态
     */
    private Set<PushMsgDTO> getDynamicFromDB(Long followerId, Long lastPullTime) {
        // 从消息表获取未读博客消息
        List<PushMsgDTO> dbMsgList = userFollowMapper.listFollowedUserDynamic(followerId, lastPullTime);
        if (CollectionUtil.isEmpty(dbMsgList)) {
            // 消息表无数据，直接从博客表拉取关注博主的最新博客
            dbMsgList = blogService.listFollowedUserBlog(followerId, lastPullTime)
                    .stream()
                    .map(blog -> {
                        PushMsgDTO dto = new PushMsgDTO();
                        dto.setBlogId(blog.getId());
                        dto.setSenderId(blog.getUserId());
                        dto.setTimestamp(blog.getCreateTime().toInstant().toEpochMilli());
                        return dto;
                    })
                    .collect(Collectors.toList());
        }
        return new HashSet<>(dbMsgList);
    }

    /**
     * 转换为BlogVO（封装博客详情+博主信息）
     */
    private List<BlogVO> convertToBlogVO(List<PushMsgDTO> dynamicList) {
        if (CollectionUtil.isEmpty(dynamicList)) {
            return Collections.emptyList();
        }

        // 1. 提取所有博客ID，批量查询博客详情
        Set<Long> blogIds = dynamicList.stream().map(PushMsgDTO::getBlogId).collect(Collectors.toSet());
        Map<Long, Blog> blogMap = blogService.listByIds(blogIds).stream()
                .collect(Collectors.toMap(Blog::getId, blog -> blog));

        // 2. 提取所有博主ID，批量查询用户信息
        Set<Long> userIds = dynamicList.stream().map(PushMsgDTO::getSenderId).collect(Collectors.toSet());
        Map<Long, User> userMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, user -> user));

        // 3. 封装VO
        return dynamicList.stream()
                .map(dto -> {
                    Blog blog = blogMap.get(dto.getBlogId());
                    if (blog == null) {
                        return null;
                    }
                    BlogVO blogVO = blogService.getblogVO(blog, null);
                    // 补充博主信息
                    User blogger = userMap.get(dto.getSenderId());
                    blogVO.setCreateTime(new Date()); // 按推送时间排序
                    return blogVO;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 原有方法：获取未读消息
     */
    @Override
    public List<PushMsgDTO> getUnreadMsg(Long followerId, long lastPullTime) {
        // 复用RedisService的拉取逻辑
        Set<PushMsgDTO> unreadMsg = new HashSet<>();
        // 1. 从消息队列获取未读
        List<PushMsgDTO> queueMsg = redisService.readMsgQueue(followerId, Integer.MAX_VALUE);
        unreadMsg.addAll(queueMsg);
        // 2. 从聚合表获取增量
        Set<Long> followedUserIds = getFollowedUserIds(followerId);
        for (Long userId : followedUserIds) {
            unreadMsg.addAll(redisService.pullBlogsFromAggregation(userId, lastPullTime));
        }
        // 排序后返回
        return unreadMsg.stream()
                .sorted((o1, o2) -> Long.compare(o2.getTimestamp(), o1.getTimestamp()))
                .collect(Collectors.toList());
    }
}
