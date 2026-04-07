package com.liubinrui.repository;

import com.liubinrui.model.dto.blog.BlogEsDTO;
import org.apache.ibatis.annotations.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.annotations.Query;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BlogEsRepository extends ElasticsearchRepository<BlogEsDTO, Long> {

    // 根据用户ID查询
    // TODO
    Page<BlogEsDTO> findByUserId(Long userId, Pageable pageable);

    // 复杂查询：根据标题进行全文检索（使用 IK 分词器）
    @Query("{\"bool\": {\"must\": [{\"match\": {\"title\": \"?0\"}}]}}")
    Page<BlogEsDTO> searchByTitle(String title, Pageable pageable);

    // 复杂查询：根据内容进行全文检索
    @Query("{\"bool\": {\"must\": [{\"match\": {\"content\": \"?0\"}}]}}")
    Page<BlogEsDTO> searchByContent(String content, Pageable pageable);

    // 复杂查询：根据标题或内容进行全文检索（推荐使用）
    @Query("{\"bool\": {\"should\": [{\"match\": {\"title\": \"?0\"}}, {\"match\": {\"content\": \"?0\"}}], \"minimum_should_match\": 1}}")
    Page<BlogEsDTO> searchByKeywords(String keyword, Pageable pageable);  // 这个？0 会把这两个都替换为keyword，即第一个参数

    // 组合查询：用户ID OR 关键词
    @Query("{\"bool\": {\"should\": [" +
            "{\"term\": {\"userId\": \"?0\"}}, " +
            "{\"match\": {\"title\": \"?1\"}}, " +
            "{\"match\": {\"content\": \"?1\"}}" +
            "], \"minimum_should_match\": 1}}")
    Page<BlogEsDTO> searchByUserIdOrKeywords(
            @Param("userId") Long userId,
            @Param("keyword") String keyword,
            Pageable pageable
    );
}