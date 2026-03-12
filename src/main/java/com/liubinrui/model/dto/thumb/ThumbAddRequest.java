package com.liubinrui.model.dto.thumb;

import lombok.Data;

import java.io.Serializable;

@Data
public class ThumbAddRequest implements Serializable {

    /**
     *
     */
    private Long blogId;

    private static final long serialVersionUID = 1L;
}