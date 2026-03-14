package com.liubinrui.service;


public interface UserFollowService {
    /**
     *用户关注博主
     * @param userId
     * @param followerId
     * @return
     */
    boolean followUser(Long userId, Long followerId);

    /**
     * 用户取消关注博主
     * @param userId
     * @param followerId
     * @return
     */
    boolean cancelFollow(Long userId, Long followerId);

    void migrateToStarTable(Long userId);
}
