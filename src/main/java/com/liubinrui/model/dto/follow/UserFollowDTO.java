package com.liubinrui.model.dto.follow;

import com.liubinrui.model.entity.UserFollow;
import lombok.Data;
import org.springframework.beans.BeanUtils;

import java.time.LocalDateTime;

@Data
public class UserFollowDTO {
    private Long userId;       // 被关注者ID（博主）

    private Long followerId;   // 粉丝ID

    private LocalDateTime createTime;

    private Integer type;

    public static UserFollow dtoToEntity(UserFollowDTO userFollowDTO) {
        UserFollow userFollow = new UserFollow();
        BeanUtils.copyProperties(userFollowDTO, userFollow);
        if (userFollowDTO.getType() == 1)
            userFollow.setIsCancel(0);
        else userFollow.setIsCancel(1);
        return userFollow;
    }
}
