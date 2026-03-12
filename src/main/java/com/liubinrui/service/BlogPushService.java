package com.liubinrui.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.liubinrui.model.dto.msg.PushMsgDTO;
import com.liubinrui.model.entity.Blog;

public interface BlogPushService extends IService<Blog> {
    /**
     * 异步推送博客
     * @param blog
     */
    void asyncPushBlogToFollowers(Blog blog);
}
