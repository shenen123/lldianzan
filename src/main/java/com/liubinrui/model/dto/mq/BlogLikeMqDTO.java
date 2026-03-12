package com.liubinrui.model.dto.mq;

import lombok.Data;
import java.io.Serializable;

@Data
public class BlogLikeMqDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 操作用户ID (谁点的赞)
     */
    private Long userId;

    /**
     * 博客ID (给谁点赞)
     */
    private Long blogId;

    /**
     * 博主ID (被点赞的人，用于统计他的总获赞数)
     */
    private Long targetUserId;

    /**
     * 操作类型: 1-点赞, 0-取消点赞
     */
    private Integer type; // 1: like, 0: unlike
}
