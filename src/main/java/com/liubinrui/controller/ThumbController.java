package com.liubinrui.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
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
    @SentinelResource(value = "thumbAdd", blockHandler = "handleThumbBlock")
    public BaseResponse<Boolean> doThumb(@RequestBody ThumbAddRequest thumbAddRequest, HttpServletRequest request) {
        Boolean result = thumbService.doThumb(thumbAddRequest, request);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    public BaseResponse<Boolean> handleThumbBlock(ThumbAddRequest thumbAddRequest,
                                                  HttpServletRequest request,
                                                  BlockException ex) {
        log.warn("搜索接口被限流或熔断: {}", ex.getClass().getSimpleName());
        return ResultUtils.error(ErrorCode.OPERATION_ERROR, "操作太频繁，请稍后再试");
    }

    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteThumb(@RequestBody ThumbDeleteRequest deleteRequest, HttpServletRequest request) {
        Boolean result = thumbService.undoThumb(deleteRequest, request);
        return ResultUtils.success(true);
    }


}
