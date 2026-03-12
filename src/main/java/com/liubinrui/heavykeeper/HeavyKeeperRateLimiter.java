package com.liubinrui.heavykeeper;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 大厂级 HeavyKeeper 实现：高频点赞/取消点赞检测
 * 核心特性：线程安全、动态阈值、时间衰减、单日兜底、低误判率
 */
public class HeavyKeeperRateLimiter {
    // ========== 大厂标准配置 ==========
    private static final int HASH_FUNCTION_NUM = 4;       // 哈希函数数量（4个=精度+性能平衡）
    private static final double DECAY_FACTOR = 0.8;        // 衰减因子（每分钟衰减20%）
    private static final long TIME_WINDOW_SEC = 5 * 60;    // 基础时间窗口：5分钟
    private static final long DAY_WINDOW_SEC = 24 * 60 * 60;// 单日时间窗口：24小时
    private static final int DAY_LIMIT = 10;               // 单日兜底上限

    // ========== 核心存储 ==========
    private final Map<Integer, Integer>[] hashBuckets;     // 多哈希桶（ConcurrentHashMap保证线程安全）
    private final Map<String, TimeCount> dayCountMap;      // 单日计数（key: userId_blogId）
    private final Map<Integer, Map<Integer, Long>> lastDecayTimeMap; // 哈希桶索引 -> 哈希值 -> 上次衰减时间
    private final Map<Integer, Map<Integer, Long>> firstCountTimeMap; // 哈希桶索引 -> 哈希值 -> 首次计数时间
    private volatile int baseThreshold;                    // 基础阈值（支持动态调整）
    private final Random random = new Random();            // 随机数（哈希辅助）
    private final ScheduledExecutorService scheduler;      // 定时清理线程

    // 时间计数封装（线程安全）
    private static class TimeCount {
        private volatile long lastOperateTime; // 最后操作时间（秒）
        private volatile int count;            // 操作计数

        public TimeCount(long lastOperateTime, int count) {
            this.lastOperateTime = lastOperateTime;
            this.count = count;
        }

        // 线程安全的计数更新
        public synchronized void increment() {
            this.count++;
            this.lastOperateTime = System.currentTimeMillis() / 1000;
        }

        // 线程安全的重置
        public synchronized void reset() {
            this.count = 0;
            this.lastOperateTime = System.currentTimeMillis() / 1000;
        }
    }

    /**
     * 构造函数（初始化配置）
     * @param baseThreshold 基础阈值（5分钟内操作次数上限）
     */
    public HeavyKeeperRateLimiter(int baseThreshold) {
        if (baseThreshold < 1) {
            throw new IllegalArgumentException("阈值不能小于1");
        }
        this.baseThreshold = baseThreshold;

        // 初始化哈希桶
        this.hashBuckets = new ConcurrentHashMap[HASH_FUNCTION_NUM];
        this.lastDecayTimeMap = new ConcurrentHashMap<>();
        this.firstCountTimeMap = new ConcurrentHashMap<>();
        for (int i = 0; i < HASH_FUNCTION_NUM; i++) {
            hashBuckets[i] = new ConcurrentHashMap<>();
            lastDecayTimeMap.put(i, new ConcurrentHashMap<>());
            firstCountTimeMap.put(i, new ConcurrentHashMap<>());
        }

        // 初始化单日计数Map
        this.dayCountMap = new ConcurrentHashMap<>();

        // 初始化定时清理任务
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.scheduler.scheduleAtFixedRate(this::cleanExpiredData, 1, 1, TimeUnit.HOURS);
    }

    /**
     * 核心方法：检测操作是否超限
     * @param key 唯一标识（格式：userId_blogId）
     * @return true=超限（拦截），false=正常（放行）
     */
    public boolean isOverLimit(String key) {
        if (key == null || !key.contains("_")) {
            throw new IllegalArgumentException("key格式必须为userId_blogId");
        }
        long nowSec = System.currentTimeMillis() / 1000;

        // ========== 第一步：单日兜底检测（优先级最高） ==========
        TimeCount dayCount = dayCountMap.get(key);
        if (dayCount != null) {
            // 超过24小时，重置单日计数
            if (nowSec - dayCount.lastOperateTime > DAY_WINDOW_SEC) {
                dayCount.reset();
            }
            // 单日超过10次，直接拦截
            else if (dayCount.count >= DAY_LIMIT) {
                return true;
            }
        } else {
            // 首次操作，初始化单日计数
            dayCount = new TimeCount(nowSec, 0);
            dayCountMap.put(key, dayCount);
        }

        // ========== 第二步：HeavyKeeper 核心计数检测 ==========
        int minCount = Integer.MAX_VALUE; // 取多哈希桶最小计数（降低误判）
        for (int i = 0; i < HASH_FUNCTION_NUM; i++) {
            int hash = getHash(key, i);
            Map<Integer, Long> bucketFirstTimeMap = firstCountTimeMap.get(i);
            Map<Integer, Long> bucketDecayTimeMap = lastDecayTimeMap.get(i);

            // 1. 获取当前计数（默认0）
            int currentCount = hashBuckets[i].getOrDefault(hash, 0);

            // 2. 校验时间窗口：超过5分钟则重置计数
            long firstTimeSec = bucketFirstTimeMap.getOrDefault(hash, nowSec);
            if (nowSec - firstTimeSec > TIME_WINDOW_SEC) {
                currentCount = 0;
                bucketFirstTimeMap.put(hash, nowSec); // 重置首次时间
            }

            // 3. 衰减逻辑：每分钟仅衰减一次
            long lastDecaySec = bucketDecayTimeMap.getOrDefault(hash, 0L);
            if (nowSec - lastDecaySec >= 60) {
                currentCount = (int) Math.max(currentCount * DECAY_FACTOR, 0);
                hashBuckets[i].put(hash, currentCount);
                bucketDecayTimeMap.put(hash, nowSec); // 更新衰减时间
            }

            // 4. 计数+1
            currentCount += 1;
            hashBuckets[i].put(hash, currentCount);

            // 5. 记录最小计数
            minCount = Math.min(minCount, currentCount);
        }

        // ========== 第三步：更新单日计数 ==========
        dayCount.increment();

        // ========== 第四步：判断是否超过基础阈值 ==========
        return minCount > baseThreshold;
    }

    /**
     * 重置指定key的计数（取消点赞后调用）
     */
    public void reset(String key) {
        if (key == null || !key.contains("_")) {
            throw new IllegalArgumentException("key格式必须为userId_blogId");
        }
        // 重置哈希桶计数+衰减时间+首次计数时间
        for (int i = 0; i < HASH_FUNCTION_NUM; i++) {
            int hash = getHash(key, i);
            hashBuckets[i].remove(hash);
            lastDecayTimeMap.get(i).remove(hash);
            firstCountTimeMap.get(i).remove(hash);
        }
        // 重置单日计数
        TimeCount dayCount = dayCountMap.get(key);
        if (dayCount != null) {
            dayCount.reset();
        }
    }

    /**
     * 动态调整基础阈值（支持运营后台配置）
     */
    public void updateThreshold(int newThreshold) {
        if (newThreshold < 1) {
            throw new IllegalArgumentException("阈值不能小于1");
        }
        this.baseThreshold = newThreshold;
    }

    /**
     * 获取当前基础阈值（监控/调试用）
     */
    public int getBaseThreshold() {
        return this.baseThreshold;
    }

    /**
     * 多哈希函数实现（MurmurHash思想，降低冲突率）
     */
    private int getHash(String key, int index) {
        byte[] keyBytes = key.getBytes();
        int seed = index * 0x9747b28c; // 不同索引用不同种子
        int hash = 0x811c9dc5;
        for (byte b : keyBytes) {
            hash ^= b;
            hash *= 0x1000193;
        }
        hash += seed;
        return hash & Integer.MAX_VALUE; // 避免负数
    }

    /**
     * 清理过期数据（避免内存泄漏）
     */
    private void cleanExpiredData() {
        long nowSec = System.currentTimeMillis() / 1000;

        // 1. 清理dayCountMap中超过24小时的记录
        dayCountMap.entrySet().removeIf(entry -> {
            TimeCount count = entry.getValue();
            return nowSec - count.lastOperateTime > DAY_WINDOW_SEC;
        });

        // 2. 清理哈希桶/衰减时间/首次计数时间中超过5分钟的记录
        for (int i = 0; i < HASH_FUNCTION_NUM; i++) {
            Map<Integer, Integer> bucket = hashBuckets[i];
            Map<Integer, Long> decayTimeMap = lastDecayTimeMap.get(i);
            Map<Integer, Long> firstTimeMap = firstCountTimeMap.get(i);

            bucket.entrySet().removeIf(entry -> {
                int hash = entry.getKey();
                long firstTime = firstTimeMap.getOrDefault(hash, 0L);
                return nowSec - firstTime > TIME_WINDOW_SEC;
            });

            // 同步清理衰减时间和首次时间
            decayTimeMap.keySet().retainAll(bucket.keySet());
            firstTimeMap.keySet().retainAll(bucket.keySet());
        }
    }

    /**
     * 关闭定时任务（销毁时调用）
     */
    public void shutdown() {
        scheduler.shutdown();
    }
}
