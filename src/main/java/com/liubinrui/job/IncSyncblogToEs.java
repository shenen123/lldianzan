package com.liubinrui.job;

import cn.hutool.core.collection.CollUtil;

import com.liubinrui.mapper.BlogMapper;
import com.liubinrui.model.dto.blog.BlogEsDTO;
import com.liubinrui.model.dto.blog.BlogEsDao;
import com.liubinrui.model.entity.Blog;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
@Slf4j
public class IncSyncblogToEs {

    @Resource
    private BlogMapper blogMapper;

    @Resource
    private BlogEsDao blogEsDao;

    @Resource
    private RedissonClient redissonClient;

    @Scheduled(fixedRate = 60 * 1000)
    public void run() {
        // 定义锁的 Key，全局唯一
        String lockKey = "lock:blog:sync:es";
        // 尝试获取锁，等待时间设为 0 (不等待)，leaseTime 设为 -1
        RLock lock = redissonClient.getLock(lockKey);
        boolean isLocked = false;
        try {
            // 尝试获取锁，最多等待 0 秒，锁持有时间由看门狗自动维护
            isLocked = lock.tryLock(0, -1, TimeUnit.SECONDS);

            if (!isLocked) {
                log.debug("Skip sync, lock held by another instance");
                return;
            }
            // 查询近 5 分钟内的数据
            long FIVE_MINUTES = 5 * 60 * 1000L;
            Date fiveMinutesAgoDate = new Date(new Date().getTime() - FIVE_MINUTES);
            List<Blog> blogList = blogMapper.listblog(fiveMinutesAgoDate);
            if (CollUtil.isEmpty(blogList)) {
                log.info("no inc blog");
                return;
            }
            List<BlogEsDTO> blogEsDTOList = blogList.stream()
                    .map(BlogEsDTO::objToDto)
                    .collect(Collectors.toList());
            final int pageSize = 500;
            int total = blogEsDTOList.size();
            log.info("IncSyncblogToEs start, total {}", total);
            for (int i = 0; i < total; i += pageSize) {
                int end = Math.min(i + pageSize, total);
                log.info("sync from {} to {}", i, end);
                blogEsDao.saveAll(blogEsDTOList.subList(i, end));
            }
            log.info("IncSyncblogToEs end, total {}", total);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Sync task interrupted", e);
        } finally {
            // 只有当前线程持有锁时才释放
            if (isLocked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
