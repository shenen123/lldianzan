package com.liubinrui.model.dto.thumb;

import com.liubinrui.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

@EqualsAndHashCode(callSuper = true)
@Data
public class ThumbQueryRequest extends PageRequest implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * id
     */
    private Long notId;

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