package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;//2022.1.1 00：00：00
    /**
     * 序列号的位数
     */
    private static final int COUNT_BITS = 32;

    private final StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /*
    * 分布式系统中生成订单号、流水号，这个方法实现了一个全局唯一ID生成器
    *  ID由两部分组成：              时间戳(31位)：精确到秒，可使用69年          序列号(32位)：每秒最多生成2^32个不同ID
    * */
    public long nextId(String keyPrefix) {//keyPrefix代表业务
        // 1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);//得到当前秒数
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2.生成序列号
        // 2.1.获取当前日期，精确到天 作用：1.避免超过redis单个键的上限2的32次方    2.利于统计每天或者每月或者每年
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2.自增长
        //Redis中的key格式为"icr:业务前缀:年:月:日"
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);//保证不同天下单有着不同的key

        // 3.拼接并返回
        // 将timestamp左移32位，末尾补32个0，然后与count做OR运算
        // 相当于把时间戳和序列号拼接在一起
        return timestamp << COUNT_BITS | count;
    }
}
