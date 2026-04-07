package com.liubinrui.service;


import com.liubinrui.model.dto.follow.UserFollowRequest;
import jakarta.servlet.http.HttpServletRequest;

public interface UserFollowService {
    /**
     *用户关注博主
     * @param userId
     * @param followerId
     * @return
     */
    void addFollow(UserFollowRequest userFollowRequest,HttpServletRequest httpRequest);

    /**
     * 用户取消关注博主
     * @param userId
     * @param followerId
     * @return
     */
    void cancelFollow(UserFollowRequest userFollowRequest, HttpServletRequest httpRequest);

}
