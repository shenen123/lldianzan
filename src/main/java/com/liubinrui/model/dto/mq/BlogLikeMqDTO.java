package com.liubinrui.model.dto.mq;

import com.liubinrui.common.ErrorCode;
import com.liubinrui.exception.ThrowUtils;
import com.liubinrui.model.entity.Thumb;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
public class BlogLikeMqDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 操作用户ID (谁点的赞)
     */
    private Long dianZanId;

    /**
     * 博客ID (给谁点赞)
     */
    private Long blogId;

    /**
     * 博主ID (被点赞的人，用于统计他的总获赞数)
     */
    private Long beiDianZanId;

    /**
     * 操作类型: 1-点赞, 0-取消点赞
     */
    private Integer type; // 1: like, 0: unlike

    private String businessKey;  // 新增：业务唯一键

    private Long timestamp;       // 新增：时间戳

    public Thumb getToObj() {  // 不需要参数
        ThrowUtils.throwIf(false, ErrorCode.PARAMS_ERROR);
        Thumb thumb = new Thumb();
        thumb.setUserId(this.getDianZanId());
        thumb.setBlogId(this.getBlogId());
        Date date = new Date(this.getTimestamp());
        thumb.setCreateTime(date);
        return thumb;
    }
}
