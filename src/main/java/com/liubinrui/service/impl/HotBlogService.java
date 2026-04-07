package com.liubinrui.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.liubinrui.common.ErrorCode;
import com.liubinrui.enums.BlogActionEnum;
import com.liubinrui.exception.BusinessException;
import com.liubinrui.mapper.BlogMapper;
import com.liubinrui.model.entity.Blog;
import com.liubinrui.redis.RedisHotBlogService;
import com.liubinrui.redis.RedisNullCache;
import com.liubinrui.utils.BloomFilterService;
import com.liubinrui.utils.IpUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class HotBlogService {
    @Autowired
    private BlogMapper blogMapper;
    @Resource
    private BloomFilterService bloomFilterService;
    @Autowired
    private RedisNullCache redisNullCache;
    @Autowired
    private RedisHotBlogService redisHotBlogService;
    @Autowired
    private BlogQueryMerger blogQueryMerger;

    @Resource
    @Qualifier("dbExecutor")
    private Executor dbExecutor;

    @Resource
    @Qualifier("redisExecutor")
    private Executor redisExecutor;

    // ========== 基础配置 ==========
    private static final int HOT_TOP_N = 100;
    private static final int HOT_SCORE = 10;

    // ========== 缓存配置 ==========
    private static final long CACHE_EXPIRE_SEC = 5 * 60;
    private static final String HOT_BLOG_LIST_KEY = "HOT_BLOG_TOP_" + HOT_TOP_N;

    // ========== 布隆过滤器配置 ==========
    private static final String BLOOM_FILTER_NAME = "BlogIdFilter";
    private static final long BLOOM_FILTER_EXPECTED_INSERTIONS = 1000000;
    private static final double BLOOM_FILTER_FPP = 0.01;

    // ========== 防刷配置 ==========
    private static final int MAX_LIKES_PER_MINUTE = 10;
    private static final int MAX_IP_REQUESTS_PER_MINUTE = 20;

    // 本地存储
    private final AtomicBoolean initCompleted = new AtomicBoolean(false);

    // Redisson 布隆过滤器实例
    private RBloomFilter<Long> bloomFilter;

    // ========== 用户防刷 ==========
    private final Cache<String, AtomicInteger> userActionLimiter = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .maximumSize(10000)
            .build();

    // ========== IP限流 ==========
    private final Cache<String, AtomicInteger> ipRateLimiter = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .maximumSize(1000)
            .build();

    // ========== 本地存储 ==========
    private final Map<Long, Integer> blogHotScoreMap = new ConcurrentHashMap<>();
    private final Map<Long, Integer> normalScoreMap = new ConcurrentHashMap<>();
    // 存储热门博客ID列表
    private final Cache<String, List<Long>> hotBlogListCache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_EXPIRE_SEC, TimeUnit.SECONDS)
            .maximumSize(10)
            .build();

    // 存储博客ID与博客
    private final Cache<Long, Blog> hotBlogCache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_EXPIRE_SEC, TimeUnit.SECONDS)
            .maximumSize(HOT_TOP_N)
            .recordStats()
            .build();

    // 专门用来存新发布的、热度低的博客
    private final Cache<Long, Blog> normalBlogCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .maximumSize(10_000)
            .build();

    // 定时任务定义
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "hot-blog-scheduler");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    public void init() {
        log.info("开始初始化 HotBlogService...");
        long startTime = System.currentTimeMillis();
        try {
            // 1. 初始化布隆过滤器（必须先初始化）
            initBloomFilter();
            // 2. 加载数据到内存
            loadAndInitData();
            // 3. 启动定时任务
            startScheduledTasks();

            initCompleted.set(true);
            log.info("HotBlogService 初始化完成，总耗时: {}ms", System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.error("HotBlogService 初始化失败", e);
            throw new RuntimeException("HotBlogService 初始化失败", e);
        }
    }

    /**
     * 初始化 Redisson 布隆过滤器
     */
    private void initBloomFilter() {
        long startTime = System.currentTimeMillis();
        try {
            // 获取布隆过滤器
            bloomFilter = bloomFilterService.getFilter(BLOOM_FILTER_NAME,
                    BLOOM_FILTER_EXPECTED_INSERTIONS, BLOOM_FILTER_FPP);

            // 检查是否需要加载数据到布隆过滤器
            // 判断布隆过滤器是否为空（通过检查是否包含一个不可能存在的ID）
            if (!bloomFilter.contains(-1L)) {
                log.info("布隆过滤器为空，开始加载数据...");
                loadBlogIdsToBloomFilter();
            } else {
                log.info("布隆过滤器已有数据，跳过加载");
            }

            log.info("布隆过滤器初始化完成，耗时 {}ms", System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.error("布隆过滤器初始化失败", e);
            throw new RuntimeException("布隆过滤器初始化失败", e);
        }
    }

    /**
     * 从数据库加载所有博客ID到布隆过滤器
     */
    private void loadBlogIdsToBloomFilter() {
        long startTime = System.currentTimeMillis();
        try {
            int batchSize = 1000;
            Long lastId = 0L;
            int totalLoaded = 0;
            int batchCount = 0;

            while (true) {
                List<Long> blogIds = blogMapper.selectIdByCursor(lastId, batchSize);
                if (blogIds == null || blogIds.isEmpty()) {
                    break;
                }

                // 更新 lastId
                lastId = blogIds.get(blogIds.size() - 1);

                // 批量添加到布隆过滤器
                for (Long blogId : blogIds) {
                    bloomFilter.add(blogId);
                }

                totalLoaded += blogIds.size();
                batchCount++;

                if (batchCount % 10 == 0) {
                    log.info("布隆过滤器加载进度: 已加载 {} 条数据", totalLoaded);
                }
            }

            log.info("布隆过滤器数据加载完成共加载{}条博客ID，耗时{}ms",
                    totalLoaded, System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.error("加载博客ID到布隆过滤器失败", e);
            throw new RuntimeException("加载博客ID到布隆过滤器失败", e);
        }
    }

    /**
     * 初始化内存和Redis
     */
    private void loadAndInitData() {
        log.info("开始同步数据到内存");
        long startTime = System.currentTimeMillis();
        int batchSize = 1000;
        Long lastId = 0L;
        List<Blog> allBlogList = new ArrayList<>();

        blogHotScoreMap.clear();
        // 清空 Caffeine Cache (invalidateAll 会立即释放内存)
        hotBlogCache.invalidateAll();
        normalBlogCache.invalidateAll();
        // 清空redis的数据
        redisHotBlogService.clearAllHotScore();

        log.info("已清空原有内存的数据");
        try {
            // 使用游标查询所有博客（不只是热门的）
            while (true) {
                List<Blog> blogList = blogMapper.selectByCursor(lastId, batchSize);
                if (blogList == null || blogList.isEmpty()) {
                    break;
                }

                lastId = blogList.get(blogList.size() - 1).getId();
                allBlogList.addAll(blogList);
                for (Blog blog : blogList) {
                    Integer hotScore = blog.getHotScore();
                    if (hotScore != null && hotScore > HOT_SCORE) {
                        blogHotScoreMap.put(blog.getId(), hotScore);
                        hotBlogCache.put(blog.getId(), blog);
                    } else {
                        normalBlogCache.put(blog.getId(), blog);
                    }
                }
            }
            // 计算并缓存TopN
            sortAndCacheTopN();
            log.info("数据加载完成，共加载{}条博客，热门博客大小:{}，耗时{}ms",
                    allBlogList.size(), blogHotScoreMap.size(), System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.error("加载数据失败", e);
            throw new RuntimeException("加载数据失败", e);
        }
    }

    private void startScheduledTasks() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (!initCompleted.get()) {
                    log.info("服务尚未初始化完成，跳过本次定时任务");
                    return;
                }
                long startTime = System.currentTimeMillis();
                // 先执行衰减，再执行排序和同步
                decayHotScore();
                sortAndCacheTopN();
                syncToRedisAndDB();
                // 可以通过 cron 表达式实现，这里简化处理，定时更新布隆过滤器
                if (shouldRebuildBloomFilter()) {
                    rebuildBloomFilter();
                }
                log.info("定时热门博客任务执行完成(衰减、排序和同步），总耗时{}ms", System.currentTimeMillis() - startTime);
            } catch (Exception e) {
                log.error("定时任务调度失败", e);
            }
        }, 0, 60, TimeUnit.SECONDS);
    }

    private boolean shouldRebuildBloomFilter() {
        // 判断是否需要重建，例如每天凌晨3点
        Calendar calendar = Calendar.getInstance();
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        return hour == 3 && calendar.get(Calendar.MINUTE) < 5;
    }

    /**
     * 同步到 Redis 和 DB
     */
    private void syncToRedisAndDB() {
        long startTime = System.currentTimeMillis();
        // 创建快照，避免同步过程中数据变化
        Map<Long, Integer> hotSnap = new HashMap<>(blogHotScoreMap);
        Map<Long, Blog> blogMap = new HashMap<>(hotBlogCache.asMap());
        Map<Long, Blog> blogNormalMap = new HashMap<>(normalBlogCache.asMap());
        try {
            CompletableFuture<Void> redisSync = CompletableFuture.runAsync(() ->
                    syncToRedis(hotSnap), redisExecutor);
            CompletableFuture<Void> redisHot = CompletableFuture.runAsync(() ->
                    syncToRedisHot(blogMap), redisExecutor);
            CompletableFuture<Void> redisNormal = CompletableFuture.runAsync(() ->
                    syncToRedisNormal(blogNormalMap), redisExecutor);
            CompletableFuture<Void> dbSync = CompletableFuture.runAsync(() ->
                    syncToDB(hotSnap), dbExecutor);

            CompletableFuture.allOf(redisSync, redisHot, redisNormal, dbSync)
                    .orTimeout(10, TimeUnit.SECONDS)
                    .join();
            log.info("热度值异步同步到Redis和DB执行完成，总耗时 {}ms", System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.error("数据同步失败", e);
        }
    }

    private void syncToRedis(Map<Long, Integer> snapshot) {
        if (snapshot.isEmpty()) return;
        long startTime = System.currentTimeMillis();
        try {
            redisHotBlogService.loadHotScoreToRedis(snapshot);
            log.info("热度值更新后同步到Redis完成，耗时 {}ms", System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.error("同步到Redis失败", e);
        }
    }

    private void syncToRedisNormal(Map<Long, Blog> blogNormalMap) {
        if (blogNormalMap.isEmpty()) return;
        long startTime = System.currentTimeMillis();
        try {
            redisHotBlogService.loadNormalBlogToRedis(blogNormalMap);
            log.info("普通博客同步到Redis完成，耗时 {}ms", System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.error("同步到Redis失败", e);
        }
    }

    private void syncToRedisHot(Map<Long, Blog> blogMap) {
        if (blogMap.isEmpty()) return;
        long startTime = System.currentTimeMillis();
        try {
            redisHotBlogService.loadHotBlogToRedis(blogMap);
            log.info("热门博客同步到Redis完成，耗时 {}ms", System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.error("同步到Redis失败", e);
        }
    }

    // TODO 还得普通的更新,但是普通的没衰减，应该不需要吧，这里简化了
    private void syncToDB(Map<Long, Integer> snapshot) {
        if (snapshot.isEmpty()) return;
        long startTime = System.currentTimeMillis();
        List<Blog> hotScoreList = new ArrayList<>();
        try {
            for (Map.Entry<Long, Integer> entry : snapshot.entrySet()) {
                Blog blog = new Blog();
                blog.setId(entry.getKey());
                blog.setHotScore(entry.getValue());
                hotScoreList.add(blog);
            }
            // 分批更新
            int batchSize = 500;
            for (int i = 0; i < hotScoreList.size(); i += batchSize) {
                int end = Math.min(i + batchSize, hotScoreList.size());
                List<Blog> batch = hotScoreList.subList(i, end);
                blogMapper.batchUpdateHotScore(batch);
            }
            log.info("热度值更新后同步到DB完成，共{}条，耗时{}ms",
                    hotScoreList.size(), System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.error("同步到DB失败", e);
        }
    }

    /**
     * 排序并缓存 Top N
     */
    private void sortAndCacheTopN() {
        if (blogHotScoreMap.isEmpty()) {
            hotBlogListCache.put(HOT_BLOG_LIST_KEY, new ArrayList<>());
            return;
        }
        long startTime = System.currentTimeMillis();
        try {
            // 使用 PriorityQueue 获取 Top N
            PriorityQueue<Map.Entry<Long, Integer>> minHeap = new PriorityQueue<>(
                    HOT_TOP_N + 1,
                    Comparator.comparingInt(Map.Entry::getValue)
            );

            for (Map.Entry<Long, Integer> entry : blogHotScoreMap.entrySet()) {
                minHeap.offer(entry);
                if (minHeap.size() > HOT_TOP_N) {
                    minHeap.poll();
                }
            }

            List<Long> hotIdList = new ArrayList<>(HOT_TOP_N);
            List<Map.Entry<Long, Integer>> list = new ArrayList<>(minHeap);
            list.sort((e1, e2) -> Integer.compare(e2.getValue(), e1.getValue()));
            for (Map.Entry<Long, Integer> entry : list) {
                hotIdList.add(entry.getKey());
            }

            hotBlogListCache.put(HOT_BLOG_LIST_KEY, hotIdList);
            log.info("排序完成，TopN 数量: {}，耗时 {}ms",
                    hotIdList.size(), System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.error("排序失败", e);
        }
    }

    /**
     * 热度衰减 - 使用迭代器安全删除
     */
    private void decayHotScore() {
        if (blogHotScoreMap.isEmpty()) {
            return;
        }
        long startTime = System.currentTimeMillis();
        int decayCount = 0;
        int removeCount = 0;

        try {
            Iterator<Map.Entry<Long, Integer>> iterator = blogHotScoreMap.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Long, Integer> entry = iterator.next();
                Long blogId = entry.getKey();
                Integer hotScore = entry.getValue();

                if (hotScore == null || hotScore <= 0) {
                    iterator.remove();
                    hotBlogCache.invalidate(blogId);
                    removeCount++;
                    continue;
                }

                int newScore = (int) (hotScore * 0.98);
                if (newScore < HOT_SCORE) {
                    iterator.remove();
                    hotBlogCache.invalidate(blogId);
                    removeCount++;
                } else {
                    entry.setValue(newScore);
                    decayCount++;
                }
            }

            if (decayCount > 0 || removeCount > 0) {
                log.info("热度衰减完成，衰减: {} 条，移除: {} 条，耗时 {}ms",
                        decayCount, removeCount, System.currentTimeMillis() - startTime);
            }
        } catch (Exception e) {
            log.error("热度衰减失败", e);
        }
    }

    /**
     * 记录用户行为
     */
    public void recordUserAction(Long userId, Long blogId, BlogActionEnum blogActionEnum) {
        if (userId == null || userId < 0 || blogId == null || blogId < 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数错误");
        }

        int incrCount = blogActionEnum.getWeight();

        // 限流检查
        if (isUserActionLimited(userId, blogId)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "操作过于频繁，请稍后再试");
        }

        // 1. 更新本地内存（同步）
        Integer newHotScore = blogHotScoreMap.compute(blogId,
                (k, v) -> (v == null ? 0 : v) + incrCount);

        // 2. 更新本地缓存（同步）
        Blog blog = blogMapper.selectById(blogId);
        if (blog != null) {
            blog.setHotScore(newHotScore);
            if (newHotScore > HOT_SCORE) {
                blogHotScoreMap.put(blog.getId(), newHotScore);
                hotBlogCache.put(blogId, blog);
                normalBlogCache.invalidate(blogId);
            } else {
                blogHotScoreMap.remove(blog.getId());
                normalBlogCache.put(blogId, blog);
                hotBlogCache.invalidate(blogId);
            }
        }

        // 3. 同步更新 Redis（使用 INCRBY 原子操作）
        try {
            Long result = redisHotBlogService.updateHotScore(blogId, incrCount);
            if (result == null) {
                log.warn("Redis热度更新失败: blogId={}", blogId);
                // 可选：记录到本地日志，定时任务会兜底
            }
        } catch (Exception e) {
            log.error("Redis热度更新异常: blogId={}", blogId, e);
            // 异常不影响主流程，定时任务会兜底
        }
    }

    private boolean isUserActionLimited(Long userId, Long blogId) {
        String key = userId + "_" + blogId;
        AtomicInteger counter = userActionLimiter.get(key, k -> new AtomicInteger(0));
        int count = counter.incrementAndGet();
        return count > MAX_LIKES_PER_MINUTE;
    }

    /**
     * 获取博客
     */
    public Blog getBlog(Long blogId) {
        String clientIp = IpUtils.getClientIp();

        if (blogId == null || blogId <= 0) {
            log.warn("无效blogId: {}", blogId);
            return null;
        }
//        if (isIpRateLimited(clientIp)) {
//            log.warn("IP被限流: {}, blogId: {}", clientIp, blogId);
//            return null;
//        }
        // 使用 Redisson 布隆过滤器
        if (bloomFilter == null || !bloomFilter.contains(blogId)) {
            log.debug("布隆过滤器拦截: blogId={}", blogId);
            return null;
        }
        // 空值缓存检查
        String blogNullKey = redisNullCache.buildBlogNullKey(blogId);
        if (redisNullCache.isNull(blogNullKey)) {
            log.debug("空值缓存命中: blogId={}", blogId);
            return null;
        }

        // 查询缓存
        //Blog blog = getFromCache(blogId);
        Blog blog = getBlogWithMerge(blogId);  // TODO 核心改动
        if (blog != null) {
            return blog;
        }
        // 查询数据库
        blog = blogMapper.selectById(blogId);
        if (blog != null)
            return blog;
        // 数据库不存在，设置空值缓存
        redisNullCache.setNull(blogNullKey);
        return null;
    }

    /**
     * 请求合并核心逻辑
     */
    private Blog getBlogWithMerge(Long blogId) {
        try {
            log.info("博客 {} 走批量合并", blogId);
            // 调用合并器，拿到future
            CompletableFuture<Blog> future = blogQueryMerger.queryBlog(blogId);
            // 等待最多200ms，拿到结果
            return future.get(200, TimeUnit.MILLISECONDS);

        } catch (TimeoutException e) {
            log.warn("请求合并超时，降级为直接查询: blogId={}", blogId);
            return blogMapper.selectById(blogId);
        } catch (Exception e) {
            log.error("请求合并失败，降级为直接查询: blogId={}", blogId, e);
            return blogMapper.selectById(blogId);
        }
    }

    private boolean isIpRateLimited(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        AtomicInteger counter = ipRateLimiter.get(ip, v -> new AtomicInteger(0));
        int count = counter.incrementAndGet();
        return count > MAX_IP_REQUESTS_PER_MINUTE;
    }

    private Blog getFromCache(Long blogId) {
        Blog blog = hotBlogCache.getIfPresent(blogId);
        if (blog != null) {
            return blog;
        }
        return normalBlogCache.getIfPresent(blogId);

    }

    /**
     * 添加博客到布隆过滤器
     */
    public void addToBloomFilter(Long blogId) {
        if (blogId == null || blogId <= 0) {
            return;
        }
        if (bloomFilter != null) {
            bloomFilter.add(blogId);
            log.debug("博客ID已添加到布隆过滤器: blogId={}", blogId);
        }
    }

    /**
     * 获取热门博客列表
     */
    public List<Long> getHotBlogTopN() {
        log.info("开始查询TopN的热门博客");
        List<Long> cacheList = hotBlogListCache.getIfPresent(HOT_BLOG_LIST_KEY);
        if (cacheList != null && !cacheList.isEmpty()) {
            return cacheList;
        }
        // 缓存失效，重新计算
        sortAndCacheTopN();
        cacheList = hotBlogListCache.getIfPresent(HOT_BLOG_LIST_KEY);
        return cacheList != null ? cacheList : new ArrayList<>();
    }

    /**
     * 创建博客时的处理
     * 1. 添加到布隆过滤器
     * 2. 清除可能存在的空值缓存
     * 3. 添加到普通缓存（新博客热度低）
     */
    public void onBlogCreated(Blog blog) {
        if (blog == null || blog.getId() == null) {
            return;
        }

        Long blogId = blog.getId();

        // 1. 添加到布隆过滤器
        addToBloomFilter(blogId);

        // 2. 清除可能存在的空值缓存（如果是恢复已删除的博客）
        String blogNullKey = redisNullCache.buildBlogNullKey(blogId);
        redisNullCache.deleteNull(blogNullKey);

        // 3. 添加到热度Map（初始热度为0或默认值）
        Integer initHotScore = blog.getHotScore() != null ? blog.getHotScore() : 0;

        normalBlogCache.invalidate(blogId);
        log.info("新博客已添加到系统中: blogId={}, hotScore={}", blogId, initHotScore);
    }

    /**
     * 删除博客时的处理
     * 1. 从布隆过滤器中移除（注意：布隆过滤器不支持删除，需要重建或使用计数布隆过滤器）
     * 2. 从缓存中移除
     * 3. 从热度Map中移除
     * 4. 设置空值缓存
     */
    public void onBlogDeleted(Long blogId) {
        if (blogId == null) {
            return;
        }
        // 1. 从缓存中移除
        hotBlogCache.invalidate(blogId);
        normalBlogCache.invalidate(blogId);
        // 2. 从热度Map中移除
        blogHotScoreMap.remove(blogId);
        // 3. 设置空值缓存（防止缓存穿透）
        String blogNullKey = redisNullCache.buildBlogNullKey(blogId);
        redisNullCache.setNull(blogNullKey);
        // 4. 注意：布隆过滤器不支持直接删除
        // 解决方案：
        // - 方案A：使用计数布隆过滤器（Counting Bloom Filter）
        // - 方案B：定期重建布隆过滤器
        // - 方案C：接受一定时间的误判（删除后短时间内可能被布隆过滤器误判为存在）
        // 推荐方案B：在定时任务中定期重建布隆过滤器
        log.info("博客已删除: blogId={}", blogId);
        // 如果使用计数布隆过滤器，可以这样实现：
        // if (countingBloomFilter != null) {
        //     countingBloomFilter.remove(blogId);
        // }
    }

    /**
     * 批量重建布隆过滤器（定时任务调用）
     */
    public void rebuildBloomFilter() {
        log.info("开始重建布隆过滤器...");
        long startTime = System.currentTimeMillis();

        try {
            // 重新获取布隆过滤器（会清空原有数据）
            RBloomFilter<Long> newBloomFilter = bloomFilterService.getFilter(
                    BLOOM_FILTER_NAME,
                    BLOOM_FILTER_EXPECTED_INSERTIONS,
                    BLOOM_FILTER_FPP
            );

            // 清空并重新加载
            // 注意：Redisson 的 RBloomFilter 没有直接的清空方法，需要删除后重建
            // 这里简化处理，实际需要删除原过滤器再创建

            // 加载所有存在的博客ID
            int batchSize = 1000;
            Long lastId = 0L;
            int totalLoaded = 0;

            while (true) {
                List<Long> blogIds = blogMapper.selectIdByCursor(lastId, batchSize);
                if (blogIds == null || blogIds.isEmpty()) {
                    break;
                }

                lastId = blogIds.get(blogIds.size() - 1);
                for (Long blogId : blogIds) {
                    newBloomFilter.add(blogId);
                }

                totalLoaded += blogIds.size();
            }

            // 替换旧的布隆过滤器
            this.bloomFilter = newBloomFilter;

            log.info("布隆过滤器重建完成，共加载 {} 条数据，耗时 {}ms",
                    totalLoaded, System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.error("布隆过滤器重建失败", e);
        }
    }

    @PreDestroy
    public void destroy() {
        log.info("开始关闭热门博客服务...");
        try {
            // 最后同步一次数据
            if (!blogHotScoreMap.isEmpty()) {
                syncToRedis(blogHotScoreMap);
                syncToDB(blogHotScoreMap);
            }
        } catch (Exception e) {
            log.error("最后同步数据失败", e);
        }

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("热门博客服务关闭完成");
    }

//    public List<Blog> batchGetBlog(List<Long> blogIds) {
//        // 1. 批量查热门缓存
//        Map<Long, Blog> hotBlogs = hotBlogCache.getAllPresent(blogIds);
//        // 2. 批量查普通缓存
//        Map<Long, Blog> normalBlogs = normalBlogCache.getAllPresent(blogIds);
//        // 3. 合并结果
//        List<Blog> result = new ArrayList<>();
//        for (Long id : blogIds) {
//            Blog blog = hotBlogs.get(id);
//            if (blog == null) {
//                blog = normalBlogs.get(id);
//            }
//            if (blog != null) {
//                result.add(blog);
//            }
//        }
//        List<Blog> hotBlog;
//        List<Blog> normalBlog;
//        // TODO 从Redis查找，再没有从DB查找并重建缓存
//        if (result == null) {
//            hotBlog = redisHotBlogService.getHotBlog(blogIds);
//            normalBlog = redisHotBlogService.getNormalBlog(blogIds);
//            result.addAll(hotBlog);
//            result.addAll(normalBlog);
//        }
//        return result;
//    }

    public List<Blog> batchGetBlog(List<Long> blogIds) {
        if (blogIds == null || blogIds.isEmpty()) {
            return new ArrayList<>();
        }
        // 1. 批量查热门缓存
        Map<Long, Blog> hotBlogs = hotBlogCache.getAllPresent(blogIds);
        // 2. 批量查普通缓存
        Map<Long, Blog> normalBlogs = normalBlogCache.getAllPresent(blogIds);

        // 3. 收集本地缓存未命中的 ID
        List<Long> missIds = new ArrayList<>();
        List<Blog> result = new ArrayList<>();

        for (Long id : blogIds) {
            Blog blog = hotBlogs.get(id);
            if (blog == null) {
                blog = normalBlogs.get(id);
            }
            if (blog != null) {
                result.add(blog);
            } else {
                missIds.add(id);  // 记录未命中的 ID
            }
        }
        log.info("本地缓存查找到的数量：{}",result.size());
        // 4. 从 Redis 批量查询未命中的数据
        if (!missIds.isEmpty()) {
            List<Blog> redisHotBlogs = redisHotBlogService.getHotBlog(missIds);
            List<Blog> redisNormalBlogs = redisHotBlogService.getNormalBlog(missIds);

            // 合并 Redis 查询结果
            List<Blog> redisResults = new ArrayList<>();
            if (redisHotBlogs != null) redisResults.addAll(redisHotBlogs);
            if (redisNormalBlogs != null) redisResults.addAll(redisNormalBlogs);

            // 将 Redis 结果放入本地缓存
            for (Blog blog : redisResults) {
                if (blog != null) {
                    // 根据热度决定放入哪个缓存
                    Integer hotScore = blog.getHotScore();
                    if (hotScore != null && hotScore > HOT_SCORE) {
                        hotBlogCache.put(blog.getId(), blog);
                    } else {
                        normalBlogCache.put(blog.getId(), blog);
                    }
                    result.add(blog);
                }
            }

            // 5. 如果 Redis 也没有，从 DB 查询并重建缓存
            List<Long> finalMissIds = new ArrayList<>(missIds);
            // 移除 Redis 中查到的 ID
            for (Blog blog : redisResults) {
                if (blog != null) {
                    finalMissIds.remove(blog.getId());
                }
            }

            if (!finalMissIds.isEmpty()) {
                List<Blog> dbBlogs = blogMapper.selectBatchIds(finalMissIds);
                for (Blog blog : dbBlogs) {
                    if (blog != null) {
                        // 重建本地缓存
                        Integer hotScore = blog.getHotScore();
                        if (hotScore != null && hotScore > HOT_SCORE) {
                            hotBlogCache.put(blog.getId(), blog);
                        } else {
                            normalBlogCache.put(blog.getId(), blog);
                        }
                        // 重建 Redis 缓存
                        if (hotScore != null && hotScore > HOT_SCORE) {
                            Map<Long, Blog> hotMap = new HashMap<>();
                            hotMap.put(blog.getId(), blog);
                            redisHotBlogService.loadHotBlogToRedis(hotMap);
                        } else {
                            Map<Long, Blog> normalMap = new HashMap<>();
                            normalMap.put(blog.getId(), blog);
                            redisHotBlogService.loadNormalBlogToRedis(normalMap);
                        }
                        result.add(blog);
                    }
                }
            }
        }

        return result;
    }
}