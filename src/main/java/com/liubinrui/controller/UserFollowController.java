package com.liubinrui.controller;

import com.liubinrui.common.BaseResponse;
import com.liubinrui.common.ErrorCode;
import com.liubinrui.common.ResultUtils;
import com.liubinrui.exception.ThrowUtils;
import com.liubinrui.model.dto.follow.UserFollowRequest;
import com.liubinrui.service.UserFollowService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/user/follow")
@Slf4j
public class UserFollowController {
    @Resource
    private UserFollowService userFollowService;

    @PostMapping("/add")
    public BaseResponse<Boolean> addFollow(@RequestBody UserFollowRequest userFollowRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(userFollowRequest == null, ErrorCode.PARAMS_ERROR);

        userFollowService.addFollow(userFollowRequest,request);
        return ResultUtils.success(true);
    }

    @PostMapping("/cancel")
    public BaseResponse<Boolean> cancelFollow(@RequestBody UserFollowRequest userFollowRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(userFollowRequest == null, ErrorCode.PARAMS_ERROR);
        userFollowService.cancelFollow(userFollowRequest,request);
        return ResultUtils.success(true);
    }
}
