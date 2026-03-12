package com.liubinrui.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.liubinrui.model.dto.blog.BlogQueryRequest;

import com.liubinrui.model.entity.Blog;
import com.liubinrui.model.entity.User;
import com.liubinrui.model.vo.BlogVO;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

public interface BlogService extends IService<Blog> {

    /**
     * 校验数据
     *
     * @param blog
     * @param add 对创建的数据进行校验
     */
    void validblog(Blog blog, boolean add);

    /**
     * 获取查询条件
     *
     * @param blogQueryRequest
     * @return
     */
    QueryWrapper<Blog> getQueryWrapper(BlogQueryRequest blogQueryRequest);
    
    /**
     * 获取博客封装
     *
     * @param blog
     * @param request
     * @return
     */
    BlogVO getblogVO(Blog blog, HttpServletRequest request);

    /**
     * 分页获取博客封装
     *
     * @param blogPage
     * @param request
     * @return
     */
    Page<BlogVO> getblogVOPage(Page<Blog> blogPage, HttpServletRequest request);

    Page<Blog> searchFromEs(BlogQueryRequest request);

    boolean addThumb(Long blogId);

    Boolean subThumb(Long id);

    List<Blog> searchThumbed(User loginUser);
}
