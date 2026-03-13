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
     * @param blog
     * @param add 对创建的数据进行校验
     */
    void validblog(Blog blog, boolean add);

    /**
     * 获取查询条件
     * @param blogQueryRequest
     * @return
     */
    QueryWrapper<Blog> getQueryWrapper(BlogQueryRequest blogQueryRequest);
    
    /**
     * 获取博客封装
     * @param blog
     * @param request
     * @return
     */
    BlogVO getblogVO(Blog blog, HttpServletRequest request);

    /**
     * 分页获取博客封装
     * @param blogPage
     * @param request
     * @return
     */
    Page<BlogVO> getblogVOPage(Page<Blog> blogPage, HttpServletRequest request);

    /**
     * 从ES中查找
     * @param request
     * @return
     */
    Page<Blog> searchFromEs(BlogQueryRequest request);

    /**
     * 从博客表拉取关注博主的最新博客
     * @param followerId 粉丝ID（当前用户）
     * @param lastPullTime 最后拉取时间戳（毫秒）
     * @return 博客列表
     */
    List<Blog> listFollowedUserBlog(Long followerId, Long lastPullTime);
}
