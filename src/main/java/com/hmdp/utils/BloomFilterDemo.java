package com.hmdp.utils;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_TTL;

/**
 * 布隆过滤器演示类
 * 实现CommandLineRunner接口，在应用启动后自动初始化布隆过滤器
 */
@Slf4j
@Component
public class BloomFilterDemo implements CommandLineRunner {

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private IShopService shopService;

    @Resource
    private CacheClient cacheClient;

    // 布隆过滤器名称
    private static final String SHOP_BLOOM_FILTER = "shop:bloom:filter";

    /**
     * 初始化布隆过滤器并加载所有商铺ID
     */
    @Override
    public void run(String... args) throws Exception {
        // 创建布隆过滤器
        RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter(SHOP_BLOOM_FILTER);
        
        // 初始化布隆过滤器，预计元素数量为1000，误判率为0.01
        bloomFilter.tryInit(1000, 0.01);
        
        // 查询所有商铺ID并添加到布隆过滤器
        List<Shop> shops = shopService.list();
        log.info("正在初始化商铺布隆过滤器，商铺数量：{}", shops.size());
        
        for (Shop shop : shops) {
            bloomFilter.add(shop.getId());
        }
        
        log.info("商铺布隆过滤器初始化完成，已加载商铺ID数量：{}", bloomFilter.count());
    }

    /**
     * 使用布隆过滤器查询商铺
     * 
     * @param id 商铺ID
     * @return 商铺信息
     */
    public Shop queryShopWithBloomFilter(Long id) {
        // 获取布隆过滤器
        RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter(SHOP_BLOOM_FILTER);
        
        // 使用布隆过滤器方法查询商铺
        return cacheClient.handCachePenetrationByBloomFilter(
                SHOP_KEY, 
                id, 
                Shop.class, 
                shopService::getById, 
                bloomFilter, 
                SHOP_TTL, 
                TimeUnit.MINUTES
        );
    }
}
