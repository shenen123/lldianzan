package com.liubinrui.config.sharding;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.liubinrui.redis.RedisUserFollowService;
import com.liubinrui.utils.ApplicationContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.driver.jdbc.core.datasource.ShardingSphereDataSource;
import org.apache.shardingsphere.infra.metadata.database.ShardingSphereDatabase;
import org.apache.shardingsphere.mode.manager.ContextManager;
import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.concurrent.TimeUnit;

@Slf4j
public class DynamicFollowShardingAlgorithm implements StandardShardingAlgorithm<Long> {

    private static final int TABLE_COUNT = 16;
    private static final String TEMPLATE_TABLE = "user_follow";

    private JdbcTemplate jdbcTemplate;
    private RedisUserFollowService redisUserFollowService;
    private RedissonClient redissonClient;
    private DataSource dataSource;

    private final Cache<String, Boolean> existTable = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .maximumSize(10000)
            .build();

    private JdbcTemplate getJdbcTemplate() {
        if (jdbcTemplate == null) {
            jdbcTemplate = ApplicationContextHolder.getBean(JdbcTemplate.class);
        }
        return jdbcTemplate;
    }

    private RedisUserFollowService getRedisUserFollowService() {
        if (redisUserFollowService == null) {
            redisUserFollowService = ApplicationContextHolder.getBean(RedisUserFollowService.class);
        }
        return redisUserFollowService;
    }

    private RedissonClient getRedissonClient() {
        if (redissonClient == null) {
            redissonClient = ApplicationContextHolder.getBean(RedissonClient.class);
        }
        return redissonClient;
    }

    private DataSource getDataSource() {
        if (dataSource == null) {
            dataSource = ApplicationContextHolder.getBean(DataSource.class);
        }
        return dataSource;
    }

    @Override
    public String doSharding(Collection<String> availableTargetNames, PreciseShardingValue<Long> shardingValue) {
        Long followId = shardingValue.getValue();
        String tableName = "user_follow_" + (Math.abs(followId) % TABLE_COUNT);
        return tableName;
    }

//    @Override
//    public String doSharding(Collection<String> availableTargetNames, PreciseShardingValue<Long> shardingValue) {
//        Long followId = shardingValue.getValue();
//
//        boolean isStar = getRedisUserFollowService().isStar(followId);
//
//        String tableName;
//        if (isStar) {
//            tableName = "user_follow_" + (Math.abs(followId) % TABLE_COUNT);
//            createTableIfNotExists(tableName);
//        } else {
//            tableName = "user_follow";
//        }
//
//        log.debug("路由表: followId={}, isStar={}, tableName={}", followId, isStar, tableName);
//        return tableName;
//    }

    private void createTableIfNotExists(String tableName) {
        if (!tableName.startsWith("user_follow_")) {
            return;
        }

        Boolean exists = existTable.getIfPresent(tableName);
        if (Boolean.TRUE.equals(exists)) {
            return;
        }

        String lockKey = "create:table:" + tableName;
        RLock lock = getRedissonClient().getLock(lockKey);
        boolean locked = false;

        try {
            locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("获取建表锁超时，tableName={}", tableName);
                return;
            }

            if (Boolean.TRUE.equals(existTable.getIfPresent(tableName))) {
                return;
            }

            if (checkTableExists(tableName)) {
                existTable.put(tableName, true);
                return;
            }

            // 创建表
            String createSql = String.format("CREATE TABLE %s LIKE %s", tableName, TEMPLATE_TABLE);
            getJdbcTemplate().execute(createSql);

            // 刷新 ShardingSphere 元数据
            refreshShardingSphereMetadata();

            log.info("动态创建分表成功: {}", tableName);
            existTable.put(tableName, true);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("获取建表锁被中断，tableName={}", tableName, e);
        } catch (Exception e) {
            log.error("动态创建分表失败: {}", tableName, e);
            if (!checkTableExists(tableName)) {
                throw new RuntimeException("创建分表失败: " + tableName, e);
            }
            existTable.put(tableName, true);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 刷新 ShardingSphere 元数据
     */
    private void refreshShardingSphereMetadata() {
        try {
            DataSource ds = getDataSource();
            if (ds instanceof ShardingSphereDataSource) {
                ShardingSphereDataSource shardingDataSource = (ShardingSphereDataSource) ds;

                // 方法1：通过反射刷新元数据
                Field contextManagerField = ShardingSphereDataSource.class.getDeclaredField("contextManager");
                contextManagerField.setAccessible(true);
                ContextManager contextManager = (ContextManager) contextManagerField.get(shardingDataSource);

                // 触发元数据刷新
                Method reloadMethod = contextManager.getClass().getDeclaredMethod("reloadMetaData");
                reloadMethod.setAccessible(true);
                reloadMethod.invoke(contextManager);

                log.info("刷新 ShardingSphere 元数据成功");
            }
        } catch (Exception e) {
            log.warn("刷新 ShardingSphere 元数据失败，但表已创建成功", e);
        }
    }

    private boolean checkTableExists(String tableName) {
        try {
            String checkSql = "SELECT COUNT(*) FROM information_schema.tables " +
                    "WHERE table_schema = DATABASE() AND table_name = ?";
            Integer count = getJdbcTemplate().queryForObject(checkSql, Integer.class, tableName);
            return count != null && count > 0;
        } catch (Exception e) {
            log.error("检查表是否存在失败: {}", tableName, e);
            return false;
        }
    }

    @Override
    public Collection<String> doSharding(Collection<String> availableTargetNames, RangeShardingValue<Long> shardingValue) {
        return new LinkedHashSet<>(availableTargetNames);
    }


    @Override
    public String getType() {
        return "CLASS_BASED";
    }
}