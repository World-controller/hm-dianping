-- 1.参数列表
-- 1.1优惠券的ID
local voucherId = ARGV[1]
-- 1.2用户ID
local userId = ARGV[2]
-- 1.3订单ID
local orderId = ARGV[3]
-- 1.4Stream消息队列名称
local queueName = ARGV[4]
--2.数据key
-- 2.1库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2订单key
local orderKey = 'seckill:order:' .. voucherId

-- 3.脚本业务
-- 3.1判断库存是否充足
local stock = redis.call('get',stockKey)
local stockNumber = tonumber(stock)
if stockNumber <= 0 then
--     3.2库存不足，返回1
    return 1
end

-- 3.2判断用户是否下单
if redis.call('sismember',orderKey,userId) == 1 then
--     3.3存在，说明是重复下单，返回2
    return 2
end
-- 3.4扣库存
redis.call('incrby',stockKey,-1)
-- 3.5下单（保存用户）
redis.call('sadd',orderKey,userId)
-- 3.6发送消息到队列中 XADD stream.orders * k1 v1 k2 v2(为什么是id？  因为与voucherOrder中的属性名保持一致，方便创建订单)
redis.call('xadd',queueName,'*','userId',userId,'voucherId',voucherId,'id',orderId)
return 0