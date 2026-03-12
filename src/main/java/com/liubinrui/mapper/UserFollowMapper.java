package com.liubinrui.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liubinrui.model.dto.msg.PushMsgDTO;
import com.liubinrui.model.entity.UserFollow;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Set;

public interface UserFollowMapper extends BaseMapper<UserFollow> {
    /**
     * 查询用户的粉丝总数
     */
    Long countFollowerByUserId(@Param("userId") Long userId);

    /**
     * 查询用户的活跃粉丝ID列表（近30天活跃）
     */
    Set<Long> listActiveFollowerIds(@Param("userId") Long userId, @Param("activeDays") Integer activeDays);

    /**
     * 查询用户的全量粉丝ID列表（仅小V使用）
     */
    Set<Long> listAllFollowerIds(@Param("userId") Long userId);
    /**
     * 从消息表获取关注的人的博客动态
     * @param followerId 粉丝ID（当前用户）
     * @param lastPullTime 最后拉取时间戳（毫秒）
     * @return 博客消息列表
     */
    List<PushMsgDTO> listFollowedUserDynamic(@Param("followerId") Long followerId,
                                             @Param("lastPullTime") Long lastPullTime);

    Set<Long> listFollowedUserIds(Long followerId);
}
