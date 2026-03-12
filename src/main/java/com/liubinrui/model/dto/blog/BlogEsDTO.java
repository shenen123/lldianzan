package com.liubinrui.model.dto.blog;
import com.liubinrui.model.entity.Blog;
import lombok.Data;
import org.springframework.beans.BeanUtils;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.io.Serializable;
import java.util.Date;

@Document(indexName = "blog")
@Data
public class BlogEsDTO implements Serializable {

    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Field(type = FieldType.Long)
    private Long id;

    // 正确写法：显式指定 name = "user_id"
    @Field(name = "user_id", type = FieldType.Long)
    private Long userId;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String title;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String content;

    // 同样需要映射 thumb_count
    @Field(name = "thumb_count", type = FieldType.Integer)
    private Integer thumbCount;

    @Field(name = "visit", type = FieldType.Integer)
    private Integer visit;

    // 日期类型也需要指定格式，确保与 Mapping 一致
    @Field(name = "create_time", type = FieldType.Date, format = {}, pattern = DATE_TIME_PATTERN)
    private Date createTime;

    @Field(name = "update_time", type = FieldType.Date, format = {}, pattern = DATE_TIME_PATTERN)
    private Date updateTime;

    private static final long serialVersionUID = 1L;

    public static BlogEsDTO objToDto(Blog blog) {
        if (blog == null) {
            return null;
        }
        BlogEsDTO blogEsDTO = new BlogEsDTO();
        BeanUtils.copyProperties(blog, blogEsDTO);
        return blogEsDTO;
    }

    public static Blog objToObj(BlogEsDTO blogEsdto) {
        if (blogEsdto == null) {
            return null;
        }
        Blog blog=new Blog();
        BeanUtils.copyProperties(blogEsdto,blog);
        return blog;
    }

}
