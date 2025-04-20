package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
@SuppressWarnings({"all"})

/**
 * 方法1和方法3针对普通key
 * 方法2和方法4针对热点key
 */

public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //    方法1：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
    /**
     * 将数据加入Redis，并设置有效期
     *
     * @param key   缓存key
     * @param value 缓存数据值
     * @param time  有效时间
     * @param unit  有效时间单位
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    //    方法2：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
    /**
     * 将数据加入Redis，并设置逻辑过期时间（实际有效期为永久）
     *
     * @param key               缓存key
     * @param value             缓存数据值
     * @param expireTime        逻辑过期时间
     * @param unit              时间单位
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入Redis - 使用redisData对象而不是原始值
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    //    方法3：根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
    /**
     * 根据id查询数据（使用缓存空值法解决缓存穿透）
     *
     * @param keyPrefix  缓存key前缀
     * @param id         查询id，与缓存key前缀拼接
     * @param type       查询数据的Class类型
     * @param dbFallback 根据id查询数据的函数式接口
     * @param time       有效期
     * @param unit       时间单位
     * @param <R>
     * @param <ID>
     * @return
     */

    public <T,ID> T handCachePenetrationByBlankValue(String keyPrefix, ID id, Class<T> type, Function<ID,T> dbFallback, Long time, TimeUnit unit){
        //        1.从Redis查询商铺缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
//        2.判断是否存在
        if (StrUtil.isNotBlank(json)){
            //        3.存在，直接返回商铺信息
            T t = JSONUtil.toBean(json, type);
            return t;
        }

//        解决缓存穿透第二步：判断redis缓存中是否命中了空值
        if (json != null) {
            //此时，我们已经知道 json 要么是 null，要么是空字符串或只包含空白字符
            //如果 json != null，那么它一定是空字符串或只包含空白字符，表示之前已经查询过数据库并确认该商铺不存在
//            log.info("此时已经写入redis");
            return null;
        }

//        4.不存在则根据id查询数据库
        T t = dbFallback.apply(id);
//        5.数据库中商铺不存在返回404  ->解决缓存穿透第一步：将空值写入redis并设置较短的有效期
        if(t == null){
            stringRedisTemplate.opsForValue().set(key, "",CACHE_NULL_TTL,TimeUnit.MINUTES);
//            log.info("此时还未写入redis");
            return null;
        }
//        6.如果商铺存在将商铺数据写入Redis   (超时剔除策略)
        this.set(key,t,time,unit);
//        7.返回商铺信息
        return t;
    }

    //    方法4：根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题

    /**
     * 尝试获取锁，判断是否获取锁成功
     * setIfAbsent()：如果缺失不存在这个key，则可以set，返回true；存在key不能set，返回false。相当于setnx命令
     * @param lockKey 互斥锁的key
     * @return 是否获取到锁
     */

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);//防止Boolean->boolean过程中出现空指针异常
    }

    /**
     * 释放互斥锁
     * @param lockKey 互斥锁的key
     */

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    /**
     * 缓存重建线程池
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 根据id查询热点数据（使用逻辑过期解决缓存击穿）
     *
     * @param cacheKeyPrefix    缓存key前缀
     * @param id                查询id，与缓存key前缀拼接
     * @param type              查询数据的Class类型
     * @param lockKeyPrefix     缓存数据锁前缀，与查询id拼接
     * @param dbFallback        根据id查询数据的函数式接口
     * @param expireTime        逻辑过期时间
     * @param unit              时间单位
     * @param <R>
     * @param <ID>
     * @return
     */
    public <T,ID> T handCacheBreakdownByLogicalExpire(String cachekeyPrefix, ID id, Class<T> type, String lockKeyPrefix, Function<ID,T> dbFallback, Long time, TimeUnit unit){
        // 从缓存中获取热点数据
        String cacheKey = cachekeyPrefix+ id;
        String json = stringRedisTemplate.opsForValue().get(cacheKey);
//        // 判断缓存是否命中（由于是热点数据，提前进行缓存预热，默认缓存一定会命中）
        if (StrUtil.isBlank(json)){
            // 缓存未命中，说明查到的不是热点key，直接返回空
            return null;
        }
        //        // 缓存命中，先把json反序列化为逻辑过期对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject Data = (JSONObject) redisData.getData();
        // 将Object对象转成JSONObject再反序列化为目标对象
        T t = (T) JSONUtil.toBean(Data, type);

        // 判断是否逻辑过期
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())){
            // 未过期：直接返回正确数据
            return t;
        }
        // 已过期，先尝试获取互斥锁，再判断是否需要缓存重建
        String lockKey = lockKeyPrefix + id;
        boolean isLock = tryLock(lockKey);
        // 判断是否获取锁
        if (isLock){
            // 在线程1重建缓存期间，线程2进行过期判断，假设此时key是过期状态，线程1重建完成并释放锁，线程2立刻获取锁，并启动异步线程执行重建，那此时的重建就与线程1的重建重复了
            // 因此需要在线程2获取锁成功后，在这里再次检测redis中缓存是否过期（DoubleCheck），如果未过期则无需重建缓存，防止数据过期之后，刚释放锁就有线程拿到锁的情况，重复访问数据库进行重建
            json = stringRedisTemplate.opsForValue().get(cacheKey);
            // 缓存命中，先把json反序列化为逻辑过期对象
            redisData = JSONUtil.toBean(json, RedisData.class);
            // 将Object对象转成JSONObject再反序列化为目标对象
            t = JSONUtil.toBean((JSONObject) redisData.getData(), type);
            // 判断是否逻辑过期
            if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
                // 命中且未过期，直接返回新数据
                return t;
            }
            // 获取锁成功，开启一个独立子线程去重建缓存
            CACHE_REBUILD_EXECUTOR.submit(()->{
                //重建缓存
                try {
                    // 查询数据库
                    T t1 = dbFallback.apply(id);
                    // 写入redis
                    this.setWithLogicalExpire(cacheKey, t1, time, unit);
                } catch (Exception e) {
                    log.error("缓存重建异常", e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        // 获取锁失败，直接返回过期的旧数据
        return t;
    }

}
