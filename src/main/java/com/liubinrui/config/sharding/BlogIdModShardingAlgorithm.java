package com.liubinrui.config.sharding;

import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Properties;

public class BlogIdModShardingAlgorithm implements StandardShardingAlgorithm<Long> {

    private static final int SHARDING_COUNT = 128;

    @Override
    public String doSharding(Collection<String> availableTargetNames,
                             PreciseShardingValue<Long> shardingValue) {
        Long blogId = shardingValue.getValue();
        // 对于精确查询，直接取模定位到具体的表
        String tableName = "thumb_" + (blogId % SHARDING_COUNT);

        if (availableTargetNames.contains(tableName)) {
            return tableName;
        }
        throw new IllegalArgumentException("找不到对应的表: " + tableName);
    }

    @Override
    public Collection<String> doSharding(Collection<String> availableTargetNames,
                                         RangeShardingValue<Long> shardingValue) {
        // 对于范围查询（如 BETWEEN AND），需要返回可能涉及的所有表
        // 这里简单返回所有表，确保范围查询能查到所有数据
        // 如果有性能要求，可以根据范围值进行优化
        return new LinkedHashSet<>(availableTargetNames);
    }

    @Override
    public void init(Properties properties) {
        // 初始化方法，可以读取配置参数
    }

    @Override
    public String getType() {
        return "CLASS_BASED";
    }
}