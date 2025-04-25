package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;

//继承自MyBatis-Plus提供的ServiceImpl类
//泛型参数说明：
//VoucherOrderMapper: 订单数据访问层接口
//VoucherOrder: 订单实体类
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
//        1.执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString());
//        2.判断结果是否为0
        int r = result.intValue();
        if (r != 0) {
            //        结果不为0，返回异常信息,没有购买资格
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }
//           3.为0，有购买资格，将优惠券id、用户id和订单id存入阻塞队列中
        long orderId = redisIdWorker.nextId("order");
        //TODO 保存阻塞队列
        //4.返回订单id
        return Result.ok(orderId);
    }

    /*@Override
    public Result seckillVoucher(Long voucherId) {
//        1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始！");
        }
//        3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }
//        4.判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足!");
        }
        Long userId = UserHolder.getUser().getId();
//        创建锁对象
//        SimpleRedisLock lock = new SimpleRedisLock("lock:order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        获取锁
        boolean isLock = lock.tryLock();
//        判断是否获取锁成功
        if (!isLock){
            //        获取锁失败，返回错误
            return Result.fail("一人只可下单一次，请勿重复下单！");
        }
        //获取代理对象（事务），避免由于事务未提交就释放锁的安全问题
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }
//        7.返回订单id
    }*/

    @Transactional(rollbackFor = {Exception.class})
    public Result createVoucherOrder(Long voucherId){
        //5.实现一人一单（悲观锁解决一人一单查询问题，乐观锁解决数据库更新问题）
        Long userId = UserHolder.getUser().getId();
//        5.1查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//        5.2判断是否存在
        if (count > 0){
            return Result.fail("用户已经购买过一次，请勿重新购买！");
        }

//            6.扣除库存(利用乐观锁的CAS法解决超卖问题）
        boolean success = seckillVoucherService.update()//set stock = stock - 1
                .setSql("stock = stock -1").eq("voucher_id",voucherId).gt("stock",0)//where voucher_id = ? amd stock > 0
                .update();
        if (!success){
            return Result.fail("库存不足!");
        }
        //        7.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setUserId(userId);
//        7.1订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
//        7.2用户id

//        7.3代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        return Result.ok(orderId);
    }
}
