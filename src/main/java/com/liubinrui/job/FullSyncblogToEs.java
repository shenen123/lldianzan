package com.liubinrui.job;

import cn.hutool.core.collection.CollUtil;
import com.liubinrui.model.dto.blog.BlogEsDTO;
import com.liubinrui.model.dto.blog.BlogEsDao;
import com.liubinrui.model.entity.Blog;
import com.liubinrui.service.BlogService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@Slf4j
public class FullSyncblogToEs implements CommandLineRunner {

    @Resource
    private BlogService blogService;

    @Resource
    private BlogEsDao blogEsDao;

    @Override
    public void run(String... args) {
        // 全量获取题目（数据量不大的情况下使用）
        List<Blog> blogList = blogService.list();
        if (CollUtil.isEmpty(blogList)) {
            return;
        }
        // 转为 ES 实体类
        List<BlogEsDTO> blogEsDTOList = blogList.stream()
                .map(BlogEsDTO::objToDto).collect(Collectors.toList());
        // 分页批量插入到 ES
        final int pageSize = 500;
        int total = blogEsDTOList.size();
        log.info("FullSyncblogToEs start, total {}", total);
        for (int i = 0; i < total; i += pageSize) {
            // 注意同步的数据下标不能超过总数据量
            int end = Math.min(i + pageSize, total);
            log.info("sync from {} to {}", i, end);
            blogEsDao.saveAll(blogEsDTOList.subList(i, end));
        }
        log.info("FullSyncblogToEs end, total {}", total);
    }
}

