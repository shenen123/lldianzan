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

    private Date createTime;

    private Date updateTime;

    private Integer hotScore;
    /**
     * 创建用户信息
     */
    private UserVO user;

}
