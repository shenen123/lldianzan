package com.liubinrui.model.dto.thumb;

import lombok.Data;

import java.io.Serializable;

@Data
public class ThumbDeleteRequest implements Serializable {
    private Long blogId;
}
