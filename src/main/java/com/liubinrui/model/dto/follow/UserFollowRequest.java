package com.liubinrui.model.dto.follow;

import lombok.Data;

@Data
public class UserFollowRequest {
    private Long followId;
    private Long followerId;
}
