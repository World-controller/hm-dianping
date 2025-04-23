package com.hmdp.utils;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import lombok.Getter;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;
public class SimpleRedisLock implements ILock{

    @Getter
    private String name;
    private final StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name,StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();//用UUID区分不同的JVM，因为不同的JVM中线程id可能存在相等的情况
        //获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(success);//防止拆箱过程中出现空指针异常
    }

    @Override
    public void unlock() {
        //获取线程表示
        String threadId = ID_PREFIX + Thread.currentThread().getId();//用UUID区分不同的JVM，因为不同的JVM中线程id可能存在相等的情况
        //获取锁中的标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        //判断标识是否一致
        if (threadId.equals(id)) {
            //释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }

    }

}
