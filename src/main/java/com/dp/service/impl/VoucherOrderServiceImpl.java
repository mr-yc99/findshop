package com.dp.service.impl;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import cn.hutool.core.bean.BeanUtil;
import com.dp.model.dto.Result;
import com.dp.model.entity.SeckillVoucher;
import com.dp.model.entity.VoucherOrder;
import com.dp.mapper.VoucherOrderMapper;
import com.dp.service.ISeckillVoucherService;
import com.dp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dp.utils.RedisIdWorker;
import com.dp.utils.SimpleRedisLock;
import com.dp.utils.UserHolder;
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

import static com.dp.utils.RedisConstants.STREAM_ORDERS_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author forya
 * @since 2021-12-22
 */

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();


    // 当前类初始化完毕就执行
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while(true) {
                try {
                    // 1. 从消息队列中获取订单信息 XREADGROUP GROUP process1 消费者姓名【yml中配置】 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> streamList = stringRedisTemplate.opsForStream().read(
                            Consumer.from("process1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(STREAM_ORDERS_KEY, ReadOffset.lastConsumed())
                    );

                    // 2. 判读消息是否获取成功
                    if (streamList == null || streamList.isEmpty()) {
                        // 2.1 如果获取失败，说明没有消息-》继续下一次循环
                        continue;
                    }

                    // 2.2 如果获取成功，解析消息-》创建订单
                    MapRecord<String, Object, Object> record = streamList.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

                    handleVoucherOrder(voucherOrder);
                    // 3.ACK确认 SACK stream.orders process1 id
                    stringRedisTemplate.opsForStream().acknowledge(STREAM_ORDERS_KEY, "process1", record.getId());

                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("从阻塞队列中获取订单信息时出现异常：", e);
                    // 抛异常说明没有被ACK确认，需要获取没有处理的消息
                    handlePendingList();
                    //
                    
                }

            }
        }

        private void handlePendingList() {
            while(true) {
                try {
                    // 1. 从pending-list中获取订单信息 XREADGROUP GROUP process1 消费者姓名【yml中配置】 COUNT 1 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> streamList = stringRedisTemplate.opsForStream().read(
                            Consumer.from("process1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(STREAM_ORDERS_KEY, ReadOffset.from("0"))
                    );

                    // 2. 判读消息是否获取成功
                    if (streamList == null || streamList.isEmpty()) {
                        // 2.1 如果获取失败，说明pending-list没有异常消息-》结束循环
                        break;
                    }

                    // 2.2 如果获取成功，解析消息-》创建订单
                    MapRecord<String, Object, Object> record = streamList.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

                    handleVoucherOrder(voucherOrder);
                    // 3.ACK确认 SACK stream.orders process1 id
                    stringRedisTemplate.opsForStream().acknowledge(STREAM_ORDERS_KEY, "process1", record.getId());




                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理pending-list出现异常：", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }

            }





        }
    }
    /*private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    // 内部类
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while(true) {
                try {
                    // 1. 获取订单
                    // 从阻塞队列中获取订单信息，take是阻塞的，没有就等待
                    VoucherOrder voucherOrder = orderTasks.take();

                    // 2.异步创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("从阻塞队列中获取订单信息时出现异常：", e);
                }

            }
        }
    }*/

    private IVoucherOrderService proxy;
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 使用Redisson来创建锁
        RLock lock = redissonClient.getLock("lock:" + "voucherorder:" + userId);

        // 获取锁失败, 无参：不等待，直接返回
        if (!lock.tryLock()) {
           log.error("一人只能下一单");
        }

        try {
            // 获取代理对象来执行方法，否则事务不会被触发，但是这里通过子线程来执行，
            // 而currentProxy()获取代理对象是通过ThreadLocal获取的，子线程不能从父线程取出东西的，要提前获取
            //IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }


    @Override
    public Result secKillVoucherOrder(Long voucherId) {
        // 1.查询
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        LocalDateTime beginTime = seckillVoucher.getBeginTime();
        LocalDateTime endTime = seckillVoucher.getEndTime();
        Integer stock = seckillVoucher.getStock();

        Long userId = UserHolder.getUser().getId();
        //生成订单id
        long orderId = redisIdWorker.nextId("order");

        // 2. 判断是否开始
        if(LocalDateTime.now().isBefore(beginTime)) {
            return Result.fail("未开始");
        }

        // 3.判断是否结束
        if(LocalDateTime.now().isAfter(endTime)) {
            return Result.fail("已结束");
        }


        // 4.执行lua脚本: 判断购买资格-》发送到消息队列
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),String.valueOf(orderId)

        );
        int orderResult = result.intValue();
        // 5.判断结果不为0，没有购买资格
        if(orderResult == 1) {
            return Result.fail("库存不足");
        }else if(orderResult == 2) {
            return Result.fail("请勿重复下单");
        }
/*        // 6.为0，有购买资格，下单信息保存到阻塞队列
        // 5.创建订单
        VoucherOrder newVoucherOrder = new VoucherOrder();

        //  5.1 订单id

        newVoucherOrder.setId(orderId);

        //  5.2 设置下单用户
        newVoucherOrder.setUserId(userId);

        //  5.3 设置优惠券id
        newVoucherOrder.setVoucherId(voucherId);

        // 保存到阻塞队列，这里能用消息队列RabbitMQ
        orderTasks.add(newVoucherOrder);*/







        IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 7.返回订单id
        return Result.ok(orderId);
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();


        // 一人一单
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 判断是否重复下单
        if (count > 0) {
            // 已经购买过了
            log.error("仅限购买一次，请勿重复下单");
            return;
        }

        //  库存充足, 减1
        boolean isUpdate = seckillVoucherService.update().
                setSql("stock = stock - 1").
                eq("voucher_Id", voucherId).gt("stock", 0).update();

        if (!isUpdate) {
            log.error("库存不足");
            return;
        }
        // 创建订单
        save(voucherOrder);
    }

    /*@Override
    public Result secKillVoucherOrder(Long voucherId) {
        // 1.查询
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        LocalDateTime beginTime = seckillVoucher.getBeginTime();
        LocalDateTime endTime = seckillVoucher.getEndTime();
        Integer stock = seckillVoucher.getStock();

        Long userId = UserHolder.getUser().getId();

        // 2. 判断是否开始
        if(LocalDateTime.now().isBefore(beginTime)) {
            return Result.fail("未开始");
        }

        // 3.判断是否结束
        if(LocalDateTime.now().isAfter(endTime)) {
            return Result.fail("已结束");
        }

        // 4. 判断库存
        //  4.1 库存不足
        if(stock <= 0) {
            return Result.fail("已售完");
        }

        //创建锁（新建锁时，对象内部会创建UUID）
        //SimpleRedisLock lock = new SimpleRedisLock("voucherorder:" + userId, stringRedisTemplate);

        // 使用Redisson来创建锁
        RLock lock = redissonClient.getLock("lock:" + "voucherorder:" + userId);

        // 获取锁失败, 无参：不等待，直接返回
        if (!lock.tryLock()) {
            return Result.fail("一人只能下一单");
        }
        
        try {
            // 获取代对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId, userId, stock);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }*/

/*    @Transactional
    public Result createVoucherOrder(Long voucherId, Long userId, Integer stock) {

        // 一人一单
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 判断是否重复下单
        if (count > 0) {
            // 已经购买过了
            return Result.fail("仅限购买一次，请勿重复下单");
        }

        //  4.2 库存充足, 减1
        boolean isUpdate = seckillVoucherService.update().
                set("stock", stock - 1).
                eq("voucher_Id", voucherId).gt("stock", 0).update();

        if (!isUpdate) {
            return Result.fail("库存不足");
        }

        // 5.创建订单
        VoucherOrder newVoucherOrder = new VoucherOrder();

        //  5.1 生成订单id
        long orderId = redisIdWorker.nextId("order");
        newVoucherOrder.setId(orderId);

        //  5.2 设置下单用户
        newVoucherOrder.setUserId(userId);

        //  5.3 设置优惠券id
        newVoucherOrder.setVoucherId(voucherId);

        save(newVoucherOrder);

        return Result.ok(orderId);

    }*/
}
