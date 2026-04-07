package com.liubinrui.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.liubinrui.common.ErrorCode;
import com.liubinrui.config.RabbitMqConfig;
import com.liubinrui.enums.BlogActionEnum;
import com.liubinrui.exception.BusinessException;
import com.liubinrui.exception.ThrowUtils;
import com.liubinrui.mapper.BlogMapper;
import com.liubinrui.mapper.ThumbMapper;
import com.liubinrui.model.dto.mq.BlogLikeMqDTO;
import com.liubinrui.model.dto.thumb.ThumbAddRequest;
import com.liubinrui.model.dto.thumb.ThumbDeleteRequest;
import com.liubinrui.model.dto.thumb.ThumbQueryRequest;
import com.liubinrui.model.entity.Blog;
import com.liubinrui.model.entity.Thumb;
import com.liubinrui.model.entity.User;
import com.liubinrui.model.vo.ThumbVO;

import com.liubinrui.redis.RedisNullCache;
import com.liubinrui.redis.RedisThumbLikeService;
import com.liubinrui.service.BlogService;
import com.liubinrui.service.ThumbService;
import com.liubinrui.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ThumbServiceImpl extends ServiceImpl<ThumbMapper, Thumb> implements ThumbService {
    @Value("${app.test.enabled:false}")
    private boolean testMode;

    @Value("${app.test.mock-mode:fixed}")
    private String mockMode;

    @Value("${app.test.mock-user-id:100001}")
    private Long mockUserId;

    @Autowired
    private UserService userService;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RedisNullCache redisNullCache;
    @Autowired
    private RedisThumbLikeService redisThumbLikeService;
    @Autowired
    private HotBlogService hotBlogService;

    // 热门博客本地缓存（防击穿）
    private final Cache<Long, Blog> hotBlogCache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .maximumSize(100)
            .build();

    @Override
    @Transactional
    public Boolean doThumb(ThumbAddRequest doThumbRequest, HttpServletRequest request) {
        // 1.进行数据检验
        Long blogId = doThumbRequest.getBlogId();
        ThrowUtils.throwIf(blogId == null || blogId < 0, ErrorCode.NOT_FOUND_ERROR);

//        User loginUser = userService.getLoginUser(request);
//        Long userId = loginUser.getId();
//        ThrowUtils.throwIf(userId == null || userId < 0, ErrorCode.NOT_LOGIN_ERROR);

        // 2.获取用户ID（支持压测多用户）
        Long userId;
        if (testMode) {
            if ("multi-user".equals(mockMode)) {
                // 多用户模式：从请求参数获取userId
                userId = doThumbRequest.getUserId();
                ThrowUtils.throwIf(userId == null || userId <= 0,
                        new BusinessException(ErrorCode.PARAMS_ERROR, "压测模式下需要传入userId"));
            } else {
                // 固定用户模式
                userId = mockUserId;
            }
        } else {
            // 正常模式：从Session获取
            User loginUser = userService.getLoginUser(request);
            ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
            userId = loginUser.getId();
        }

        // 2.查找空值缓存，防止击穿
        String blogNullKey = redisNullCache.buildBlogNullKey(blogId);
        if (redisNullCache.isNull(blogNullKey)) {
            //命中代表不存在
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "博客不存在");
        }

        Blog blog = hotBlogService.getBlog(blogId);
        if (blog == null) {
            // 博客不存在，设置空值缓存
            redisNullCache.setNull(blogNullKey);
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "博客不存在");
        }
        // 3.插入到Redis
        Long result = redisThumbLikeService.LuaLike(userId, blogId);
        if (result == -1) {
            log.info("用户已点过赞");
            return false;
        }

        // 更新博客热度
        hotBlogService.recordUserAction(userId, blogId, BlogActionEnum.LIKE);

        // 4.发送到消息队列，这里不分批，消息队列分批
        BlogLikeMqDTO blogLikeMqDTO = new BlogLikeMqDTO();
        blogLikeMqDTO.setDianZanId(userId);
        blogLikeMqDTO.setBlogId(blogId);
        blogLikeMqDTO.setBeiDianZanId(blog.getUserId());
        blogLikeMqDTO.setType(1);
        //blogLikeMqDTO.setBusinessKey(businessKey);
        blogLikeMqDTO.setTimestamp(System.currentTimeMillis());
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.BLOG_LIKE_EXCHANGE,
                RabbitMqConfig.BLOG_LIKE_ROUTING_KEY,
                blogLikeMqDTO);

        log.info("点赞消息已发送: dianZanId={}, blogId={}", userId, blogId);
        return true;
    }

    @Override
    @Transactional
    public Boolean undoThumb(ThumbDeleteRequest deleteRequest, HttpServletRequest request) {
        // 1.进行数据检验
        Long blogId = deleteRequest.getBlogId();
        ThrowUtils.throwIf(blogId == null || blogId < 0, ErrorCode.NOT_FOUND_ERROR);
        User loginUser = userService.getLoginUser(request);
        Long userId = loginUser.getId();
        ThrowUtils.throwIf(userId == null || userId < 0, ErrorCode.NOT_LOGIN_ERROR);

        // 2.查找空值缓存，防止击穿
        // 这里的逻辑是不是得修改
        String blogNullKey = redisNullCache.buildBlogNullKey(blogId);
        if (redisNullCache.isNull(blogNullKey)) {
            //命中代表不存在
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "博客不存在");
        }
        Blog blog = hotBlogService.getBlog(blogId);
        if (blog == null) {
            // 博客不存在，设置空值缓存
            redisNullCache.setNull(blogNullKey);
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "博客不存在");
        }

        // 3.插入到Redis
        Long result = redisThumbLikeService.LuaUnLike(userId, blogId);
        if (result == -1) {
            log.info("用户未点过赞");
            return false;
        }

        // 4.更新博客热度
        hotBlogService.recordUserAction(userId, blogId, BlogActionEnum.UNLIKE);
        // 5.发送到消息队列，这里不分批，消息队列分批
        BlogLikeMqDTO blogLikeMqDTO = new BlogLikeMqDTO();
        blogLikeMqDTO.setDianZanId(userId);
        blogLikeMqDTO.setBlogId(blogId);
        blogLikeMqDTO.setBeiDianZanId(blog.getUserId());
        blogLikeMqDTO.setType(0);
        //blogLikeMqDTO.setBusinessKey(businessKey);
        blogLikeMqDTO.setTimestamp(System.currentTimeMillis());
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.BLOG_LIKE_EXCHANGE,
                RabbitMqConfig.BLOG_LIKE_ROUTING_KEY,
                blogLikeMqDTO);

        log.info("取消点赞消息已发送: cancelDianZanId={}, blogId={}", userId, blogId);
        return true;
    }
}


