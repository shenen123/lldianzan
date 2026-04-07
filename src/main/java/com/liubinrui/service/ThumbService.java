package com.liubinrui.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.liubinrui.common.DeleteRequest;
import com.liubinrui.model.dto.thumb.ThumbAddRequest;
import com.liubinrui.model.dto.thumb.ThumbDeleteRequest;
import com.liubinrui.model.dto.thumb.ThumbQueryRequest;
import com.liubinrui.model.entity.Thumb;
import com.liubinrui.model.vo.ThumbVO;
import jakarta.servlet.http.HttpServletRequest;

public interface ThumbService extends IService<Thumb> {

    /**
     * 点赞
     * @param doThumbRequest
     * @param request
     * @return
     */
    Boolean doThumb(ThumbAddRequest doThumbRequest, HttpServletRequest request);
    /**
     * 取消点赞
     * @param deleteRequest
     * @param request
     * @return
     */
    Boolean undoThumb(ThumbDeleteRequest deleteRequest, HttpServletRequest request);


}
