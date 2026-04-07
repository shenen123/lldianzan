
package com.liubinrui.utils;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 基于 Redisson 的分布式布隆过滤器服务
 * 支持集群环境，数据集中存储在 Redis
 */
@Component
public class BloomFilterService {

    @Resource
    private RedissonClient redissonClient;

    // ================== 通用方法 ==================

    /**
     * 获取或创建一个布隆过滤器
     *
     * @param name 过滤器名称 (Redis Key)
     * @param expectedInsertions 预期数据量
     * @param falseProbability 误判率 (如 0.01 代表 1%)
     * @return RBloomFilter<T>
     */
    public <T> RBloomFilter<T> getFilter(String name, long expectedInsertions, double falseProbability) {
        RBloomFilter<T> filter = redissonClient.getBloomFilter(name);

        // 第一次使用时需要初始化
        // 注意：如果多个实例同时启动，可能会重复初始化，Redisson 内部会处理并发安全
        boolean isExists = filter.tryInit(expectedInsertions, falseProbability);
        if (!isExists) {
            // 如果已经初始化过，tryInit 会返回 false，无需处理
            System.out.println("布隆过滤器 " + name + " 已存在，跳过初始化");
        }

        return filter;
    }

    /**
     * 快速获取一个默认配置的过滤器
     * 适合不想关心具体参数的场景
     */
    public <T> RBloomFilter<T> getDefaultFilter(String name) {
        return getFilter(name, 1000_000, 0.03); // 100w数据，3%误判率
    }
}