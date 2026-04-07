package com.liubinrui.redis;

import com.liubinrui.common.ErrorCode;
import com.liubinrui.exception.ThrowUtils;
import com.liubinrui.mapper.UserFollowMapper;
import com.liubinrui.model.dto.follow.FollowRelation;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Component
@Slf4j
public class RedisUserFollowService {

    @Resource
    @Qualifier("redisExecutor")
    private Executor redisExecutor;
    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private UserFollowMapper userFollowMapper;

    private static final int STAR_FAN_THRESHOLD = 10;
    // ==================== 批量处理队列 ====================
    private final Queue<FollowRelation> followQueue = new ConcurrentLinkedQueue<>();
    private final Queue<FollowRelation> cancelQueue = new ConcurrentLinkedQueue<>();

    private static final int REDIS_BATCH_SIZE = 1000;
    private static final long REDIS_BATCH_INTERVAL_MS = 1000;
    private final ScheduledExecutorService batchScheduler = Executors.newSingleThreadScheduledExecutor();

    @PostConstruct
    public void initBatchProcessor() {
        batchScheduler.scheduleAtFixedRate(this::flushRedisBatches, 0, REDIS_BATCH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("RedisFollow批量处理器初始化完成");
    }

    @PreDestroy
    public void destroyBatchProcessor() {
        flushRedisBatches();
        batchScheduler.shutdown();
        try {
            if (!batchScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                batchScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            batchScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void flushRedisBatches() {
        flushRedisBatchByType(1);  // 关注
        flushRedisBatchByType(0);  // 取消关注
    }

    private void flushRedisBatchByType(int type) {
        Queue<FollowRelation> sourceQueue = (type == 1) ? followQueue : cancelQueue;
        if (sourceQueue.isEmpty()) {
            return;
        }
        List<FollowRelation> batchList = new ArrayList<>();
        FollowRelation relation;
        while ((relation = sourceQueue.poll()) != null) {
            batchList.add(relation);
        }
        if (batchList.isEmpty()) {
            return;
        }
        log.info("Redis批量处理{}队列，数量：{}", type == 1 ? "关注" : "取消关注", batchList.size());
        try {
            if (type == 1) {
                batchLuaAddFollow(batchList);
            } else {
                batchLuaCancelFollow(batchList);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * 批量关注（添加到队列）
     */
    public void batchAddFollow(Long followId, Long followerId) {
        followQueue.offer(new FollowRelation(followId, followerId));
//        if (followQueue.size() >= REDIS_BATCH_SIZE) {
//            flushRedisBatchByType(1);
//        }
        flushRedisBatchByType(1);
    }

    /**
     * 批量取消关注（添加到队列）
     */
    public void batchCancelFollow(Long followId, Long followerId) {
        cancelQueue.offer(new FollowRelation(followId, followerId));
//        if (cancelQueue.size() >= REDIS_BATCH_SIZE) {
//            flushRedisBatchByType(0);
//        }
        flushRedisBatchByType(0);
    }

    /**
     * 批量关注 - 使用原生命令模拟 Lua 脚本（管道版）
     * 不用 eval，改用原生命令组合，100% 兼容
     */
    private void batchLuaAddFollow(List<FollowRelation> relations) throws InterruptedException {
        if (relations == null || relations.isEmpty()) return;

        int batchSize = 100;
        // 记录哪些博主的粉丝数可能达到阈值
        Set<Long> needCheckFollowIds = new HashSet<>();

        for (int i = 0; i < relations.size(); i += batchSize) {
            int end = Math.min(i + batchSize, relations.size());
            List<FollowRelation> batch = relations.subList(i, end);

            // 使用管道批量执行原生命令
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (FollowRelation relation : batch) {
                    Long followId = relation.getFollowId();
                    Long followerId = relation.getFollowerId();

                    String followKey = FOLLOW_SET_KEY + followId;
                    String followerKey = FOLLOWER_SET_KEY + followerId;
                    String followerIdStr = String.valueOf(followerId);
                    String followIdStr = String.valueOf(followId);

                    connection.sAdd(followKey.getBytes(), followerIdStr.getBytes());
                    connection.sAdd(followerKey.getBytes(), followIdStr.getBytes());
                    connection.zIncrBy(FOLLOW_RANK_ZSET_KEY.getBytes(), 1, followIdStr.getBytes());

                    // 记录需要检查的博主ID
                    needCheckFollowIds.add(followId);
                }
                return null;
            });
        }

        // 批量检查并设置明星标识
        for (Long followId : needCheckFollowIds) {
            checkAndSetStar(followId);
        }
        log.info("批量关注完成，总数={}", relations.size());
    }

    /**
     * 检查粉丝数是否达到明星阈值，如果是则设置明星标识
     */
    private void checkAndSetStar(Long followId) {
        int fansCount = getFansCount(followId);
        if (fansCount >= STAR_FAN_THRESHOLD) {  // STAR_FAN_THRESHOLD = 100
            if (!isStar(followId)) {
                setStar(followId);
                List<Map<String, Object>> activeMap = null;
                // TODO 这里会卡死，导致查不出问题，从DB获得
                try {
                    Thread.sleep(1500);
                    activeMap = userFollowMapper.getActiveFansIds(followId, 100);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

                rebuildActiveFans(followId, activeMap);
                log.info("博主 {} 粉丝数达到 {}，成为明星博主", followId, fansCount);
            }
        }
        if (fansCount < STAR_FAN_THRESHOLD) {
            if (isStar(followId)) {
                removeStar(followId);
                redisTemplate.delete(ACTIVE_FAN_ZSET_KEY + followId);
                log.info("博主 {} 粉丝数减少到{}，取消明星博主", followId, fansCount);
            }
        }
    }

//    private void batchLuaAddFollow(List<FollowRelation> relations) {
//        if (relations == null || relations.isEmpty()) return;
//
//        int successCount = 0;
//        int failCount = 0;
//
//        for (FollowRelation relation : relations) {
//            try {
//                Long result = luaAddFollow(relation.getFollowId(), relation.getFollowerId());
//                if (result != null && result != -1) {
//                    successCount++;
//                } else {
//                    failCount++;
//                }
//            } catch (Exception e) {
//                failCount++;
//                log.debug("单条关注失败: {}->{}", relation.getFollowId(), relation.getFollowerId());
//            }
//        }
//
//        log.info("批量关注完成，总数={}, 成功={}, 失败={}", relations.size(), successCount, failCount);
//    }

    private void batchLuaCancelFollow(List<FollowRelation> relations) {
        if (relations == null || relations.isEmpty()) return;

        int batchSize = 100;
        // 记录哪些博主的粉丝数可能发生变化（需要检查是否降级）
        Set<Long> needCheckFollowIds = new HashSet<>();

        for (int i = 0; i < relations.size(); i += batchSize) {
            int end = Math.min(i + batchSize, relations.size());
            List<FollowRelation> batch = relations.subList(i, end);

            // 使用管道批量执行原生命令
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (FollowRelation relation : batch) {
                    Long followId = relation.getFollowId();
                    Long followerId = relation.getFollowerId();

                    String followKey = FOLLOW_SET_KEY + followId;
                    String followerKey = FOLLOWER_SET_KEY + followerId;
                    String followerIdStr = String.valueOf(followerId);
                    String followIdStr = String.valueOf(followId);

                    // 取消关注：从粉丝集合中移除
                    connection.sRem(followKey.getBytes(), followerIdStr.getBytes());
                    // 从用户的关注集合中移除
                    connection.sRem(followerKey.getBytes(), followIdStr.getBytes());
                    // 粉丝数减1
                    connection.zIncrBy(FOLLOW_RANK_ZSET_KEY.getBytes(), -1, followIdStr.getBytes());

                    // 记录需要检查的博主ID（粉丝数变化了，可能低于阈值）
                    needCheckFollowIds.add(followId);
                }
                return null;
            });
        }
        // 批量检查并更新明星标识（可能降级）
        for (Long followId : needCheckFollowIds) {
            checkAndSetStar(followId);  // 或者用 checkAndDowngradeStar
        }

        log.info("批量取消关注完成，总数={}", relations.size());
    }

    private static final String FOLLOW_SET_KEY = "follow:";       // 博主的粉丝列表
    private static final String FOLLOWER_SET_KEY = "follower:"; //  我的关注列表
    private static final String FOLLOW_RANK_ZSET_KEY = "follow:rank";  // 全局排行榜Key
    private static final String ACTIVE_FAN_ZSET_KEY = "active:fan:followId:";
    private static final String STAR_FOLLOW_KEY = "star:follow:";
    private static final int BATCH_SIZE = 1000;
    private static final String ATOMIC_ADD_FOLLOW_SCRIPT_STR =
            // KEYS[1] = followKey (e.g., "follow:1001")
            // KEYS[2] = followerKey (e.g., "follower:1002")
            // KEYS[3] = countKey (e.g., "follow:rank")
            // ARGV[1] = followId
            // ARGV[2] = followerId
            "local isFollow = redis.call('sismember', KEYS[1], ARGV[2]) " +
                    "if isFollow == 1 then " +
                    "    return -1 " +
                    "end " +
                    "redis.call('sadd', KEYS[1], ARGV[2]) " +
                    "redis.call('sadd', KEYS[2], ARGV[1]) " +
                    "local newCount = redis.call('zincrby', KEYS[3], 1, ARGV[1]) " +
                    "newCount = tonumber(newCount) " +
                    "return math.floor(newCount)";

    private static final RedisScript<Long> ATOMIC_ADD_FOLLOW_SCRIPT =
            RedisScript.of(ATOMIC_ADD_FOLLOW_SCRIPT_STR, Long.class);

    private static final String ATOMIC_CANCEL_FOLLOW_SCRIPT_STR =
            // KEYS[1] = followKey (e.g., "follow:1001")
            // KEYS[2] = followerKey (e.g., "follower:1002")
            // KEYS[3] = countKey (e.g., "follow:rank")
            // ARGV[1] = followId
            // ARGV[2] = followerId
            "local isFollow = redis.call('sismember', KEYS[1], ARGV[2]) " +
                    "if isFollow == 0 then " +
                    "    return -1 " +
                    "end " +
                    "redis.call('srem', KEYS[1], ARGV[2]) " +
                    "redis.call('srem', KEYS[2], ARGV[1]) " +
                    "local newCount = redis.call('zincrby', KEYS[3], -1, ARGV[1]) " +
                    "newCount = tonumber(newCount) " +
                    "if newCount < 0 then " +
                    "    redis.call('zadd', KEYS[3], 0, ARGV[1]) " +
                    "    newCount = 0 " +
                    "end " +
                    "return math.floor(newCount)";

    // TODO 这里可以改为int
    private static final RedisScript<Long> ATOMIC_CANCEL_FOLLOW_SCRIPT =
            RedisScript.of(ATOMIC_CANCEL_FOLLOW_SCRIPT_STR, Long.class);

    public Long luaAddFollow(Long followId, Long followerId) {
        String followKey = FOLLOW_SET_KEY + followId;
        String followerKey = FOLLOWER_SET_KEY + followerId;
        try {
            // 执行脚本
            Long result = redisTemplate.execute(ATOMIC_ADD_FOLLOW_SCRIPT,
                    Arrays.asList(followKey, followerKey, FOLLOW_RANK_ZSET_KEY),
                    followId.toString(),  // ARGV[1]
                    followerId.toString()   // ARGV[2]
            );
            if (result != -1)
                log.info("Redis关注成功: followId={}, followerId={}",
                        followId, followerId);
            return result;
        } catch (Exception e) {
            log.info("Redis关注失败: followId={}, followerId={}",
                    followId, followerId);
            throw new RuntimeException("点赞操作失败", e);
        }
    }


    public Long luaCancelFollow(Long followId, Long followerId) {
        String followKey = FOLLOW_SET_KEY + followId;
        String followerKey = FOLLOWER_SET_KEY + followerId;
        try {
            // 执行脚本
            Long result = redisTemplate.execute(ATOMIC_CANCEL_FOLLOW_SCRIPT,
                    Arrays.asList(followKey, followerKey, FOLLOW_RANK_ZSET_KEY),
                    followId.toString(),  // ARGV[1]
                    followerId.toString()   // ARGV[2]
            );
            if (result != -1)
                log.info("Redis取消关注成功: followId={}, followerId={}",
                        followId, followerId);
            return result;
        } catch (Exception e) {
            log.info("Redis取消关注失败: followId={}, followerId={}",
                    followId, followerId);
            throw new RuntimeException("点赞操作失败", e);
        }
    }

    /**
     * 获取博主粉丝人数
     *
     * @param followId
     * @return
     */
    public int getFansCount(Long followId) {
        ThrowUtils.throwIf(followId == null || followId < 0, ErrorCode.PARAMS_ERROR);
        Double count = redisTemplate.opsForZSet().score(FOLLOW_RANK_ZSET_KEY, followId.toString());
        if (count == null)
            return -1;
        return count.intValue();
    }

    public Boolean redisBuildCache(Long followId, int count) {
        ThrowUtils.throwIf(followId == null || followId < 0, ErrorCode.PARAMS_ERROR);
        return redisTemplate.opsForZSet().add(FOLLOW_RANK_ZSET_KEY, followId.toString(), count);
    }

    // 获取粉丝ID列表
    public Set<Long> getFansIds(Long followId) {
        Set<String> fansStrIds = redisTemplate.opsForSet().members(followId.toString());
        if (fansStrIds == null)
            return Collections.emptySet();
        return fansStrIds.stream().map(Long::parseLong).collect(Collectors.toSet());
    }

    // TODO 这里面是不是不再需要异步，外面加上比较好
    // 使用通道进行重构缓存,独属于小V重构的，因为其不区分是否活跃


    public void rebuildFansIdCache(Long followId, Set<Long> fansSet) {
        ThrowUtils.throwIf(followId == null || followId < 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(fansSet == null || fansSet.isEmpty(), ErrorCode.PARAMS_ERROR);

        // 同步：设置博主粉丝集合
        String followKey = FOLLOW_SET_KEY + followId;
        redisTemplate.delete(followKey);
        redisTemplate.opsForSet().add(followKey,
                fansSet.stream().map(String::valueOf).distinct().toArray(String[]::new));

        // 异步分批：更新粉丝关注列表
        List<Long> fansList = new ArrayList<>(fansSet);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < fansList.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, fansList.size());
            List<Long> batch = fansList.subList(i, end);

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                updateFollowerFollowListBatch(followId, batch);
            }, redisExecutor);
            futures.add(future);
        }

        // 等待所有批次完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .exceptionally(throwable -> {
                    log.error("批量更新粉丝关注列表失败: followId={}", followId, throwable);
                    return null;
                });
    }

    private void updateFollowerFollowListBatch(Long followId, List<Long> batch) {
        byte[] followIdBytes = followId.toString().getBytes(StandardCharsets.UTF_8);

        redisTemplate.executePipelined((RedisCallback<Void>) connection -> {
            for (Long fansId : batch) {
                byte[] followerKey = (FOLLOWER_SET_KEY + fansId).getBytes(StandardCharsets.UTF_8);
                connection.sAdd(followerKey, followIdBytes);
            }
            return null;
        });
    }

    // 获取活跃粉丝Id列表
    public Set<Long> getActiveFanIds(Long followId) {
        ThrowUtils.throwIf(followId == null || followId < 0, ErrorCode.PARAMS_ERROR);
        Set<String> activeStrIds = redisTemplate.opsForZSet().range(ACTIVE_FAN_ZSET_KEY + followId, 0, 99);
        if (activeStrIds == null)
            return Collections.emptySet();
        return activeStrIds.stream().map(Long::parseLong).collect(Collectors.toSet());
    }


    // 用户登录后更新时间，判断是否算活跃粉丝
    public void updateActiveFans(Long userId, Long time) {
        ThrowUtils.throwIf(userId == null || userId < 0, ErrorCode.PARAMS_ERROR);
        Set<String> followStrIds = redisTemplate.opsForSet().members(FOLLOWER_SET_KEY + userId);
        if (followStrIds == null)
            log.info("用户未关注任何人");
        else {
            Set<Long> followIds = followStrIds.stream().map(Long::parseLong).collect(Collectors.toSet());
            byte[] followerIdByte = userId.toString().getBytes(StandardCharsets.UTF_8);
            redisTemplate.executePipelined((RedisCallback<Void>) connection -> {
                for (Long followId : followIds) {
                    byte[] followIdByte = (ACTIVE_FAN_ZSET_KEY + followId.toString()).getBytes(StandardCharsets.UTF_8);
                    // 先判断是不是中大V，否则这里不判断
                    if (getFansCount(followId) > STAR_FAN_THRESHOLD) {
                        connection.zAdd(followIdByte, time, followerIdByte);
                        long expireTime = System.currentTimeMillis() - 7 * 24 * 3600 * 1000L;
                        connection.zRemRangeByScore(followIdByte, 0, expireTime);
                    }
                }
                return null;
            });
        }
    }

    // 重构活跃粉丝
    public Set<Long> rebuildActiveFans(Long followId, List<Map<String, Object>> activeMap) {
        // 先清空之前的
        redisTemplate.delete(ACTIVE_FAN_ZSET_KEY + followId);
        ThrowUtils.throwIf(followId == null || followId < 0, ErrorCode.PARAMS_ERROR);
        // 先转换为Map结构，{(1001,12431413),(1002,1231412341),(1003,132142131)}
        Map<Long, Long> idAndTimeMap = new HashMap<>(Map.of());
        Set<Long> activeIds = new HashSet<>(Set.of());

        for (Map<String, Object> rowMap : activeMap) {
            Long followerId = ((Number) rowMap.get("id")).longValue();

            // 安全地从Map中获取并转换时间戳
            // 因为SQL里已经 * 1000，这里拿到的是Long或Integer，用Number接收最安全
            Long loginTimeMillis = ((Number) rowMap.get("loginTime")).longValue();
            // 4. 存入集合
            idAndTimeMap.put(followerId, loginTimeMillis);
            activeIds.add(followerId);
        }

        // 重构
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            byte[] followIdByte = (ACTIVE_FAN_ZSET_KEY + followId).getBytes(StandardCharsets.UTF_8);
            for (Map.Entry<Long, Long> longLongEntry : idAndTimeMap.entrySet()) {
                Long followerId = longLongEntry.getKey();
                byte[] followerIdByte = followerId.toString().getBytes(StandardCharsets.UTF_8);
                double time = longLongEntry.getValue().doubleValue();

                connection.zAdd(followIdByte, time, followerIdByte);

                //删除所有 score ≤ expireTime 的元素
                long expireTime = System.currentTimeMillis() - 7 * 24 * 3600 * 1000L;
                connection.zRemRangeByScore(followIdByte, 0, expireTime);
            }
            return null;
        });
        return activeIds;
    }

    public void setStar(Long followId) {
        redisTemplate.opsForValue().set(STAR_FOLLOW_KEY + followId.toString(), "1");
    }

    public void removeStar(Long followId) {
        redisTemplate.delete(STAR_FOLLOW_KEY + followId.toString());
    }

    public boolean isStar(Long followId) {
        return redisTemplate.hasKey(STAR_FOLLOW_KEY + followId.toString());
    }

    public Set<Long> getFollowIds(Long followerId) {
        Set<String> followStr = redisTemplate.opsForSet().members(FOLLOWER_SET_KEY + followerId);
        if (followStr == null)
            return Collections.emptySet();
        return followStr.stream().map(Long::parseLong).collect(Collectors.toSet());
    }

    public Set<Long> rebuildFollowId(Long followerId) {
        //
        String lockKey = "lock:rebuild+followerId:" + followerId;
        Set<Long> followIds = new HashSet<>();
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (lock.tryLock()) {
                try {
                    // 二次检查是否有关注的博主
                    followIds = this.getFollowIds(followerId);
                    if (followIds.isEmpty()) {
                        // 重构缓存
                        followIds = userFollowMapper.getFollowIds(followerId);
                        if (followIds != null) {
                            String[] followIdStr = followIds.stream().map(String::valueOf).toArray(String[]::new);
                            redisTemplate.opsForSet().add(FOLLOWER_SET_KEY + followerId, followIdStr);
                        }
                        log.info("用户未关注任何博主");
                    }
                } finally {
                    // 锁是我的才能解锁
                    if (lock.isHeldByCurrentThread())
                        lock.unlock();
                }
            } else {
                log.info("未获取锁，等待其他线程处理");
            }
        } catch (Exception e) {
            log.info("创建锁失败，失败原因:{}", e);
        }
        return followIds;
    }

    public boolean isFollowed(Long followId, Long followerId) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(FOLLOW_SET_KEY + followId, followerId.toString()));
    }
}
