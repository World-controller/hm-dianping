package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

//继承自MyBatis-Plus提供的ServiceImpl类
//泛型参数说明：
//VoucherOrderMapper: 订单数据访问层接口
//VoucherOrder: 订单实体类
@Slf4j
@Service
@SuppressWarnings({"all"})
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

    // 创建一个单线程的线程池，用于异步处理订单
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    @PostConstruct// @PostConstruct表示在构造方法执行后立即执行这个方法
    private void init(){
        // 创建消息队列
        if (Boolean.FALSE.equals(stringRedisTemplate.hasKey("stream.orders"))) {
            stringRedisTemplate.opsForStream().createGroup("stream.orders", ReadOffset.from("0"), "g1");
            log.debug("Stream队列创建成功");
        }
        // 提交订单处理任务到线程池
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";
        @Override
        public void run() {
            // 永久循环，不断从队列中获取订单并处理
            while (true){
                try {
                    // 1.获取消息队列中的订单消息 xreadgroup group g1 c1 count 1 block 2000 streams stream.orders
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
//                    2.判断消息获取是否成功
                    if (list == null || list.isEmpty()){
//                        如果获取失败,说明没有消息,继续下一次循环
                        continue;
                    }
//                    3.解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
//                    4.如果获取成功,可以下单
                    handleVoucherOrder(voucherOrder);
//                    5.ack确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true){
                try {
                    // 1.获取pending-list中的订单消息 xreadgroup group g1 c1 count 1 streams stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
//                    2.判断消息获取是否成功
                    if (list == null || list.isEmpty()){
//                        如果获取失败,说明pending-list没有消息,结束循环
                        break;
                    }
//                    3.解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
//                    4.如果获取成功,可以下单
                    handleVoucherOrder(voucherOrder);
//                    5.ack确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    // 防止处理频繁，下次循环休眠20毫秒
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }

    // 定义一个内部类，实现Runnable接口，用于处理订单任务
    /*private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<VoucherOrder>(1024 * 1024);//使用阻塞队列（orderTasks）存储待处理的订单
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            // 永久循环，不断从队列中获取订单并处理
            while (true){
                try {
                    // 1.从阻塞队列中获取订单信息，如果队列为空会阻塞等待
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.创建订单(逻辑较为复杂，所以创建一个函数实现）
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }
            }
        }
    }*/


    /*前面都已经用lua脚本实现原子操作了，为什么还要加一次锁？   事实上不做获取锁的动作也可以，但是万一redis没有判断成功（几乎没可能），一句话，为了安全，为了兜底，以防万一*/
    //因为是异步处理，所以不需要Result再给前端返回任何东西了
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.获取用户id(此时不可以再利用UserHolder获取了，因为这是新开的线程)
        Long userId = voucherOrder.getUserId();
        //2.// 创建分布式锁，锁的key为 "lock:order:" + 用户ID
        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        3.获取锁
        boolean isLock = lock.tryLock();
//        4.判断是否获取锁成功
        if (!isLock){
            //        获取锁失败，返回错误
            log.error("不允许重复下单");
            return;
        }

        try {
            // 通过代理对象创建订单，以确保事务生效
            proxy.createVoucherOrder(voucherOrder);//通过父线程的成员变量，该子线程直接可以获取到父线程的代理对象
        } finally {
            lock.unlock();
        }

    }
    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
//        获取用户id
        Long userId = UserHolder.getUser().getId();
//        获取订单id
        long orderId = redisIdWorker.nextId("order");
//        1.执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString(),String.valueOf(orderId),"stream.orders");
//        2.判断结果是否为0
        int r = result.intValue();
        if (r != 0) {
            //        2.1结果不为0，返回异常信息,没有购买资格
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }
//        3.获取代理对象以让子线程能够拿到（方法1：放入阻塞队列，方法2：变成主线程的成员变量即可）
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //4.返回订单id
        return Result.ok(orderId);
    }

    /*秒杀业务的优化思路实现*/
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        Long userId = UserHolder.getUser().getId();
////        1.执行lua脚本
//        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString());
////        2.判断结果是否为0
//        int r = result.intValue();
//        if (r != 0) {
//            //        2.1结果不为0，返回异常信息,没有购买资格
//            return Result.fail(r==1?"库存不足":"不能重复下单");
//        }
////           2.2.为0，有购买资格，将优惠券id、用户id和订单id存入阻塞队列中
//        VoucherOrder voucherOrder = new VoucherOrder();
////        2.3订单id
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
////        2.4用户id
//        voucherOrder.setUserId(userId);
////        2.5代金券id
//        voucherOrder.setVoucherId(voucherId);
////        2.6放入阻塞队列
//        orderTasks.add(voucherOrder);//放到阻塞队列以后，提交订单的任务也会异步地通过VoucherOrderHandler（子线程）开启
////        3.获取代理对象以让子线程能够拿到（方法1：放入阻塞队列，方法2：变成主线程的成员变量即可）
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        //4.返回订单id
//        return Result.ok(orderId);
//    }

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
    public void createVoucherOrder(VoucherOrder voucherOrder){
        //5.实现一人一单（悲观锁解决一人一单查询问题，乐观锁解决数据库更新问题）
        Long userId = voucherOrder.getUserId();//不可以Long userId = UserHolder.getUser().getId(); 因为此时是子线程调用了，无法获取父线程的用户的id，所以只能通过订单获取用户id了
//        5.1查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
//        5.2判断是否存在
        if (count > 0){
            log.error("用户已经购买过一次！");//尽管redis通过lua脚本已经实现了判断，但这里再次进行判断为了兜底（事实上redis几乎不可能发生判断错误的问题）
            return ;
        }

//            6.扣除库存(利用乐观锁的CAS法解决超卖问题）
        boolean success = seckillVoucherService.update()//set stock = stock - 1
                .setSql("stock = stock -1").eq("voucher_id", voucherOrder.getVoucherId()).gt("stock",0)//where voucher_id = ? amd stock > 0
                .update();
        if (!success){
            log.error("库存不足！");//尽管redis通过lua脚本已经实现了判断，但这里再次进行判断为了兜底（事实上redis几乎不可能发生判断错误的问题）
            return;
        }
        //        7.创建订单
        save(voucherOrder);
    }
}
