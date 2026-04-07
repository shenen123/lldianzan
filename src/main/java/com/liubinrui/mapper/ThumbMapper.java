package com.liubinrui.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liubinrui.model.entity.Blog;
import com.liubinrui.model.entity.Thumb;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Set;

public interface ThumbMapper extends BaseMapper<Thumb> {

    void batchInsertIgnore(@Param("thumbList") Set<Thumb> thumbsToInsert);

    void batchDelete(@Param("deleteList")Set<Thumb> deleteList);
}




