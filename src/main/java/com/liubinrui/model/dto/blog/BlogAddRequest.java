package com.liubinrui.model.dto.blog;

import lombok.Data;

import java.io.Serializable;

@Data
public class BlogAddRequest implements Serializable {

    /**
     *
     */
    private Long userId;

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

    private static final long serialVersionUID = 1L;
}