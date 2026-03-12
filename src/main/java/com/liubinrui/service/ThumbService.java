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
     * 校验数据
     *
     * @param thumb
     * @param add 对创建的数据进行校验
     */
    void validThumb(Thumb thumb, boolean add);
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

    /**
     * 获取查询条件
     *
     * @param thumbQueryRequest
     * @return
     */
    QueryWrapper<Thumb> getQueryWrapper(ThumbQueryRequest thumbQueryRequest);
    
    /**
     * 获取点赞封装
     *
     * @param thumb
     * @param request
     * @return
     */
    ThumbVO getThumbVO(Thumb thumb, HttpServletRequest request);

    /**
     * 分页获取点赞封装
     *
     * @param thumbPage
     * @param request
     * @return
     */
    Page<ThumbVO> getThumbVOPage(Page<Thumb> thumbPage, HttpServletRequest request);

    /**
     * 是否已点赞
     * @param blogId
     * @param userId
     * @return
     */
    Boolean hasThumb(Long blogId, Long userId);

}
