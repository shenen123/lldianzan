package com.liubinrui.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.xiaoymin.knife4j.core.util.StrUtil;
import com.liubinrui.common.ErrorCode;
import com.liubinrui.constant.CommonConstant;
import com.liubinrui.exception.ThrowUtils;
import com.liubinrui.mapper.BlogMapper;
import com.liubinrui.model.dto.blog.BlogEsDTO;
import com.liubinrui.model.dto.blog.BlogQueryRequest;
import com.liubinrui.model.entity.Blog;
import com.liubinrui.model.entity.User;
import com.liubinrui.model.vo.BlogVO;
import com.liubinrui.model.vo.UserVO;
import com.liubinrui.redis.RedisBlogPushService;
import com.liubinrui.redis.RedisNullCache;
import com.liubinrui.redis.RedisUserFollowService;
import com.liubinrui.repository.BlogEsRepository;
import com.liubinrui.service.BlogService;

import java.util.*;

import com.liubinrui.service.UserService;
import com.liubinrui.utils.SqlUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements BlogService {

    @Autowired
    private UserService userService;
    @Autowired
    private BlogMapper blogMapper;
    @Autowired
    private RedisNullCache redisNullCache;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private BlogEsRepository blogEsRepository;
    @Autowired
    private RedisBlogPushService redisBlogPushService;
    @Autowired
    private RedisUserFollowService redisUserFollowService;
    @Autowired
    private HotBlogService hotBlogService;

    @Override
    public void validblog(Blog blog, boolean add) {
        ThrowUtils.throwIf(blog == null, ErrorCode.PARAMS_ERROR);
        String title = blog.getTitle();
        // 创建数据时，参数不能为空
        if (add) {
            ThrowUtils.throwIf(StringUtils.isBlank(title), ErrorCode.PARAMS_ERROR);
        }
        if (StringUtils.isNotBlank(title)) {
            ThrowUtils.throwIf(title.length() > 80, ErrorCode.PARAMS_ERROR, "标题过长");
        }
    }

    @Override
    public QueryWrapper<Blog> getQueryWrapper(BlogQueryRequest blogQueryRequest) {
        QueryWrapper<Blog> queryWrapper = new QueryWrapper<>();
        if (blogQueryRequest == null) {
            return queryWrapper;
        }
        String searchText = blogQueryRequest.getSearchText();
        Long userId = blogQueryRequest.getUserId();
        String title = blogQueryRequest.getTitle();
        String content = blogQueryRequest.getContent();
        String sortField = blogQueryRequest.getSortField();
        String sortOrder = blogQueryRequest.getSortOrder();

        if (StrUtil.isNotBlank(searchText)) {
            queryWrapper.and(qw -> qw.like("title", title).or().like("content", content));

        }
        queryWrapper.eq(userId != null, "user_id", userId);

        queryWrapper.orderBy(SqlUtils.validSortField(sortField),
                sortOrder.equals(CommonConstant.SORT_ORDER_ASC),
                sortField);
        return queryWrapper;

    }

    @Override
    public Page<BlogVO> getblogVOPage(Page<Blog> blogPage, HttpServletRequest request) {

        if (blogPage.getRecords().isEmpty())
            return new Page<>();
        List<Blog> blogList = blogPage.getRecords();

        Set<Long> userIds = blogList.stream().map(Blog::getUserId).collect(Collectors.toSet());
        Map<Long, User> userMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        // 转换为 BlogVO
        List<BlogVO> blogVOList = blogList.stream()
                .map(blog -> {
                    BlogVO blogVO = new BlogVO();
                    BeanUtils.copyProperties(blog, blogVO);

                    User user = userMap.get(blog.getUserId());
                    if (user != null) {
                        UserVO userVO = new UserVO();
                        BeanUtils.copyProperties(user, userVO);
                        blogVO.setUser(userVO);
                    }

                    return blogVO;
                })
                .collect(Collectors.toList());

        // 构建分页结果
        Page<BlogVO> blogVOPage = new Page<>(blogPage.getCurrent(), blogPage.getSize());
        blogVOPage.setRecords(blogVOList);
        blogVOPage.setTotal(blogPage.getTotal());

        return blogVOPage;
    }

    @Override
    public Page<Blog> searchFromEs(BlogQueryRequest request) {
        if (request == null)
            return new Page<>();
        String searchKey = generateSearchCacheKey(request);
        String searchNullKey = redisNullCache.buildSearchNullKey(searchKey);
        if (redisNullCache.isNull(searchNullKey)) {
            log.info("Redis空搜索缓存命中: {}", searchKey);
            return new Page<>();
        }
        try {
            Page<Blog> blogPage = searchFromEsInternal(request);
            if (blogPage == null || blogPage.getRecords().isEmpty())
                redisNullCache.setNull(searchNullKey);
            return blogPage;
        } catch (Exception e) {
            log.info("从ES中查询数据失败");
            // 降级，从DB中查询
            return searchFromDbWithLock(request, searchNullKey);
        }
    }

    private Page<Blog> searchFromDbWithLock(BlogQueryRequest request, String searchNullKey) {
        log.info("开始从DB中查询数据");
        // 构建锁
        Page<Blog> blogPage = new Page<>(request.getCurrent(), request.getPageSize());
        String lockKey = "lock:blog:search:db:" + searchNullKey;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                try {
                    // 双重检查Redis空值缓存
                    if (redisNullCache.isNull(searchNullKey)) {
                        return new Page<>();
                    }
                    List<Blog> blogList = blogMapper.selectList(getQueryWrapper(request));
                    if (blogList == null || blogList.isEmpty()) {
                        redisNullCache.setNull(searchNullKey);
                    }
                    blogPage.setRecords(blogList);
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            } else {
                log.info("没有抢到锁");
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("获取锁被中断", e);
        } catch (Exception e) {
            log.error("DB降级查询失败", e);
            return new Page<>();
        }
        return blogPage;
    }

    private Page<Blog> searchFromEsInternal(BlogQueryRequest request) {
        log.info("开始从ES中查询数据");
        if (request == null) {
            return new Page<>();
        }

        int current = Math.max(request.getCurrent(), 1);
        int size = request.getPageSize();
        PageRequest pageRequest = PageRequest.of(current - 1, size);

        // 必须指明，否则会和mybatis的冲突
        org.springframework.data.domain.Page<BlogEsDTO> esPage;

        if (request.getUserId() != null && request.getUserId() > 0
                && StringUtils.isNotBlank(request.getSearchText())) {
            // 两个条件都有：组合查询
            esPage = blogEsRepository.searchByUserIdOrKeywords(
                    request.getUserId(), request.getSearchText(), pageRequest);
        } else if (request.getUserId() != null && request.getUserId() > 0) {
            // 只有用户ID
            esPage = blogEsRepository.findByUserId(request.getUserId(), pageRequest);
        } else if (StringUtils.isNotBlank(request.getSearchText())) {
            // 只有关键词
            esPage = blogEsRepository.searchByKeywords(request.getSearchText(), pageRequest);
        } else {
            // 都没有
            esPage = blogEsRepository.findAll(pageRequest);
        }

        // 转换结果
        Page<Blog> blogPage = new Page<>(current, size);
        blogPage.setRecords(esPage.getContent().stream()
                .map(BlogEsDTO::objToObj)
                .collect(Collectors.toList()));
        blogPage.setTotal(esPage.getTotalElements());

        return blogPage;
    }

    private String generateSearchCacheKey(BlogQueryRequest request) {
        return String.format("%d:%d:%d:%s", request.getCurrent(),
                request.getPageSize(),
                request.getUserId() != null ? request.getUserId() : 0,
                request.getSearchText() != null ? request.getSearchText() : "");
    }

    // TODO 是否需要线程池 如果这里没有又要重建缓存，需要有降级机制，但是降级也不对，我没法查数据库呀
    @Override
    public Page<Blog> listDymaticBlog(Long time, int pageNum, int pageSize, Long followerId) {
        Page<Blog> blogPage = new Page<>(pageNum, pageSize);

        // 1. 参数校验与默认值
        if (time == null) {
            time = System.currentTimeMillis();
        }

        // 2. 获取关注列表 (核心数据源)
        Set<Long> followIds = redisUserFollowService.getFollowIds(followerId);
        if (followIds.isEmpty()) {
            log.info("用户 {} 未关注任何人", followerId);
            blogPage.setRecords(Collections.emptyList());
            blogPage.setTotal(0);
            return blogPage;
        }

        // 3. 核心逻辑：尝试从缓存获取博客ID
        Set<Long> blogIdSet = new LinkedHashSet<>(); // 使用 LinkedHashSet 保持插入顺序（虽然这里主要靠后续排序）
        boolean cacheHit = true;

        try {
            // A. 获取收件箱 (推模式)
            Set<Long> inBoxIds = redisBlogPushService.getFromInbox(followerId);
            log.info("自己信箱收到{}条消息",inBoxIds.size());
            // B. 获取发件箱 (拉模式/兜底): 查询关注者最近一周的发件箱
            Set<Long> outBoxIds = redisBlogPushService.getFromOutBox(followIds, time);
            log.info("从博主拉取收到{}条消息",outBoxIds.size());
            // C. 合并：收件箱为主，发件箱为辅 (去重)
            if (inBoxIds != null && !inBoxIds.isEmpty()) {
                blogIdSet.addAll(inBoxIds);
            }
            if (outBoxIds != null && !outBoxIds.isEmpty()) {
                blogIdSet.addAll(outBoxIds);
            }

            // 如果两者都为空，说明缓存中没有数据（可能是新用户或数据过期）
            if (blogIdSet.isEmpty()) {
                cacheHit = false;
                log.warn("用户 {} 动态流缓存未命中，需异步重建", followerId);
            }

        } catch (Exception e) {
            // 防御性编程：缓存查询出错不应导致业务中断
            log.error("用户 {} 查询动态流缓存异常: {}", followerId, e.getMessage(), e);
            cacheHit = false;
        }
        // 4. 异步重建缓存 (如果缓存未命中)  重要：不要阻塞主线程等待重建完成
        if (!cacheHit) {
            // 立即返回一个空页面，同时异步去数据库捞数据重建 Redis
            Long finalTime = time;
            CompletableFuture.runAsync(() -> {
                try {
                    List<Blog> freshBlogs = blogMapper.rebuildBox(followIds, finalTime - 604800 * 1000L);
                    redisBlogPushService.rebuildBox(followerId, freshBlogs);
                } catch (Exception ex) {
                    log.error("异步重建用户 {} 动态流缓存失败", followerId, ex);
                }
            });
            // 方案 B：返回空，提示“暂无数据”
            blogPage.setRecords(Collections.emptyList());
            blogPage.setTotal(0);
            return blogPage;
        }

        // 但为了修复你当前的逻辑，我们先按 ID 查详情，然后内存分页。
        // 计算分页
        int total = blogIdSet.size();
        List<Long> allBlogIdList = new ArrayList<>(blogIdSet);

        // 简单的内存分页（注意：这不是按时间排序的，只是按 ID 获取）
        int start = (pageNum - 1) * pageSize;
        if (start >= total) {
            blogPage.setRecords(Collections.emptyList());
            blogPage.setTotal(total);
            return blogPage;
        }
        List<Long> pageBlogIds = allBlogIdList.subList(start, Math.min(start + pageSize, total));

        // 6. 批量查询博客详情
        // 这里需要一个批量查询接口，不要循环查 DB
        List<Blog> blogList =hotBlogService.batchGetBlog(pageBlogIds);

        // 7. 设置结果
        blogPage.setRecords(blogList);
        blogPage.setTotal(total);

        log.info("用户 {} 动态消息查询完成，总条数: {}, 当前页条数: {}, 缓存命中: {}",
                followerId, total, blogList.size(), cacheHit);
        return blogPage;
    }
    // 新的方法
//    public Page<BlogEsDTO> searchWithOrConditions(Long userId, String keyword, Pageable pageable) {
//        // 动态构建查询
//        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();
//
//        // 根据情况添加条件（这些条件之间是 OR 关系）
//        if (userId != null && userId > 0) {
//            boolQuery.should(QueryBuilders.termQuery("userId", userId));
//        }
//
//        if (StringUtils.hasText(keyword)) {
//            boolQuery.should(QueryBuilders.multiMatchQuery(keyword, "title", "content"));
//        }
//
//        // 如果没有条件，返回空结果
//        if (boolQuery.should().isEmpty()) {
//            return Page.empty(pageable);
//        }
//
//        // 至少匹配一个条件
//        boolQuery.minimumShouldMatch(1);
//
//        Query query = new NativeSearchQueryBuilder()
//                .withQuery(boolQuery)
//                .withPageable(pageable)
//                .build();
//
//        SearchHits<BlogEsDTO> searchHits = elasticsearchRestTemplate.search(query, BlogEsDTO.class);
//        return SearchHitSupport.page(searchHits, pageable);


}
