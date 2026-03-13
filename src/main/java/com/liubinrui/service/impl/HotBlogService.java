package com.liubinrui.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.liubinrui.enums.BlogActionEnum;
import com.liubinrui.heavykeeper.HeavyKeeperRateLimiter;
import com.liubinrui.mapper.BlogMapper;
import com.liubinrui.model.entity.Blog;
import com.liubinrui.service.BlogService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
@Slf4j
@Service
public class HotBlogService {
    @Autowired
    private BlogService blogService;
    @Autowired
    private BlogMapper blogMapper;
    // ========== 基础配置 ==========
    private static final int HOT_TOP_N = 100;                // 热门博客TopN数量
    private static final int BASE_THRESHOLD = 5;             // 5分钟内高频互动阈值
    private static final double HOT_DECAY_FACTOR = 0.9;      // 热度衰减因子（每分钟衰减10%）
    private static final long SORT_INTERVAL_SEC = 60;        // 热度排序间隔（1分钟）

    // ========== 缓存配置 ==========
    private static final long CACHE_EXPIRE_SEC = 5 * 60;     // 缓存过期时间（5分钟）
    private static final String HOT_BLOG_LIST_KEY = "HOT_BLOG_TOP_" + HOT_TOP_N; // 热门列表缓存Key

    // ========== 核心存储 ==========
    private final Map<Long, Integer> blogHotScoreMap = new ConcurrentHashMap<>(); // 修正：热度值改为Integer
    private final HeavyKeeperRateLimiter rateLimiter = new HeavyKeeperRateLimiter(BASE_THRESHOLD);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // ========== Caffeine缓存核心 ==========
    // 缓存1：热门博客TopN列表（Key: 固定字符串，Value: List<Long>）
    private final Cache<String, List<Long>> hotBlogListCache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_EXPIRE_SEC, TimeUnit.SECONDS)
            .maximumSize(10)
            .build();

    // 缓存2：单博客热度值（Key: blogId，Value: Integer）修正：类型改为Integer
    private final Cache<Long, Integer> blogHotScoreCache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_EXPIRE_SEC, TimeUnit.SECONDS)
            .maximumSize(10000)
            .recordStats()
            .build();

    // 缓存3：热门博客详情（Key: blogId，Value: BlogDetail）【新增核心缓存】
    private final Cache<Long, Blog> hotBlogCache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_EXPIRE_SEC, TimeUnit.SECONDS) // 与热门列表缓存同过期时间
            .maximumSize(HOT_TOP_N) // 仅缓存热门TopN的详情，节省内存
            .recordStats()
            .build();

    /**
     * 初始化：启动定时任务（热度衰减+热门排序+缓存更新）
     */
    public HotBlogService() {
        // 每分钟执行：热度衰减 → 重新排序 → 更新缓存
        scheduler.scheduleAtFixedRate(() -> {
            decayHotScore();    // 1. 热度衰减
            sortHotBlogs();     // 2. 重新计算TopN
            refreshAllCache();  // 3. 主动刷新缓存
            syncHotScoreToDB(); // 4. 异步同步数据到DB
        }, 0, SORT_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    @PostConstruct
    public void init() {
        loadHotScoreFromDB(); // 此时blogService已注入，可安全调用
    }

    /**
     * 核心方法：获取单博客详情（优先读热门缓存）
     *
     * @param blogId 博客ID
     * @return 博客详情
     */
    public Blog getBlog(Long blogId) {
        // 1. 直接查热门详情缓存 (Caffeine 速度极快)
        Blog cache = hotBlogCache.getIfPresent(blogId);
        if (cache != null) {
            return cache;
        }

        // 2. 缓存未命中 -> 查库 (无论是热门还是非热门，都走这里)
        Blog blog = blogService.getById(blogId);
        if (blog != null) {
            // 3. 【关键优化】只有当该博客确实在热门列表中时，才写入热点缓存
            // 防止非热门博客污染 hotBlogCache (虽然 maximumSize 限制了，但最好逻辑上也控制)
            List<Long> hotIds = hotBlogListCache.getIfPresent(HOT_BLOG_LIST_KEY);
            if (hotIds != null && hotIds.contains(blogId)) {
                blog.setHotScore(getBlogHotScore(blogId));
                hotBlogCache.put(blogId, blog);
            }
        }
        return blog;
    }

    private Integer getBlogHotScore(Long blogId) {
        // 1. 优先读缓存
        Integer cacheScore = blogHotScoreCache.getIfPresent(blogId);
        if (cacheScore != null) {
            return cacheScore;
        }
        // 2. 缓存未命中：从原始存储读取并写入缓存（写回）
        Integer originScore = blogHotScoreMap.getOrDefault(blogId, 0);
        blogHotScoreCache.put(blogId, originScore);
        return originScore;
    }

    public void recordUserAction(Long userId, Long blogId, BlogActionEnum action) {
        // 示例逻辑：根据不同操作类型计算热度增量
        int increment = switch (action) {
            case LIKE -> 5;
            case COMMENT -> 3;
            case FORWARD -> 8;
            default -> 0;
        };
        // 限流判断
        String limitKey = userId + "_" + blogId;
        // 2. 调用正确的限流方法（isOverLimit返回true=超限/拦截，false=放行）
        if (rateLimiter.isOverLimit(limitKey)) {
            return;
        }
        // 更新热度值
        blogHotScoreMap.compute(blogId, (k, v) -> (v == null ? 0 : v) + increment);
        // 同步更新缓存
        blogHotScoreCache.put(blogId, blogHotScoreMap.get(blogId));
    }

    private void loadHotScoreFromDB() {
        // 查询所有博客的ID和热度值,这里有风险，数据库100万条，则爆炸，不能用list
        // 只加载热度大于 0 的前 5000 条，避免全表扫描
        List<Blog> activeBlogs = blogMapper.selectList(
                new LambdaQueryWrapper<Blog>()
                        .gt(Blog::getHotScore, 0)
                        .orderByDesc(Blog::getHotScore)
                        .last("LIMIT 5000")
        );
        for (Blog blog : activeBlogs) {
            blogHotScoreMap.put(blog.getId(), blog.getHotScore());
            blogHotScoreCache.put(blog.getId(), blog.getHotScore());
        }
        log.info("热门博客服务初始化完成，加载 {} 条活跃数据", activeBlogs.size());
    }

    private void syncHotScoreToDB() {
        if (blogHotScoreMap.isEmpty()) {
            return;
        }
        // 批量更新（避免单条update性能差）
        List<Blog> updateList = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : blogHotScoreMap.entrySet()) {
            Blog blog = new Blog();
            blog.setId(entry.getKey());
            blog.setHotScore(entry.getValue());
            updateList.add(blog);
        }
        // 调用Mapper批量更新
        blogMapper.batchUpdateHotScore(updateList);
    }

    public void resetLikeAction(Long blogId, double increment) {
        // 修正：将double增量转为int
        int intIncrement = (int) Math.round(increment);
        blogHotScoreMap.compute(blogId, (k, v) -> (v == null ? 0 : v) - intIncrement);
        // 同步更新缓存
        blogHotScoreCache.put(blogId, blogHotScoreMap.get(blogId));
    }

    private void decayHotScore() {
        blogHotScoreMap.replaceAll((blogId, score) -> {
            if (score <= 0) return 0;
            // 【修改点】直接使用 (int) 强制转换，相当于向下取整 (Floor)
            // 5 * 0.95 = 4.75 -> 4 (而不是 5)
            // 1 * 0.95 = 0.95 -> 0 (直接归零)
            int newScore = (int) (score * HOT_DECAY_FACTOR);
            // 同步更新缓存
            blogHotScoreCache.put(blogId, newScore);
            return newScore;
        });
        // 【重要】清理掉所有变为 0 的数据，防止内存泄漏
        blogHotScoreMap.entrySet().removeIf(entry -> entry.getValue() == 0);
    }

    public List<Long> getHotBlogTopN() {
        // 1. 优先从缓存读取
        List<Long> cacheList = hotBlogListCache.getIfPresent(HOT_BLOG_LIST_KEY);
        if (cacheList != null && !cacheList.isEmpty()) {
            return new ArrayList<>(cacheList); // 返回副本，避免并发修改
        }

        // 2. 缓存未命中：手动排序并更新缓存（降级逻辑）
        sortHotBlogs();
        cacheList = hotBlogListCache.getIfPresent(HOT_BLOG_LIST_KEY);
        return (cacheList != null) ? new ArrayList<>(cacheList) : new ArrayList<>();
    }

    private void sortHotBlogs() {
        List<Map.Entry<Long, Integer>> sortedList = new ArrayList<>(blogHotScoreMap.entrySet());
        // 修正：按Integer类型排序
        sortedList.sort((e1, e2) -> Integer.compare(e2.getValue(), e1.getValue()));

        List<Long> topNList = new ArrayList<>();
        int takeCount = Math.min(HOT_TOP_N, sortedList.size());
        for (int i = 0; i < takeCount; i++) {
            topNList.add(sortedList.get(i).getKey());
        }
        // 仅更新内存列表（缓存刷新在refreshAllCache中）
        hotBlogListCache.put(HOT_BLOG_LIST_KEY, topNList);
    }

    /**
     * 扩展：刷新所有缓存（包含详情缓存）
     */
    private void refreshAllCache() {
        // 1. 刷新热门列表缓存（原有逻辑）
        List<Long> hotList = new ArrayList<>();
        List<Map.Entry<Long, Integer>> sortedList = new ArrayList<>(blogHotScoreMap.entrySet());
        sortedList.sort((e1, e2) -> Integer.compare(e2.getValue(), e1.getValue()));
        int takeCount = Math.min(HOT_TOP_N, sortedList.size());
        for (int i = 0; i < takeCount; i++) {
            hotList.add(sortedList.get(i).getKey());
        }
        hotBlogListCache.put(HOT_BLOG_LIST_KEY, hotList);

        // 2. 批量刷新单博客热度缓存（原有逻辑）
        for (Long blogId : hotList) {
            Integer score = blogHotScoreMap.get(blogId);
            if (score != null) {
                blogHotScoreCache.put(blogId, score);
            }
        }

        // 3. 刷新热门博客详情缓存【新增】
        refreshHotBlogDetailCache();
    }

    /**
     * 刷新热门博客详情缓存（定时任务/手动触发）
     */
    private void refreshHotBlogDetailCache() {
        List<Long> hotBlogIds = getHotBlogTopN(); // 获取最新热门列表
        if (hotBlogIds.isEmpty()) {
            hotBlogCache.invalidateAll(); // 无热门博客，清空详情缓存
            return;
        }

        // 批量查询数据库 (一次 SQL 搞定)
        // 假设 blogService 有一个 listByIds 方法
        List<Blog> blogs = blogService.listByIds(hotBlogIds);

        // 构建 Map 方便匹配
        Map<Long, Blog> blogMap = blogs.stream()
                .collect(Collectors.toMap(Blog::getId, b -> b));

        for (Long blogId : hotBlogIds) {
            Blog blog = blogMap.get(blogId);
            if (blog != null) {
                blog.setHotScore(getBlogHotScore(blogId));
                hotBlogCache.put(blogId, blog);
            } else {
                // 如果数据库里没了，清理缓存
                hotBlogCache.invalidate(blogId);
            }
        }
    }

    @PreDestroy // 需要引入 jakarta.annotation.PreDestroy
    public void destroy() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
