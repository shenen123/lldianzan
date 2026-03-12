package com.liubinrui.model.dto.thumb;

import lombok.Data;

import java.io.Serializable;
@Data
public class ThumbUpdateRequest implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     *
     */
    private Long userId;

    /**
     *
     */
    private Long blogId;

    private static final long serialVersionUID = 1L;
}