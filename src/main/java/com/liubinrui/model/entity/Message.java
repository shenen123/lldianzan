package com.liubinrui.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("message")
public class Message {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long receiverId;   // 接收者（粉丝）ID
    private Long blogId;       // 关联博客ID
    private Long senderId;     // 发送者（博主）ID
    private Integer messageType; // 消息类型：1-博客更新
    private Integer isRead;    // 是否已读：0-未读 1-已读
    private LocalDateTime createTime;
}
