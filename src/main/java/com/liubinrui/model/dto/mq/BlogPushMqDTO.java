package com.liubinrui.model.dto.mq;

import lombok.Data;

import java.io.Serializable;
import java.util.Set;

@Data
public class BlogPushMqDTO {
    // 原有字段
    private Long blogId;

    private Long followId;

    private Long timestamp;

    // 粉丝ID列表
    private Set<Long> followerIds;
}