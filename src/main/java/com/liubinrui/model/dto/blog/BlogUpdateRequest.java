package com.liubinrui.model.dto.blog;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class BlogUpdateRequest implements Serializable {

    /**
     * id
     */
    private Long id;

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

    private static final long serialVersionUID = 1L;
}