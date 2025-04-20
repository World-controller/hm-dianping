package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

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

    @Override
    @Transactional(rollbackFor = {Exception.class})
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
//            5.扣除库存(利用乐观锁的CAS法解决超卖问题）
        boolean success = seckillVoucherService.update()//set stock = stock - 1
                .setSql("stock = stock -1").eq("voucher_id",voucherId).gt("stock",0)//where voucher_id = ? amd stock > 0
                .update();
        if (!success){
            return Result.fail("库存不足!");
        }
//        6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
//        6.1订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
//        6.2用户id
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
//        6.3代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
//        7.返回订单id
        return Result.ok(orderId);


    }
}
