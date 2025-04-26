package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

        @Resource
        private CacheClient cacheClient;

        @Resource
        private ShopServiceImpl shopService;

        @Resource
        private RedisIdWorker redisIdWorker;
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

}
