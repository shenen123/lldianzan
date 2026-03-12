package com.liubinrui.model.vo;

import com.liubinrui.model.entity.Blog;
import lombok.Data;
import org.springframework.beans.BeanUtils;

import java.io.Serializable;
import java.util.Date;

@Data
public class BlogVO implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * 标题
     */
    private String title;

    /**
     * 内容
     */
    private String content;

    /**
     * 点赞数
     */
    private Integer thumbCount;

    /**
     * 创建用户 id
     */
    private Long userId;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;


    /**
     * 创建用户信息
     */
    private UserVO user;

    public static Blog voToObj(BlogVO blogVO) {
        if (blogVO == null) {
            return null;
        }
        Blog blog = new Blog();
        BeanUtils.copyProperties(blogVO, blog);
        return blog;
    }

    public static BlogVO objToVo(Blog blog) {
        if (blog == null) {
            return null;
        }
        BlogVO blogVO = new BlogVO();
        BeanUtils.copyProperties(blog, blogVO);
        return blogVO;
    }
}
