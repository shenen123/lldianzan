package com.liubinrui.controller;

import cn.hutool.core.util.ObjUtil;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.liubinrui.annotation.AuthCheck;
import com.liubinrui.common.BaseResponse;
import com.liubinrui.common.DeleteRequest;
import com.liubinrui.common.ErrorCode;
import com.liubinrui.common.ResultUtils;
import com.liubinrui.constant.UserConstant;
import com.liubinrui.exception.BusinessException;
import com.liubinrui.exception.ThrowUtils;
import com.liubinrui.model.dto.blog.BlogAddRequest;
import com.liubinrui.model.dto.blog.BlogQueryRequest;
import com.liubinrui.model.dto.blog.BlogUpdateRequest;
import com.liubinrui.model.entity.Blog;

import com.liubinrui.model.entity.User;
import com.liubinrui.model.vo.BlogVO;
import com.liubinrui.service.BlogPushService;
import com.liubinrui.service.BlogService;
import com.liubinrui.service.UserService;
import com.liubinrui.service.impl.HotBlogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;


@RestController
@RequestMapping("/blog")
@Slf4j
public class BlogController {

    @Autowired
    private BlogService blogService;
    @Autowired
    private UserService userService;
    @Autowired
    private BlogPushService blogPushService;
    @Autowired
    private HotBlogService hotBlogService;

    @PostMapping("/add")
    public BaseResponse<Long> addBlog(@RequestBody BlogAddRequest blogAddRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(blogAddRequest == null, ErrorCode.PARAMS_ERROR);
        Blog blog = new Blog();
        BeanUtils.copyProperties(blogAddRequest, blog);
        // 数据校验
        blogService.validblog(blog, true);
        User loginUser = userService.getLoginUser(request);
        blog.setUserId(loginUser.getId());
        // 写入数据库
        boolean result = blogService.save(blog);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        // 返回新写入的数据 id
        long newBlogId = blog.getId();
        // 更新布隆过滤器
        hotBlogService.onBlogCreated(blog);
        // 异步推送（不阻塞发布接口）
        blogPushService.asyncPushBlogToFollowers(blog);
        return ResultUtils.success(newBlogId);
    }

    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteBlog(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User user = userService.getLoginUser(request);
        long id = deleteRequest.getId();

        // 判断是否存在
        Blog oldblog = blogService.getById(id);
        ThrowUtils.throwIf(oldblog == null, ErrorCode.NOT_FOUND_ERROR);

        // 仅本人或管理员可删除
        if (!oldblog.getUserId().equals(user.getId()) && !userService.isAdmin(request)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        // 操作数据库
        boolean result = blogService.removeById(id);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        hotBlogService.onBlogDeleted(oldblog.getId());
        return ResultUtils.success(true);
    }

    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateBlog(@RequestBody BlogUpdateRequest blogUpdateRequest, HttpServletRequest request) {
        if (blogUpdateRequest == null || blogUpdateRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Blog blog = new Blog();
        BeanUtils.copyProperties(blogUpdateRequest, blog);
        // 数据校验
        blogService.validblog(blog, false);
        // 判断是否存在
        long id = blogUpdateRequest.getId();
        Blog oldblog = blogService.getById(id);
        ThrowUtils.throwIf(oldblog == null, ErrorCode.NOT_FOUND_ERROR);
        User loginUser = userService.getLoginUser(request);
        //必须是本人修改
        ThrowUtils.throwIf(!oldblog.getUserId().equals(loginUser.getId()), ErrorCode.NO_AUTH_ERROR, "只能本人修改");
        // 操作数据库
        boolean result = blogService.updateById(blog);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }


    @GetMapping("/get")
    public BaseResponse<Blog> getBlogVOById(@RequestParam Long blogId) {
        Blog hotBlog = hotBlogService.getBlog(blogId);
        return ResultUtils.success(hotBlog);
    }

    @PostMapping("/list/page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<Blog>> listblogByPage(@RequestBody BlogQueryRequest blogQueryRequest) {
        long current = blogQueryRequest.getCurrent();
        long size = blogQueryRequest.getPageSize();
        // 查询数据库
        Page<Blog> blogPage = blogService.page(new Page<>(current, size),
                blogService.getQueryWrapper(blogQueryRequest));
        return ResultUtils.success(blogPage);
    }

    @PostMapping("/my/list/page/vo")
    public BaseResponse<Page<BlogVO>> listMyblogVOByPage(@RequestBody BlogQueryRequest blogQueryRequest,
                                                         HttpServletRequest request) {
        ThrowUtils.throwIf(blogQueryRequest == null, ErrorCode.PARAMS_ERROR);
        // 补充查询条件，只查询当前登录用户的数据
        User loginUser = userService.getLoginUser(request);
        blogQueryRequest.setUserId(loginUser.getId());
        long current = blogQueryRequest.getCurrent();
        long size = blogQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);
        // 查询数据库
        Page<Blog> blogPage = blogService.page(new Page<>(current, size),
                blogService.getQueryWrapper(blogQueryRequest));
        // 获取封装类
        return ResultUtils.success(blogService.getblogVOPage(blogPage, request));
    }

    @SentinelResource(value = "blogSearchPage", // 资源名称，控制台配置时用到
            blockHandler = "handleSearchBlock" // 限流/熔断后的处理方法
    )
    @PostMapping("/search/page/vo")
    public BaseResponse<Page<BlogVO>> searchVOByPage(@RequestBody BlogQueryRequest questionQueryRequest,
                                                     HttpServletRequest request) {
        //熔断测试代码
//        try {
//            Thread.sleep(3000);
//            log.warn("【测试熔断】模拟慢查询，休眠了 3 秒...");
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }
        long size = questionQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 200, ErrorCode.PARAMS_ERROR);
        Page<Blog> questionPage = blogService.searchFromEs(questionQueryRequest);
        return ResultUtils.success(blogService.getblogVOPage(questionPage, request));
    }

    public BaseResponse<Page<BlogVO>> handleSearchBlock(BlogQueryRequest questionQueryRequest,
                                                        HttpServletRequest request,
                                                        BlockException ex) {
        // 这里可以记录日志，或者直接返回友好的提示
        log.warn("搜索接口被限流或熔断: {}", ex.getClass().getSimpleName());
        // 返回空页面或特定错误码，避免前端崩溃
        Page<BlogVO> emptyPage = new Page<>(0, 0, 0);
        return ResultUtils.success(emptyPage);
        // 或者抛出特定的业务异常让前端展示 "系统繁忙"
        // throw new BusinessException(ErrorCode.SYSTEM_ERROR, "访问人数过多，请稍后再试");
    }


    @PostMapping("/search/hot/top")
    public BaseResponse<Page<Blog>> getHotBlogTopN(int pageNum, int pageSize) {
        // 获取热门博客ID列表
        long startTime = System.currentTimeMillis();
        List<Long> hotBlogIds = hotBlogService.getHotBlogTopN();
        log.info("查询TopN的热门博客内存查询耗费时间:{}ms", System.currentTimeMillis() - startTime);
        // 分页处理
        int start = (pageNum - 1) * pageSize;
        int end = Math.min(start + pageSize, hotBlogIds.size());
        // 若起始位置超过总长度，返回空列表
        if (start >= hotBlogIds.size()) {
            return ResultUtils.success(new Page<>(pageNum, pageSize, 0));
        }
        List<Long> blogIds = hotBlogIds.subList(start, end);
        // 转换为Blog列表
        List<Blog> blogVOList = hotBlogService.batchGetBlog(blogIds);   // 这个性能真的强，之前stream查询需要2s，现在100ms
        Page<Blog> page = new Page<>(pageNum, pageSize, hotBlogIds.size());
        page.setRecords(blogVOList);
        log.info("查询TopN的热门博客总耗费时间:{}ms", System.currentTimeMillis() - startTime);
        return ResultUtils.success(page);
    }

    @PostMapping("/search/dymatic")
    public BaseResponse<Page<Blog>> getDymatic(int pageNum, int pageSize, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        if (loginUser == null)
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);

        long time = System.currentTimeMillis();
        Page<Blog> blogPage = blogService.listDymaticBlog(time, pageNum, pageSize, loginUser.getId());

        if (blogPage.getRecords().isEmpty())
            log.info("未查询到任何动态消息");
        return ResultUtils.success(blogPage);
    }
}
