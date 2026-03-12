package com.liubinrui.model.dto.mq;

import lombok.Data;

import java.io.Serializable;
import java.util.Set;

@Data
public class BlogPushMqDTO {
    // 原有字段
    private Long blogId;
    private Long userId;
    private Long timestamp;
    // 新增字段：粉丝ID集合（小V/中V活跃粉丝）
    private Set<Long> followerIds;
    // 标记推送类型（小V/中V/大V）
    private String pushType;
}