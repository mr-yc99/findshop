package com.dp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    //构造函数注入
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithTTL(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓
     * @param key
     * @param value
     * @param expireTime
     * @param unit
     */
    public void setWithLogicalExpire(String key, Object value, Long expireTime, TimeUnit unit) {

        RedisData redisData = new RedisData(LocalDateTime.now().plusSeconds(unit.toSeconds(expireTime)), value);

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
     *  Class<R> type---泛型的推断，利用Class类（好像还是一个泛型类）
     *  因为不确定类型，所以就用泛型方法
     *  使用函数式编程，因为数据库查询操作是不确定的，让调用者来查
     * @param id
     * @param type
     * @return
     * @param <R>
     */
    public <R, ID> R getWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> getByDB, Long expireTime, TimeUnit unit) {
        String cacheShopKey = keyPrefix + id;

        // 根据id查redis中是否有商铺信息（这里用String存储，之前用了hash，都练一练）
        // 要手动反序列化
        String json = stringRedisTemplate.opsForValue().get(cacheShopKey);

        // 存在，且有值（不会是空字符串或者null），直接返回
        if (StrUtil.isNotBlank(json)) {
            // 要作为java对象返回
            return JSONUtil.toBean(json, type);
        }

        // 命中空值
        if(json != null) { // 判断是否为空字符串

            return null;
        }

        // 不存在（为null了），从数据库中查，使用函数式编程
        R result = getByDB.apply(id);
        // 数据库中不存在，返回404
        if(result == null) {
            // 通过缓存空对象防止缓存穿透, 使用""代表空
            stringRedisTemplate.opsForValue().set(cacheShopKey, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);

            return null;
        }

        // 数据库中存在，序列化成json，然后存到redis中并返回
        this.setWithTTL(cacheShopKey, result, expireTime, unit);

        return result;
    }


    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    //根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题
    public <R, ID> R getWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> getByDB, Long expireTime, TimeUnit unit) {
        String cacheKey = keyPrefix + id;

        // 1.根据id查redis中是否有商铺信息
        String json = stringRedisTemplate.opsForValue().get(cacheKey);

        // 2.未命中
        if (StrUtil.isBlank(json)) {
            return null;
        }

        // 3.命中，则检查是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R data = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTimeInRedis = redisData.getExpireTime();
        // 判断是否过期

        // 3.1未过期，返回商铺信息
        if(expireTimeInRedis.isAfter(LocalDateTime.now())) {
            return data;
        }

        // 3.2过期，获取锁---来判断是否自己这个线程来重建缓存，返回旧数据
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);

        // 3.2.1 尝试获取互斥锁成功：重建缓存并返回商铺信息
        //开启独立线程进行缓存重建（查数据库，设置逻辑过期时间，释放互斥锁）同时返回商铺信息
        if(isLock) {
            //获取锁成功也要在检查一次redis是否过期
            //需要doublecheck 因为你这时候获取到锁 也有一种可能是其他线程做完了重建释放了锁
            if(expireTimeInRedis.isAfter(LocalDateTime.now())) {
                return data;
            }
            // 推荐使用线程池，性能更好，不用经常创建和销毁
            //给线程池添加任务
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R dataByDB = getByDB.apply(id);
                    this.setWithLogicalExpire(cacheKey, dataByDB, expireTime, unit);
                    //this.saveShop2Redis(id, 30L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //不管怎样，都要释放锁
                    unLock(lockKey);
                }
            });
        }
        // 3.2.2 尝试获取互斥锁失败：返回商铺信息(成功也在这里返回）
        return data;
    }


    /**
     * 加锁, 成功返回true
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        // 注意，这里是Boolean，而不是boolean，返回时会拆箱，而拆箱可能会出现NPE（空指针），所以要做一下转换
        Boolean absent = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);

        return BooleanUtil.isTrue(absent);
    }

    /**
     * 释放锁
     * @param key
     */
    private void unLock(String key) {

        stringRedisTemplate.delete(key);
    }

}
