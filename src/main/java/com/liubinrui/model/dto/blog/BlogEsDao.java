package com.liubinrui.model.dto.blog;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface BlogEsDao
        extends ElasticsearchRepository<BlogEsDTO, Long> {

}

