package com.liubinrui.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_follow")
public class UserFollow {

    @TableId(type = IdType.ASSIGN_ID)  // 雪花算法主键

    private Long id;

    private Long userId;       // 被关注者ID（博主）

    private Long followerId;   // 粉丝ID

    private LocalDateTime createTime;

    private Integer isCancel;  // 是否取消关注：0-未取消 1-已取消
}
