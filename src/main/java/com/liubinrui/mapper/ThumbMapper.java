package com.liubinrui.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.liubinrui.model.entity.Blog;
import com.liubinrui.model.entity.Thumb;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

public interface ThumbMapper extends BaseMapper<Thumb> {

    void batchInsertIgnore(List<Thumb> thumbsToInsert);

    void batchDeleteByUserAnalog(List<Map<String, Object>> deleteParams);

    @Update("delete from thumb where user_id=#{userId} and blog_id=#{blogId}")
    void deleteByUserIdAnalogId(Long userId, Long blogId);

    @Select("select * from thumb where user_id = #{id}")
    List<Blog> selectByUserId(Long id);
}




