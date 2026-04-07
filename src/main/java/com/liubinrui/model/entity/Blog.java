package com.liubinrui.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@TableName(value = "blog")
@Data
public class Blog implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private String title;

    private String content;

    private Integer thumbCount;

    private Integer hotScore;

    private Date createTime;

    private Date updateTime;


    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}