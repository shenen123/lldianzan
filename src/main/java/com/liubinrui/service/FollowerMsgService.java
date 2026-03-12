package com.liubinrui.service;

import com.liubinrui.model.dto.msg.PushMsgDTO;
import com.liubinrui.model.vo.BlogVO;

import java.util.List;

public interface FollowerMsgService {
    /**
     * 获取关注的人的博客动态
     * @param followerId 粉丝ID（当前登录用户）
     * @param pageSize 分页大小
     * @param lastPullTime 最后拉取时间戳（增量获取）
     * @return 按时间倒序的博客VO列表
     */
    List<BlogVO> getFollowedUserDynamic(Long followerId, Integer pageSize, Long lastPullTime);

    /**
     * 获取关注的人的未读信息
     * @param followerId
     * @param lastPullTime
     * @return
     */
    List<PushMsgDTO> getUnreadMsg(Long followerId, long lastPullTime);
}
