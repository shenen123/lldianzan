package com.liubinrui.model.vo;

import com.liubinrui.model.entity.Thumb;
import lombok.Data;
import org.springframework.beans.BeanUtils;

import java.io.Serializable;
import java.util.Date;

/**
 * 点赞视图
 *
 * @author <a href="https://github.com/liyupi">程序员鱼皮</a>
 * @from <a href="https://www.code-nav.cn">编程导航学习圈</a>
 */
@Data
public class ThumbVO implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     *
     */
    private Long blogId;

    /**
     * 创建用户 id
     */
    private Long userId;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;


    /**
     * 创建用户信息
     */
    private UserVO user;

    /**
     * 封装类转对象
     *
     * @param thumbVO
     * @return
     */
    public static Thumb voToObj(ThumbVO thumbVO) {
        if (thumbVO == null) {
            return null;
        }
        Thumb thumb = new Thumb();
        BeanUtils.copyProperties(thumbVO, thumb);
        return thumb;
    }

    /**
     * 对象转封装类
     *
     * @param thumb
     * @return
     */
    public static ThumbVO objToVo(Thumb thumb) {
        if (thumb == null) {
            return null;
        }
        ThumbVO thumbVO = new ThumbVO();
        BeanUtils.copyProperties(thumb, thumbVO);
        return thumbVO;
    }
}
