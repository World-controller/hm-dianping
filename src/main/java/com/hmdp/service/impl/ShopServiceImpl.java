package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import org.springframework.boot.json.GsonJsonParser;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
//        1.从Redis查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopCache = stringRedisTemplate.opsForValue().get(key);
//        2.判断是否存在
        if (StrUtil.isNotBlank(shopCache)){
            //        3.存在，直接返回商铺信息
            Shop shop = JSONUtil.toBean(shopCache, Shop.class);
            return Result.ok(shop);
        }
//        4.不存在则根据id查询数据库
        Shop shop = getById(id);
//        5.数据库中商铺不存在返回404
        if(shop == null){
            return Result.fail("404！商铺不存在！");
        }
//        6.如果商铺存在将商铺数据写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop));
//        7.返回商铺信息
        return Result.ok(shop);
    }
}
