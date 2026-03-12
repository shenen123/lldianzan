package com.liubinrui.controller;

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
import com.liubinrui.service.BlogService;
import com.liubinrui.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@RequestMapping("/blog")
@Slf4j
public class BlogController {

    @Resource
    private BlogService blogService;

    @Resource
    private UserService userService;

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
        long newblogId = blog.getId();
        return ResultUtils.success(newblogId);
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

    @GetMapping("/get/vo")
    public BaseResponse<BlogVO> getBlogVOById(long id, HttpServletRequest request) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        // 查询数据库
        Blog blog = blogService.getById(id);
        ThrowUtils.throwIf(blog == null, ErrorCode.NOT_FOUND_ERROR);
        // 获取封装类
        return ResultUtils.success(blogService.getblogVO(blog, request));
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

    //从ES中查找
    @PostMapping("/search/page/vo")
    public BaseResponse<Page<BlogVO>> searchQuestionVOByPage(@RequestBody BlogQueryRequest questionQueryRequest,
                                                             HttpServletRequest request) {
        long size = questionQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 200, ErrorCode.PARAMS_ERROR);
        Page<Blog> questionPage = blogService.searchFromEs(questionQueryRequest);
        return ResultUtils.success(blogService.getblogVOPage(questionPage, request));
    }

    @PostMapping("/search/my/thumb")
    public BaseResponse<List<Blog>> searchThumbedQuestionVO(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        List<Blog> blogList = blogService.searchThumbed(loginUser);
        return ResultUtils.success(blogList);
    }
}
