package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

        @Resource
        private CacheClient cacheClient;

        @Resource
        private ShopServiceImpl shopService;

        @Resource
        private RedisIdWorker redisIdWorker;

        @Resource
        private StringRedisTemplate stringRedisTemplate;

        private final ExecutorService es = Executors.newFixedThreadPool(500);
        @Test
        public void testIdWorker() throws InterruptedException {
            // 创建计数器闭锁，等待300个任务完成
            CountDownLatch latch = new CountDownLatch(300);
            // 定义任务：每个任务生成100个ID
            Runnable task = ()->{
                for (int i = 0; i < 100; i++) {
                    // 使用redisIdWorker生成一个带"order"前缀的ID
                    long id = redisIdWorker.nextId("order");
                    System.out.println("id="+id);
                }
                // 任务完成后计数器-1
                latch.countDown();
            };
            long begin = System.currentTimeMillis();
            // 提交300个任务到线程池执行
            for (int i = 0; i < 300; i++) {
                es.submit(task);
            }
            // 等待所有任务完成
            latch.await();
            long end = System.currentTimeMillis();
            System.out.println("time="+(end-begin));
        }

        @Test
        public void testSaveShop() throws InterruptedException {
            //针对热点数据进行数据预热后，然后使用逻辑过期技术以解决缓存击穿问题
            Shop[] shops = new Shop[5];
            for (Long i = 1L; i <= 5L;i ++){
                shops[(int) (i-1)] = shopService.getById(i);
                cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY+ i,shops[(int) (i-1)],10L, TimeUnit.SECONDS);
            }
        }

        @Test
        void loadShopData(){
//            1.查询店铺信息
            List<Shop> list = shopService.list();
//            2.把店铺分组,按照typeId分组,typeId一致的放到一个集合
            Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
//                3.分批完成写入Redis
            for (Map.Entry<Long,List<Shop>> entry : map.entrySet() ){
                //            3.1获取类型id
                Long typeId = entry.getKey();
                String key = "shop:geo:" + typeId;
//            3.2获取同类型的店铺集合
                List<Shop> value = entry.getValue();
                List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
//                3.3分批写入redis geoadd key 经度 纬度 member
                for (Shop shop : value){
//                    stringRedisTemplate.opsForGeo().add(key,new Point(shop.getX(),shop.getY()),shop.getId().toString());
                    locations.add(new RedisGeoCommands.GeoLocation<>(
                            shop.getId().toString(),
                            new Point(shop.getX(),shop.getY())
                    ));
                }
                stringRedisTemplate.opsForGeo().add(key, locations);//一次直接完成,减少和redis的交互次数,提高效率



            }



        }

}
