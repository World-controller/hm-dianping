package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

/*
* 用于实现逻辑过期的缓存击穿方案
* */

@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
