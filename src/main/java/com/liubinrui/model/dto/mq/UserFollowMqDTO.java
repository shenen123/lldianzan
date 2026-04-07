package com.liubinrui.model.dto.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserFollowMqDTO {
    private Long followId;

    private Long followerId;
}
