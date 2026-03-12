package com.liubinrui.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.liubinrui.annotation.AuthCheck;
import com.liubinrui.common.BaseResponse;
import com.liubinrui.common.DeleteRequest;
import com.liubinrui.common.ErrorCode;
import com.liubinrui.common.ResultUtils;
import com.liubinrui.constant.UserConstant;
import com.liubinrui.exception.ThrowUtils;
import com.liubinrui.model.dto.thumb.ThumbAddRequest;
import com.liubinrui.model.dto.thumb.ThumbDeleteRequest;
import com.liubinrui.model.dto.thumb.ThumbQueryRequest;
import com.liubinrui.model.entity.Thumb;
import com.liubinrui.model.entity.User;
import com.liubinrui.model.vo.ThumbVO;
import com.liubinrui.service.BlogService;
import com.liubinrui.service.ThumbService;
import com.liubinrui.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/thumb")
@Slf4j
public class ThumbController {

    @Resource
    private ThumbService thumbService;

    @PostMapping("/add")
    public BaseResponse<Boolean> doThumb(@RequestBody ThumbAddRequest thumbAddRequest, HttpServletRequest request) {

        Boolean result = thumbService.doThumb(thumbAddRequest, request);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteThumb(@RequestBody ThumbDeleteRequest deleteRequest, HttpServletRequest request) {
        Boolean result = thumbService.undoThumb(deleteRequest, request);
        return ResultUtils.success(true);
    }


}
