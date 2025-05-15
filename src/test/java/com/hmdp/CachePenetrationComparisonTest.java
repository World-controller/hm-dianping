package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.SHOP_KEY;

/**
 * 缓存穿透解决方案性能比较测试
 */
@Slf4j
@SpringBootTest
public class CachePenetrationComparisonTest {

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private IShopService shopService;

    @Resource
    private CacheClient cacheClient;

    // 布隆过滤器名称
    private static final String SHOP_BLOOM_FILTER = "shop:bloom:filter";

    // 测试线程数
    private static final int THREAD_COUNT = 100;
    
    // 每个线程执行的请求数
    private static final int REQUESTS_PER_THREAD = 100;
    
    // 模拟的不存在ID范围
    private static final long NON_EXISTING_ID_START = 10000;
    private static final long NON_EXISTING_ID_END = 20000;

    /**
     * 测试空值缓存方案处理缓存穿透的性能
     */
    @Test
    void testEmptyValueCachePenetration() throws InterruptedException {
        log.info("开始测试空值缓存方案处理缓存穿透的性能...");
        
        // 创建线程池
        ExecutorService threadPool = Executors.newFixedThreadPool(THREAD_COUNT);
        
        // 计数器，用于等待所有线程完成
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        
        // 记录总请求数和数据库查询次数
        AtomicInteger totalRequests = new AtomicInteger(0);
        AtomicInteger dbQueries = new AtomicInteger(0);
        
        // 记录开始时间
        long startTime = System.currentTimeMillis();
        
        // 启动多个线程模拟并发请求
        for (int i = 0; i < THREAD_COUNT; i++) {
            threadPool.submit(() -> {
                try {
                    for (int j = 0; j < REQUESTS_PER_THREAD; j++) {
                        // 生成一个不存在的ID
                        long nonExistingId = NON_EXISTING_ID_START + (long) (Math.random() * (NON_EXISTING_ID_END - NON_EXISTING_ID_START));
                        
                        // 使用空值缓存方案查询
                        Shop shop = cacheClient.handCachePenetrationByBlankValue(
                                SHOP_KEY,
                                nonExistingId,
                                Shop.class,
                                id -> {
                                    // 记录数据库查询次数
                                    dbQueries.incrementAndGet();
                                    return shopService.getById(id);
                                },
                                CACHE_NULL_TTL,
                                TimeUnit.MINUTES
                        );
                        
                        // 增加总请求数
                        totalRequests.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        // 等待所有线程完成
        latch.await();
        
        // 记录结束时间
        long endTime = System.currentTimeMillis();
        
        // 计算执行时间和性能指标
        long duration = endTime - startTime;
        double requestsPerSecond = (double) totalRequests.get() / (duration / 1000.0);
        
        log.info("空值缓存方案测试完成:");
        log.info("总请求数: {}", totalRequests.get());
        log.info("数据库查询次数: {}", dbQueries.get());
        log.info("执行时间: {}ms", duration);
        log.info("每秒请求数: {}", requestsPerSecond);
        
        // 关闭线程池
        threadPool.shutdown();
    }

    /**
     * 测试布隆过滤器方案处理缓存穿透的性能
     */
    @Test
    void testBloomFilterCachePenetration() throws InterruptedException {
        log.info("开始测试布隆过滤器方案处理缓存穿透的性能...");
        
        // 获取布隆过滤器
        RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter(SHOP_BLOOM_FILTER);
        
        // 确保布隆过滤器已初始化
        if (!bloomFilter.isExists()) {
            bloomFilter.tryInit(1000, 0.01);
            
            // 添加所有存在的商铺ID到布隆过滤器
            List<Shop> shops = shopService.list();
            for (Shop shop : shops) {
                bloomFilter.add(shop.getId());
            }
            log.info("布隆过滤器初始化完成，已加载商铺ID数量：{}", bloomFilter.count());
        }
        
        // 创建线程池
        ExecutorService threadPool = Executors.newFixedThreadPool(THREAD_COUNT);
        
        // 计数器，用于等待所有线程完成
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        
        // 记录总请求数和数据库查询次数
        AtomicInteger totalRequests = new AtomicInteger(0);
        AtomicInteger dbQueries = new AtomicInteger(0);
        
        // 记录开始时间
        long startTime = System.currentTimeMillis();
        
        // 启动多个线程模拟并发请求
        for (int i = 0; i < THREAD_COUNT; i++) {
            threadPool.submit(() -> {
                try {
                    for (int j = 0; j < REQUESTS_PER_THREAD; j++) {
                        // 生成一个不存在的ID
                        long nonExistingId = NON_EXISTING_ID_START + (long) (Math.random() * (NON_EXISTING_ID_END - NON_EXISTING_ID_START));
                        
                        // 使用布隆过滤器方案查询
                        Shop shop = cacheClient.handCachePenetrationByBloomFilter(
                                SHOP_KEY,
                                nonExistingId,
                                Shop.class,
                                id -> {
                                    // 记录数据库查询次数
                                    dbQueries.incrementAndGet();
                                    return shopService.getById(id);
                                },
                                bloomFilter,
                                CACHE_NULL_TTL,
                                TimeUnit.MINUTES
                        );
                        
                        // 增加总请求数
                        totalRequests.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        // 等待所有线程完成
        latch.await();
        
        // 记录结束时间
        long endTime = System.currentTimeMillis();
        
        // 计算执行时间和性能指标
        long duration = endTime - startTime;
        double requestsPerSecond = (double) totalRequests.get() / (duration / 1000.0);
        
        log.info("布隆过滤器方案测试完成:");
        log.info("总请求数: {}", totalRequests.get());
        log.info("数据库查询次数: {}", dbQueries.get());
        log.info("执行时间: {}ms", duration);
        log.info("每秒请求数: {}", requestsPerSecond);
        
        // 关闭线程池
        threadPool.shutdown();
    }
}
