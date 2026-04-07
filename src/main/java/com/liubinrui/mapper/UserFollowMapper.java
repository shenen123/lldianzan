package com.liubinrui.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liubinrui.model.entity.UserFollow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface UserFollowMapper extends BaseMapper<UserFollow> {
    /**
     * 查询用户的粉丝总数
     */
    Integer countFollowerByUserId(@Param("followId") Long followId);

    /**
     * 查询用户的全量粉丝ID列表（仅小V使用）
     */
    Set<Long> listAllFollowerIds(@Param("userId") Long userId);

    /**
     * 查询粉丝关注的所有博主ID
     * @param followerId
     * @return
     */
    Set<Long> listFollowedUserIds(Long followerId);

    List<UserFollow> selectCursor(Long followId, Long lastFollowerId, Integer batchSize);

    List<Map<String,Object>> getActiveFansIds(@Param("followId") Long followId, @Param("activeCount") int activeCount);

    // 获取关注的博主的ID列表
    @Select("select follow_id from user_follow where follower_id =#{followerId}")
    Set<Long> getFollowIds(Long followerId);

    /**
     * 批量删除关注关系（根据 userId + followerId）
     * @param userFollowList 包含 userId 和 followerId 的列表
     * @return 删除的行数
     */
    int batchDelete(@Param("list") List<UserFollow> userFollowList);
}
