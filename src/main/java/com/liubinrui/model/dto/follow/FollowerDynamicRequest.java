package com.liubinrui.model.dto.follow;

import lombok.Data;

@Data
public class FollowerDynamicRequest {
    /**
     * 分页大小（默认20）
     */
    private Integer pageSize = 20;

    /**
     * 最后拉取的时间戳（增量获取，不传则拉全部）
     */
    private Long lastPullTime = 0L;
}
