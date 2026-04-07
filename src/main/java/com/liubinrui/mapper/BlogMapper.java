package com.liubinrui.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.liubinrui.model.entity.Blog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface BlogMapper extends BaseMapper<Blog> {

    void batchUpdateThumbCount(@Param("thumbList") Set<Long> thumbList, @Param("count") int count);
    //批量更新热度值
    void batchUpdateHotScore(@Param("updateList") List<Blog> updateList);

    List<Long> selectIdByCursor(long lastId, int batchSize);

    List<Blog> selectByCursor(long lastId, int batchSize);

    @Select("SELECT * FROM blog ORDER BY id LIMIT #{offset}, #{batchSize}")
    List<Blog> selectPageList(@Param("offset") int offset, @Param("batchSize") int batchSize);

    List<Blog> selectHotByCursor(Long lastId, int hotScore, int batchSize);

    @Select("select * from blog where create_time>= #{oldDate} OR update_time >= #{oldDate}")
    List<Blog> selectAfterDate(Date oldDate);

    List<Blog> rebuildBox(@Param("followIds")Set<Long> followIds, @Param("time")Long time);
}




