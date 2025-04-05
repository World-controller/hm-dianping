package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryAll() {
//        1.从redis缓存中取数据
        List<String> shopTypeListJson = stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE_KEY,0L,9L);
//        2.若存在，则直接返回
        if (shopTypeListJson != null && !shopTypeListJson.isEmpty()) {
            List<ShopType> shopTypeList = shopTypeListJson.stream().map(s -> JSONUtil.toBean(s, ShopType.class)).collect(Collectors.toList());
            return Result.ok(shopTypeList);
        }
//        3.如果不存在，则查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
//        4.数据库中也不存在，则返回资源不存在
        if (typeList.isEmpty()){
            return Result.fail("404! 您访问的资源不存在！");
        }
//        5.将查询结果写入redis缓存
        List<String> shopCollect = typeList.stream().map(JSONUtil::toJsonStr).collect(Collectors.toList());
        stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOP_TYPE_KEY,shopCollect);
//        6.将店铺类型数据进行返回
        return Result.ok(typeList);
    }
}
