package com.liubinrui.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@TableName(value ="visit")
@Data
public class Visit {
    /**
     * 
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 
     */
    private Long user_id;

    /**
     * 
     */
    private Long blog_id;

    /**
     * 创建时间
     */
    private Date createTime;
}