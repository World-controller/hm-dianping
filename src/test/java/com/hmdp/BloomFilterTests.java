package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.BloomFilterDemo;
import com.hmdp.utils.CacheClient;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_TTL;

@Slf4j
@SpringBootTest
public class BloomFilterTests {

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private IShopService shopService;

    @Resource
    private CacheClient cacheClient;
    
    @Resource
    private BloomFilterDemo bloomFilterDemo;

    /**
     * 测试布隆过滤器的效果
     * 分别测试存在的ID和不存在的ID
     */
    @Test
    void testBloomFilter() {
        // 获取布隆过滤器
        RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter("shop:bloom:filter");
        
        // 测试存在的ID (假设ID为1的商铺存在)
        Long existingId = 1L;
        boolean existsInBloom = bloomFilter.contains(existingId);
        log.info("ID {} 在布隆过滤器中: {}", existingId, existsInBloom);
        
        // 使用布隆过滤器方法查询存在的商铺
        Shop existingShop = cacheClient.handCachePenetrationByBloomFilter(
                SHOP_KEY,
                existingId,
                Shop.class,
                shopService::getById,
                bloomFilter,
                SHOP_TTL,
                TimeUnit.MINUTES
        );
        log.info("查询存在的商铺结果: {}", existingShop != null ? "找到" : "未找到");
        
        // 测试不存在的ID (假设ID为-1的商铺不存在)
        Long nonExistingId = -1L;
        boolean nonExistsInBloom = bloomFilter.contains(nonExistingId);
        log.info("ID {} 在布隆过滤器中: {}", nonExistingId, nonExistsInBloom);
        
        // 使用布隆过滤器方法查询不存在的商铺
        Shop nonExistingShop = cacheClient.handCachePenetrationByBloomFilter(
                SHOP_KEY,
                nonExistingId,
                Shop.class,
                shopService::getById,
                bloomFilter,
                SHOP_TTL,
                TimeUnit.MINUTES
        );
        log.info("查询不存在的商铺结果: {}", nonExistingShop != null ? "找到" : "未找到");
    }
    
    /**
     * 测试使用BloomFilterDemo类查询商铺
     */
    @Test
    void testBloomFilterDemo() {
        // 测试存在的ID (假设ID为1的商铺存在)
        Long existingId = 1L;
        Shop existingShop = bloomFilterDemo.queryShopWithBloomFilter(existingId);
        log.info("查询ID为{}的商铺结果: {}", existingId, existingShop != null ? "找到" : "未找到");
        
        // 测试不存在的ID (假设ID为-1的商铺不存在)
        Long nonExistingId = -1L;
        Shop nonExistingShop = bloomFilterDemo.queryShopWithBloomFilter(nonExistingId);
        log.info("查询ID为{}的商铺结果: {}", nonExistingId, nonExistingShop != null ? "找到" : "未找到");
    }
}
