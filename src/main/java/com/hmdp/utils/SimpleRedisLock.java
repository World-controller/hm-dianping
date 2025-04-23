package com.hmdp.utils;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import lombok.Getter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
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
    //为什么释放锁要单独拿出来用静态代码块定义？
    //保证即使是多线程环境下也不会因为读写文件次数过多从而降低释放锁的效率
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

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
        //获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();//用UUID区分不同的JVM，因为不同的JVM中线程id可能存在相等的情况
        //调用lua脚本  参数一：脚本  参数二：分布式锁的key  参数三：线程的标识
    //一行代码，保证判断和释放两步操作的原子性，防止多线程情况下某个线程的这两步操作不能连续完成而导致误删问题
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(KEY_PREFIX + name),threadId);
    }

//    @Override
//    public void unlock() {
//        //获取线程表示
//        String threadId = ID_PREFIX + Thread.currentThread().getId();//用UUID区分不同的JVM，因为不同的JVM中线程id可能存在相等的情况
//        //获取锁中的标识
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        //判断标识是否一致
//        if (threadId.equals(id)) {
//            //释放锁
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }

}
