---
--- Generated by EmmyLua(https://github.com/EmmyLua)
--- Created by xyc.
--- DateTime: 2024/4/9 下午9:14
---

-- 参数：优惠券id
local voucherId = ARGV[1]
-- 用户id
local userId = ARGV[2]
-- 订单id
local orderId = ARGV[3]

local stockKey = 'secKill:stock:' .. voucherId -- 拼接用 ..
local orderKey = 'seckill:order:' .. voucherId

if(redis.call('get', stockKey) <= 0) then
    return 1
end

if(tonumber(redis.call('sismember', orderKey, userId)) == 1) then
    return 2
end

redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)

-- 发送消息到队列中
redis.call('xadd', 'stream.orders', '*',
        'userId', userId, 'voucherId', voucherId, 'id', orderId)

return 0






