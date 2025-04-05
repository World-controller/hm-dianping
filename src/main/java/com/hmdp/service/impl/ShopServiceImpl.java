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
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.json.GsonJsonParser;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
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

//        解决缓存穿透第二步：判断redis缓存中是否命中了空值
        if (shopCache != null) {
            //此时，我们已经知道 shopCache 要么是 null，要么是空字符串或只包含空白字符
            //如果 shopCache != null，那么它一定是空字符串或只包含空白字符，表示之前已经查询过数据库并确认该商铺不存在
//            log.info("此时已经写入redis");
            return Result.fail("店铺信息不存在");
        }

//        4.不存在则根据id查询数据库
        Shop shop = getById(id);
//        5.数据库中商铺不存在返回404  ->解决缓存穿透第一步：将空值写入redis并设置较短的有效期
        if(shop == null){
            stringRedisTemplate.opsForValue().set(key, "",CACHE_NULL_TTL,TimeUnit.MINUTES);
//            log.info("此时还未写入redis");
            return Result.fail("商铺不存在！");
        }
//        6.如果商铺存在将商铺数据写入Redis   (超时剔除策略)
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL,TimeUnit.MINUTES);
//        7.返回商铺信息
        return Result.ok(shop);
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
