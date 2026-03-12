package com.liubinrui.service;


public interface UserFollowService {
    boolean followUser(Long userId, Long followerId);

    boolean cancelFollow(Long userId, Long followerId);
}
