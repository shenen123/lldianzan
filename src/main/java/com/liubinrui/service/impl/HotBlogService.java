package com.liubinrui.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.liubinrui.enums.BlogActionEnum;
import com.liubinrui.heavykeeper.HeavyKeeperRateLimiter;
import com.liubinrui.mapper.BlogMapper;
import com.liubinrui.model.entity.Blog;
import com.liubinrui.service.BlogService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
    private final Cache<Long, Blog> hotBlogDetailCache = Caffeine.newBuilder()
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
            syncHotScoreToDB();
        }, 0, SORT_INTERVAL_SEC, TimeUnit.SECONDS);
    }
    @PostConstruct
    public void init() {
        loadHotScoreFromDB(); // 此时blogService已注入，可安全调用
    }
    private void loadHotScoreFromDB() {
        // 查询所有博客的ID和热度值（可分页，示例简化为全量）
        List<Blog> allBlogs = blogService.list();
        for (Blog blog : allBlogs) {
            blogHotScoreMap.put(blog.getId(), blog.getHotScore());
            // 同步到缓存
            blogHotScoreCache.put(blog.getId(), blog.getHotScore());
        }
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
    // ========== 基础方法实现 ==========
    public boolean recordUserAction(Long userId, Long blogId, BlogActionEnum action) {
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
            return false;
        }

        // 更新热度值
        blogHotScoreMap.compute(blogId, (k, v) -> (v == null ? 0 : v) + increment);
        // 同步更新缓存
        blogHotScoreCache.put(blogId, blogHotScoreMap.get(blogId));
        return true;
    }

    public void resetLikeAction(Long userId, Long blogId, double increment) {
        // 修正：将double增量转为int
        int intIncrement = (int) Math.round(increment);
        blogHotScoreMap.compute(blogId, (k, v) -> (v == null ? 0 : v) - intIncrement);
        // 同步更新缓存
        blogHotScoreCache.put(blogId, blogHotScoreMap.get(blogId));
    }

    private void decayHotScore() {
        blogHotScoreMap.replaceAll((blogId, score) -> {
            // 修正：衰减后保留整数（四舍五入）
            int newScore = (int) Math.round(score * HOT_DECAY_FACTOR);
            // 衰减后同步更新缓存
            blogHotScoreCache.put(blogId, newScore);
            return newScore;
        });
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

    public Integer getBlogHotScore(Long blogId) {
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

    // ========== 新增核心方法 ==========

    /**
     * 刷新热门博客详情缓存（定时任务/手动触发）
     * 注：实际业务中，这里需调用DAO层从数据库查询热门博客详情
     */
    private void refreshHotBlogDetailCache() {
        List<Long> hotBlogIds = getHotBlogTopN(); // 获取最新热门列表
        if (hotBlogIds.isEmpty()) {
            hotBlogDetailCache.invalidateAll(); // 无热门博客，清空详情缓存
            return;
        }

        // 模拟从数据库查询热门博客详情（实际业务替换为DAO查询）
        for (Long blogId : hotBlogIds) {
            Blog blog = blogService.getById(blogId); // 查库逻辑
            if (blog != null) {
                // 补充热度值（从缓存/原始存储获取）
                blog.setHotScore(getBlogHotScore(blogId));
                // 修正：变量名错误 detail → blog
                hotBlogDetailCache.put(blogId, blog);
            }
        }
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
     * 核心方法：获取单博客详情（优先读热门缓存）
     *
     * @param blogId 博客ID
     * @return 博客详情
     */
    public Blog getBlog(Long blogId) {
        // 步骤1：判断该博客是否在热门列表中（优先读缓存）
        List<Long> hotBlogIds = hotBlogListCache.getIfPresent(HOT_BLOG_LIST_KEY);
        if (hotBlogIds != null && hotBlogIds.contains(blogId)) {
            // 步骤2：是热门博客 → 优先读详情缓存
            Blog cacheDetail = hotBlogDetailCache.getIfPresent(blogId);
            if (cacheDetail != null) {
                return cacheDetail; // 直接返回缓存的详情
            }

            // 步骤3：热门列表命中，但详情缓存未命中 → 查库+更新缓存
            Blog blog = blogService.getById(blogId);
            if (blog != null) {
                blog.setHotScore(getBlogHotScore(blogId));
                hotBlogDetailCache.put(blogId, blog); // 写入缓存
                return blog;
            }
        }

        // 步骤4：非热门博客 → 直接查库（不走缓存）
        return blogService.getById(blogId);
    }


    /**
     * 手动刷新所有缓存（包含详情缓存）
     */
    public void manualRefreshCache() {
        refreshAllCache();
    }

    /**
     * 获取缓存统计（新增详情缓存命中率）
     */
    public String getCacheStats() {
        return "热门列表缓存命中率：" + hotBlogListCache.stats().hitRate() + "\n" +
                "单博客热度缓存命中率：" + blogHotScoreCache.stats().hitRate() + "\n" +
                "热门详情缓存命中率：" + hotBlogDetailCache.stats().hitRate();
    }

    /**
     * 关闭资源
     */
    public void shutdown() {
        scheduler.shutdown();
        hotBlogListCache.invalidateAll();
        blogHotScoreCache.invalidateAll();
        hotBlogDetailCache.invalidateAll(); // 清空详情缓存
    }
}
