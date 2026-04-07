package com.liubinrui.model.dto.thumb;

import lombok.Data;

import java.io.Serializable;

@Data
public class ThumbAddRequest implements Serializable {

    private Long blogId;

    private Long userId;   // 新增：用于压测时传入用户ID

    private static final long serialVersionUID = 1L;
}