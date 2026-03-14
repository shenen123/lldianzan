package com.liubinrui.controller;

import com.liubinrui.common.BaseResponse;
import com.liubinrui.common.ErrorCode;
import com.liubinrui.common.ResultUtils;
import com.liubinrui.exception.ThrowUtils;
import com.liubinrui.model.dto.msg.PushMsgDTO;
import com.liubinrui.model.vo.BlogVO;
import com.liubinrui.service.FollowerMsgService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/follower/msg")
@Slf4j
public class FollowerMsgController {

    @Autowired
    private FollowerMsgService followerMsgService;

    /**
     * 获取关注博主的动态列表（核心接口）
     * @param followerId   当前用户ID（关注者ID）
     * @param pageSize     分页大小（默认10）
     * @param lastPullTime 上次拉取时间戳（毫秒），用于增量获取
     * @return 关注博主的动态列表（BlogVO）
     */
    @GetMapping("/dynamic")
    public BaseResponse<List<BlogVO>> getFollowedUserDynamic(
            @RequestParam("followerId") Long followerId,
            @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize,
            @RequestParam(value = "lastPullTime", defaultValue = "0") Long lastPullTime) {
        try {
            // 参数校验
            ThrowUtils.throwIf(followerId == null || followerId <= 0, ErrorCode.PARAMS_ERROR);

            ThrowUtils.throwIf(pageSize <= 0 || pageSize > 100, ErrorCode.PARAMS_ERROR, "限制分页大小，防止查询过多");

            // 调用Service层方法
            List<BlogVO> dynamicList = followerMsgService.getFollowedUserDynamic(followerId, pageSize, lastPullTime);
            return ResultUtils.success(dynamicList);
        } catch (Exception e) {
            log.error("获取关注博主动态失败，followerId={}, lastPullTime={}", followerId, lastPullTime, e);
            return ResultUtils.error(ErrorCode.OPERATION_ERROR);
        }
    }

    /**
     * 获取未读消息列表（增量拉取）
     * @param followerId   当前用户ID
     * @param lastPullTime 上次拉取时间戳（毫秒）
     * @return 未读消息列表
     */
    @GetMapping("/unread")
    public BaseResponse<List<PushMsgDTO>> getUnreadMsg(
            @RequestParam("followerId") Long followerId,
            @RequestParam(value = "lastPullTime", defaultValue = "0") Long lastPullTime) {
        try {
            ThrowUtils.throwIf(followerId == null || followerId <= 0, ErrorCode.PARAMS_ERROR);

            List<PushMsgDTO> unreadMsg = followerMsgService.getUnreadMsg(followerId, lastPullTime);
            return ResultUtils.success(unreadMsg);
        } catch (Exception e) {
            log.error("获取未读消息失败，followerId={}, lastPullTime={}", followerId, lastPullTime, e);
            return ResultUtils.error(ErrorCode.OPERATION_ERROR);
        }
    }
}
