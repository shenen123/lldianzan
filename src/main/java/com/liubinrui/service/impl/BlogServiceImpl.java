package com.liubinrui.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.liubinrui.common.ErrorCode;
import com.liubinrui.constant.CommonConstant;
import com.liubinrui.exception.ThrowUtils;
import com.liubinrui.mapper.BlogMapper;
import com.liubinrui.mapper.ThumbMapper;
import com.liubinrui.mapper.UserFollowMapper;
import com.liubinrui.model.dto.blog.BlogEsDTO;
import com.liubinrui.model.dto.blog.BlogQueryRequest;
import com.liubinrui.model.entity.Blog;
import com.liubinrui.model.entity.User;
import com.liubinrui.model.vo.BlogVO;
import com.liubinrui.model.vo.UserVO;
import com.liubinrui.service.BlogService;

import java.util.*;

import com.liubinrui.service.UserService;
import com.liubinrui.utils.SqlUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Service
@Slf4j
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements BlogService {

    @Resource
    private UserService userService;
    @Resource
    private BlogMapper blogMapper;
    @Autowired
    @Lazy
    private UserFollowMapper userFollowMapper;
    @Autowired
    private ThumbMapper thumbMapper;
    @Resource
    private final ElasticsearchOperations elasticsearchOperations;


    public BlogServiceImpl(ElasticsearchOperations elasticsearchOperations) {
        this.elasticsearchOperations = elasticsearchOperations;
    }

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

        Long id = blogQueryRequest.getId();
        Long notId = blogQueryRequest.getNotId();
        String searchText = blogQueryRequest.getSearchText();
        Long userId = blogQueryRequest.getUserId();
        String title = blogQueryRequest.getTitle();
        String content = blogQueryRequest.getContent();
        Integer thumbCount = blogQueryRequest.getThumbCount();
        String sortField = blogQueryRequest.getSortField();
        String sortOrder = blogQueryRequest.getSortOrder();
        if (StringUtils.isNotBlank(searchText)) {
            // 需要拼接查询条件
            queryWrapper.and(qw -> qw.like("title", searchText).or().like("content", searchText));
        }
        // 模糊查询
        queryWrapper.like(StringUtils.isNotBlank(title), "title", title);
        queryWrapper.like(StringUtils.isNotBlank(content), "content", content);
        // 精确查询
        queryWrapper.eq(id != null && id > 0, "id", id);
        queryWrapper.eq(notId != null && notId > 0, "id", notId);
        queryWrapper.eq(userId != null && userId > 0, "user_id", userId);
        queryWrapper.eq(thumbCount != null && thumbCount > 0, "thumb_count", thumbCount);
        // 排序规则
        queryWrapper.orderBy(SqlUtils.validSortField(sortField),
                sortOrder.equals(CommonConstant.SORT_ORDER_ASC),
                sortField);
        return queryWrapper;
    }

    @Override
    public BlogVO getblogVO(Blog blog, HttpServletRequest request) {
        // 对象转封装类
        BlogVO blogVO = BlogVO.objToVo(blog);
        Long userId = blog.getUserId();
        User user = null;
        if (userId != null && userId > 0) {
            user = userService.getById(userId);
        }
        UserVO userVO = userService.getUserVO(user);
        blogVO.setUser(userVO);

        return blogVO;
    }

    @Override
    public Page<BlogVO> getblogVOPage(Page<Blog> blogPage, HttpServletRequest request) {
        List<Blog> blogList = blogPage.getRecords();
        Page<BlogVO> blogVOPage = new Page<>(blogPage.getCurrent(), blogPage.getSize(), blogPage.getTotal());
        if (CollUtil.isEmpty(blogList)) {
            return blogVOPage;
        }
        // 对象列表 => 封装对象列表
        List<BlogVO> blogVOList = blogList.stream().map(BlogVO::objToVo).collect(Collectors.toList());
        Set<Long> userIdSet = blogList.stream().map(Blog::getUserId).collect(Collectors.toSet());
        Map<Long, List<User>> userIdUserListMap = userService.listByIds(userIdSet).stream()
                .collect(Collectors.groupingBy(User::getId));

        blogVOList.forEach(blogVO -> {
            Long userId = blogVO.getUserId();
            User user = null;
            if (userIdUserListMap.containsKey(userId)) {
                user = userIdUserListMap.get(userId).get(0);
            }
            blogVO.setUser(userService.getUserVO(user));
        });
        blogVOPage.setRecords(blogVOList);
        return blogVOPage;
    }

    @Override
    public boolean addThumb(Long blogId) {

        return true;
    }

    @Override
    public Boolean subThumb(Long blogId) {
        ThrowUtils.throwIf(blogId < 0 || blogId == null, ErrorCode.NOT_FOUND_ERROR);
        boolean result = blogMapper.subThumb(blogId);
        return true;
    }

    @Override
    public List<Blog> searchThumbed(User loginUser) {
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        return thumbMapper.selectByUserId(loginUser.getId());
    }

    @Override
    public Page<Blog> searchFromEs(BlogQueryRequest request) {
        try {
            // 1. 尝试执行 ES 查询
            log.debug("Attempting to search from Elasticsearch...");
            return searchFromEsInternal(request);
        } catch (Exception e) {
            // 2. 捕获所有 ES 相关异常 (ConnectException, SocketTimeoutException, etc.)
            log.error("Elasticsearch search failed, switching to Database fallback. Error: {}", e.getMessage(), e);
            // 3.执行降级逻辑：查数据库,DB 查询通常不支持全文检索，只能做模糊匹配或精确匹配，功能会减弱
            return listBlogByPage(request);
        }
    }

    public Page<Blog> listBlogByPage(BlogQueryRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);

        int current = request.getCurrent();
        int pageSize = request.getPageSize();
        QueryWrapper<Blog> queryWrapper = this.getQueryWrapper(request);
        return this.page(new Page<>(current, pageSize), queryWrapper);
    }

    public Page<Blog> searchFromEsInternal(BlogQueryRequest blogQueryRequest) {
        // 1. 获取参数并做严格的安全校验
        Long id = blogQueryRequest.getId();
        String searchText = blogQueryRequest.getSearchText();
        Long userId = blogQueryRequest.getUserId();
        String sortField = blogQueryRequest.getSortField();
        String sortOrder = blogQueryRequest.getSortOrder();

        // 分页参数安全处理（避免负数/0，限制最大页大小）
        int current = Math.max((ObjUtil.isEmpty(blogQueryRequest.getCurrent()) ? 1 : blogQueryRequest.getCurrent()) - 1, 0);
        int pageSize = ObjUtil.isEmpty(blogQueryRequest.getPageSize()) ? 10 : blogQueryRequest.getPageSize();

        // 2. 构建 Bool 查询（核心修复：处理空条件）
        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
        boolean hasFilter = false;
        boolean hasShould = false;

        // A. 精确过滤条件（只处理 >0 的有效值，避免无效查询）
        if (id != null && id > 0) {
            boolBuilder.filter(f -> f.term(t -> t.field("id").value(FieldValue.of(id))));
            hasFilter = true;
        }
        if (userId != null && userId > 0) {
            boolBuilder.filter(f -> f.term(t -> t.field("user_id").value(FieldValue.of(userId))));
            hasFilter = true;
        }

        // B. 全文检索条件（只处理非空/非空白字符串，适配IK分词）
        if (StrUtil.isNotBlank(searchText)) {
            hasShould = true;
            // 标题匹配（使用IK最大分词，与ES映射一致）
            boolBuilder.should(s -> s.match(m -> m
                    .field("title")
                    .query(searchText)
                    .analyzer("ik_max_word")
            ));
            // 内容匹配（同理）
            boolBuilder.should(s -> s.match(m -> m
                    .field("content")
                    .query(searchText)
                    .analyzer("ik_max_word")
            ));
            // 至少匹配一个should条件
            boolBuilder.minimumShouldMatch("1");
        }

        // 核心修复：无任何有效条件时，执行matchAll（匹配所有文档），避免空Bool查询
        Query finalQuery;
        if (hasFilter || hasShould) {
            finalQuery = Query.of(q -> q.bool(boolBuilder.build()));
        } else {
            finalQuery = Query.of(q -> q.matchAll(m -> m)); // 兜底查询：匹配所有文档
        }

        // 3. 构建排序规则（修复：校验字段合法性，简化默认排序）
        List<co.elastic.clients.elasticsearch._types.SortOptions> sortOptions = new ArrayList<>();
        // 定义ES中合法的排序字段（与映射完全一致）
        List<String> validSortFields = Arrays.asList("id", "user_id", "thumb_count", "create_time", "update_time", "visit");

        if (StrUtil.isNotBlank(sortField)) {
            // 转换前端字段名到ES字段名（驼峰转下划线）
            String esField = convertToEsField(sortField);
            // 校验字段合法性，非法字段默认用create_time
            if (!validSortFields.contains(esField)) {
                log.warn("非法排序字段：{}，默认使用create_time排序", sortField);
                esField = "create_time";
            }
            // 确定排序方向（默认降序）
            SortOrder order = "asc".equalsIgnoreCase(sortOrder) ? SortOrder.Asc : SortOrder.Desc;
            String finalEsField = esField;
            sortOptions.add(co.elastic.clients.elasticsearch._types.SortOptions.of(so -> so
                    .field(f -> f
                            .field(finalEsField)
                            .order(order)
                    )
            ));
        } else {
            // 默认排序：按创建时间降序（简化规则，避免多排序叠加异常）
            sortOptions.add(co.elastic.clients.elasticsearch._types.SortOptions.of(so -> so
                    .field(f -> f
                            .field("create_time")
                            .order(SortOrder.Desc)
                    )
            ));
        }

        // 4. 构建NativeQuery（标准化分页，确保总条数准确）
        NativeQuery nativeQuery = NativeQuery.builder()
                .withQuery(finalQuery)          // 填入最终查询（matchAll/Bool）
                .withSort(sortOptions)          // 填入合法的排序规则
                .withPageable(PageRequest.of(current, pageSize)) // 标准化分页
                .withTrackTotalHits(true)       // 确保返回准确总条数
                .build();

        log.info("执行ES搜索 - 索引: blog, 起始位置: {}, 页大小: {}, 检索关键词: {}, 查询条件: {}",
                current * pageSize, pageSize, searchText, finalQuery.toString());

        try {
            // 5. 执行搜索（显式指定索引，避免注解不一致问题）
            SearchHits<BlogEsDTO> searchHits = elasticsearchOperations.search(
                    nativeQuery,
                    BlogEsDTO.class,
                    IndexCoordinates.of("blog") // 强制指定实际存在的索引名
            );

            // 6. 结果转换与分页封装（保持原有逻辑）
            long total = searchHits.getTotalHits();
            Page<Blog> page = new Page<>(
                    ObjUtil.isEmpty(blogQueryRequest.getCurrent()) ? 1 : blogQueryRequest.getCurrent(),
                    ObjUtil.isEmpty(blogQueryRequest.getPageSize()) ? 10 : blogQueryRequest.getPageSize(),
                    total
            );

            List<Blog> resourceList = new ArrayList<>();
            if (searchHits.hasSearchHits()) {
                resourceList = searchHits.getSearchHits().stream()
                        .map(SearchHit::getContent)
                        .map(BlogEsDTO::objToObj) // 复用原有DTO转BO方法
                        .collect(Collectors.toList());
            }
            page.setRecords(resourceList);

            return page;
        } catch (Exception e) {
            // 打印详细异常（含ES原始错误），方便定位问题
            log.error("ES搜索失败 - 参数: {}", blogQueryRequest, e);
            throw new RuntimeException("搜索服务异常：" + e.getMessage(), e);
        }
    }

    /**
     * 字段名转换：前端驼峰字段 → ES下划线字段（适配映射）
     *
     * @param sortField 前端传入的排序字段
     * @return ES中的合法字段名
     */
    private String convertToEsField(String sortField) {
        if (StrUtil.isBlank(sortField)) {
            return "create_time";
        }
        // 按实际业务场景扩展字段映射
        return switch (sortField) {
            case "thumbCount" -> "thumb_count";
            case "createTime" -> "create_time";
            case "updateTime" -> "update_time";
            case "userId" -> "user_id";
            case "visitCount" -> "visit"; // 前端可能传visitCount，对应ES的visit字段
            default -> sortField.toLowerCase(); // 其他字段默认转小写（如id→id）
        };
    }

    /**
     * 从博客表拉取关注博主的最新博客
     */
    @Override
    public List<Blog> listFollowedUserBlog(Long followerId, Long lastPullTime) {
        // 1. 先获取当前用户关注的所有博主ID
        Set<Long> followedUserIds = userFollowMapper.listFollowedUserIds(followerId);
        if (followedUserIds.isEmpty()) {
            return List.of();
        }

        // 2. 拉取这些博主在lastPullTime之后发布的博客
        LambdaQueryWrapper<Blog> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(Blog::getUserId, followedUserIds)  // 博主ID在关注列表中
                .gt(Blog::getCreateTime, new java.util.Date(lastPullTime))  // 增量获取
                .orderByDesc(Blog::getCreateTime);  // 按发布时间倒序

        return blogMapper.selectList(queryWrapper);
    }
}
