package com.liubinrui.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.liubinrui.model.entity.Blog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;
import java.util.Map;

public interface BlogMapper extends BaseMapper<Blog> {

    boolean addThumb(Long blogId);

    boolean subThumb(Long blogId);

    void batchUpdateThumbCount(@Param("countMap") Map<Long, Long> countMap);

    void incrementThumbCount(Long blogId, Integer delta);

    List<Long> selectAllblogId();

    void updateBatchLikeCount(@Param("list") List<Blog> list);

    @Select("select * from blog where update_time >= #{minUpdateTime}")
    List<Blog> listblog(Date fiveMinutesAgoDate);

}




