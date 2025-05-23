package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";//登录验证码
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";//登录用户
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long SHOP_TTL = 30L;
    public static final String SHOP_KEY = "shop:";
    public static final String SHOP_TYPE_KEY = "shopType";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";

    public static final String BLOG_LIKED_KEY = "blog:liked:";

    public static final String FEED_KEY = "feed:";

    public static final String SHOP_GEO_KEY = "shop:geo:";

    public static final String USER_SIGN_KEY = "sign:";

}
