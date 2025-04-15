package com.hmdp.service.impl;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static com.hmdp.utils.RedisConstants.*;
/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
@SuppressWarnings({"all"})
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Result queryById(Long id) {
        //缓存穿透代码实现
//        Shop shop = queryWithPassThrough(id);

//        互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);

//        逻辑过期解决缓存击穿
        Shop shop = queryWithLogicalExpire(id);
        return Result.ok(shop);
    }

    /*
    * 缓存穿透代码实现
    * */

    public  Shop queryWithPassThrough(Long id){
        //        1.从Redis查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopCache = stringRedisTemplate.opsForValue().get(key);
//        2.判断是否存在
        if (StrUtil.isNotBlank(shopCache)){
            //        3.存在，直接返回商铺信息
            Shop shop = JSONUtil.toBean(shopCache, Shop.class);
            return shop;
        }

//        解决缓存穿透第二步：判断redis缓存中是否命中了空值
        if (shopCache != null) {
            //此时，我们已经知道 shopCache 要么是 null，要么是空字符串或只包含空白字符
            //如果 shopCache != null，那么它一定是空字符串或只包含空白字符，表示之前已经查询过数据库并确认该商铺不存在
//            log.info("此时已经写入redis");
            return null;
        }

//        4.不存在则根据id查询数据库
        Shop shop = getById(id);
//        5.数据库中商铺不存在返回404  ->解决缓存穿透第一步：将空值写入redis并设置较短的有效期
        if(shop == null){
            stringRedisTemplate.opsForValue().set(key, "",CACHE_NULL_TTL,TimeUnit.MINUTES);
//            log.info("此时还未写入redis");
            return null;
        }
//        6.如果商铺存在将商铺数据写入Redis   (超时剔除策略)
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL,TimeUnit.MINUTES);
//        7.返回商铺信息
        return shop;
    }

    /*
    * 互斥锁解决缓存击穿
    * */

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);//防止Boolean->boolean过程中出现空指针异常
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    public  Shop queryWithMutex(Long id) {
        //        1.从Redis查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopCache = stringRedisTemplate.opsForValue().get(key);
//        2.判断是否存在
        if (StrUtil.isNotBlank(shopCache)) {
            //        3.存在，直接返回商铺信息
            Shop shop = JSONUtil.toBean(shopCache, Shop.class);
            return shop;
        }

//        解决缓存穿透第二步：判断redis缓存中是否命中了空值
        if (shopCache != null) {
            //此时，我们已经知道 shopCache 要么是 null，要么是空字符串或只包含空白字符
            //如果 shopCache != null，那么它一定是空字符串或只包含空白字符，表示之前已经查询过数据库并确认该商铺不存在
            return null;
        }
        Shop shop = null;
        String lockKey = LOCK_SHOP_KEY + id;
        try {
            //实现缓存重建（即缓存击穿问题）
//        4.1获取互斥锁

            boolean isLock = tryLock(lockKey);
//        4.2判断互斥锁获取是否成功
            if (!isLock) {
                //        4.3不成功，休眠一会进行重试
                Thread.sleep(50);
                return queryWithMutex(id);//模拟每个线程递归执行，直到返回商铺信息为止
            }
//        4.4成功 则根据id查询数据库
            shop = getById(id);
            Thread.sleep(200);//模拟重建的延时
//        5.数据库中商铺不存在返回404  ->解决缓存穿透第一步：将空值写入redis并设置较短的有效期
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                //            log.info("此时还未写入redis");
                return null;
            }
//        6.如果商铺存在将商铺数据写入Redis   (超时剔除策略)
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
//            7.释放互斥锁
            unlock(lockKey);
        }
//        8.返回商铺信息
        return shop;
    }

    /*
    * 逻辑过期解决缓存击穿第一步：缓存重建的数据预热
    * */

    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
//        1.查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);//模拟多线程的情况
//        2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
//        3.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }

    /*
    * 逻辑过期解决缓存击穿第二步：正式进行缓存重建
    * */
    public  Shop queryWithLogicalExpire(Long id){
        //        1.从Redis查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopCache = stringRedisTemplate.opsForValue().get(key);
//        2.判断是否存在
        if (StrUtil.isBlank(shopCache)){
            //        3.未命中，直接返回空
            return null;
        }
//        4.命中：判断缓存是否过期
        RedisData redisData = JSONUtil.toBean(shopCache, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())){
            //        4.1未过期：返回商铺信息
            JSONObject shopData = (JSONObject) redisData.getData();
            Shop shop = JSONUtil.toBean(shopData, Shop.class);
            return shop;
        }
//        4.2过期，尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
//        5判断是否获取锁
        if (!isLock){
            //                5.1否，返回商铺信息
            JSONObject shopData = (JSONObject) redisData.getData();
            Shop shop = JSONUtil.toBean(shopData, Shop.class);
            return shop;
        }
//                5.2是，开启独立线程，进行缓存重建(利用线程池)
        CACHE_REBUILD_EXECUTOR.submit(()->{
            //重建缓存
            try {
                this.saveShop2Redis(id,20L);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                //释放锁
                unlock(lockKey);
            }

        });
        JSONObject shopData = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(shopData, Shop.class);
//        7.返回商铺信息
        return shop;
    }



    @Override
    @Transactional(rollbackFor = {Exception.class})
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺id不能为空！");
        }
        /*
          主动更新策略实现
         */
//        1.先    操作数据库
        updateById(shop);
//        2.再    进行缓存删除的操作
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
