package com.dp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dp.model.dto.Result;
import com.dp.model.entity.Shop;
import com.dp.mapper.ShopMapper;
import com.dp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dp.utils.CacheClient;
import com.dp.utils.RedisConstants;
import com.dp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.dp.utils.RedisConstants.SHOP_GEO_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author forya
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {

        // 缓存穿透
        //Shop shop = queryWithPassThrough(id);

        //Shop shop = cacheClient.getWithPassThrough(
        //        RedisConstants.CACHE_SHOP_KEY+id, id, Shop.class,
        //        this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 缓存击穿：互斥锁实现
        //Shop shop = queryWithMutex(id);

        // 缓存击穿：逻辑过期实现
        //Shop shop = queryWithLogicalExpire(id);
        Shop shop = cacheClient.getWithLogicalExpire(
                RedisConstants.CACHE_SHOP_KEY,
                id,
                Shop.class,
                this::getById,
                RedisConstants.CACHE_SHOP_TTL,
                TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail("未查询到商铺信息");
        }
        return Result.ok(shop);

    }


    //// 模拟后台：将活动商铺信息（热点key）提前缓存到redis
    ///*public void saveShop2Redis(Long id, Long logicalExpireSeconds) {
    //    String key = RedisConstants.CACHE_SHOP_KEY + id;
    //    // 1.查询店铺
    //    Shop shop = getById(id);
    //    // 2.封装逻辑过期时间和店铺
    //    RedisData redisData = new RedisData();
    //    redisData.setData(shop);
    //    redisData.setExpireTime(LocalDateTime.now().plusSeconds(logicalExpireSeconds)); // 实际应该设置成30分钟
    //
    //    // 3.放到redis
    //    stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    //}*/
    //
    //// 使用逻辑过期解决缓存击穿
    ///*private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    //private Shop queryWithLogicalExpire(Long id) {
    //    String cacheShopKey = RedisConstants.CACHE_SHOP_KEY + id;
    //
    //    // 1.根据id查redis中是否有商铺信息
    //    String shopJson = stringRedisTemplate.opsForValue().get(cacheShopKey);
    //
    //    // 2.未命中
    //    if (StrUtil.isBlank(shopJson)) {
    //        return null;
    //    }
    //
    //    // 3.命中
    //    RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
    //    Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
    //    LocalDateTime expireTime = redisData.getExpireTime();
    //    // 判断是否过期
    //
    //    // 3.1未过期，返回商铺信息
    //    if(expireTime.isAfter(LocalDateTime.now())) {
    //        return shop;
    //    }
    //
    //    // 3.2过期，获取锁---来判断是否自己这个线程来重建缓存，返回旧数据
    //    String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
    //    boolean isLock = tryLock(lockKey);
    //
    //    // 3.2.1 尝试获取互斥锁成功：重建缓存并返回商铺信息
    //    //开启独立线程进行缓存重建（查数据库，设置逻辑过期时间，释放互斥锁）同时返回商铺信息
    //    if(isLock) {
    //        //获取锁成功也要在检查一次redis是否过期
    //        //需要doublecheck 因为你这时候获取到锁 也有一种可能是其他线程做完了重建释放了锁
    //        if(expireTime.isAfter(LocalDateTime.now())) {
    //            return shop;
    //        }
    //        // 推荐使用线程池，性能更好，不用经常创建和销毁
    //        //给线程池添加任务
    //        CACHE_REBUILD_EXECUTOR.submit(() -> {
    //            try {
    //                this.saveShop2Redis(id, 30L);
    //            } catch (Exception e) {
    //                throw new RuntimeException(e);
    //            } finally {
    //                //不管怎样，都要释放锁
    //                unLock(lockKey);
    //            }
    //        });
    //    }
    //    // 3.2.2 尝试获取互斥锁失败：返回商铺信息(成功也在这里返回）
    //    return shop;
    //}*/
    //
    //// 使用互斥锁解决缓存击穿
    ///*private Shop queryWithMutex(Long id) {
    //    String cacheShopKey = RedisConstants.CACHE_SHOP_KEY + id;
    //    // 要手动反序列化
    //    String shopJson = stringRedisTemplate.opsForValue().get(cacheShopKey);
    //
    //    // 命中，有值（不会是空字符串或者null），直接返回
    //    if (StrUtil.isNotBlank(shopJson)) {
    //        // 要作为java对象返回
    //        return JSONUtil.toBean(shopJson, Shop.class);
    //    }
    //
    //    // 命中空值
    //    if (shopJson != null) { // 判断是否为空字符串，等同于shopJson.equals("")
    //
    //        return null;
    //    }
    //
    //    //开始实现缓存重建，解决缓存击穿
    //    String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
    //    //  尝试获取锁
    //    boolean isLock = tryLock(lockKey);
    //    //  获得锁-> 重建缓存
    //    try {
    //        if (!isLock) {
    //            Thread.sleep(RedisConstants.LOCK_SHOP_TTL);
    //            return queryWithMutex(id);
    //
    //        } else {
    //            // 获取锁成功再到缓存里查一次
    //            String doubleCheck = stringRedisTemplate.opsForValue().get(cacheShopKey);
    //            if (StrUtil.isNotBlank(doubleCheck)) {
    //                // 要作为java对象返回
    //                return JSONUtil.toBean(doubleCheck, Shop.class);
    //            }
    //
    //            // 查数据库
    //            Shop shop = this.getById(id);
    //
    //            // 数据库中不存在，返回404
    //            if (shop == null) {
    //                // 通过缓存空对象防止缓存穿透, 使用""代表空
    //                stringRedisTemplate.opsForValue().set(cacheShopKey, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
    //
    //                return null;
    //            } else {
    //                // 数据库中存在，序列化成json，然后存到redis中并返回
    //                String shopJsonStr = JSONUtil.toJsonStr(shop);
    //                stringRedisTemplate.opsForValue().set(cacheShopKey, shopJsonStr, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
    //
    //
    //                return shop;
    //            }
    //        }
    //    } catch (InterruptedException e) {
    //        throw new RuntimeException(e);
    //    } finally {
    //        unLock(lockKey);
    //    }
    //
    //
    //}*/
    //
    ///*缓存穿透
    //private Shop queryWithPassThrough(Long id) {
    //    String cacheShopKey = RedisConstants.CACHE_SHOP_KEY + id;
    //
    //    // 根据id查redis中是否有商铺信息（这里用String存储，之前用了hash，都练一练）
    //    // 要手动反序列化
    //    String shopJson = stringRedisTemplate.opsForValue().get(cacheShopKey);
    //
    //    // 存在，且有值（不会是空字符串或者null），直接返回
    //    if (StrUtil.isNotBlank(shopJson)) {
    //        // 要作为java对象返回
    //        return JSONUtil.toBean(shopJson, Shop.class);
    //    }
    //
    //    // 命中空值
    //    if(shopJson != null) { // 判断是否为空字符串
    //
    //        return null;
    //    }
    //
    //    // 不存在（为null了），从数据库中查
    //    Shop shop = this.getById(id);
    //    // 数据库中不存在，返回404
    //    if(shop == null) {
    //        // 通过缓存空对象防止缓存穿透, 使用""代表空
    //        stringRedisTemplate.opsForValue().set(cacheShopKey, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
    //
    //        return null;
    //    }
    //
    //    // 数据库中存在，序列化成json，然后存到redis中并返回
    //    String shopJsonStr = JSONUtil.toJsonStr(shop);
    //    stringRedisTemplate.opsForValue().set(cacheShopKey, shopJsonStr, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
    //
    //    return shop;
    //}*/
    //
    ///*加锁和释放锁
    //private boolean tryLock(String key) {
    //    // 注意，这里是Boolean，而不是boolean，返回时会拆箱，而拆箱可能会出现NPE（空指针），所以要做一下转换
    //    Boolean absent = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
    //
    //    return BooleanUtil.isTrue(absent);
    //}
    //
    //private void unLock(String key) {
    //
    //    stringRedisTemplate.delete(key);
    //}*/


    // 单体项目，如果是分布式的项目考虑的就更多
    @Override
    @Transactional //中途抛异常，那就回滚
    public Result update(Shop shop) {
        // 校验
        if (shop == null) {
            return Result.fail("未查询到商铺信息");
        }

        // 先更新数据库
        updateById(shop);

        // 再删除缓存
        Long id = shop.getId();
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);

        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1. 判断是否要根据距离查询
        if (x == null || y == null) {
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.用redis做分页查询，按距离排序、分页，结果：shopId， distance
        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults = stringRedisTemplate.opsForGeo()
                .search(
                        SHOP_GEO_KEY + typeId,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end) //默认0到end - 1
                );//默认单位：米

        if (geoResults == null) {
            return Result.ok();
        }

        // 4.解析出id, getContent():取出member和距离
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> geoContent = geoResults.getContent();

        if (geoContent.size() <= from) {
            // 没有下一页
            return Result.ok(Collections.emptyList());
        }

        List<Long> shopIds = new ArrayList<>(geoContent.size());
        Map<String, Distance> distanceMap = new HashMap<>(geoContent.size());
        // 4.1 完成分页
        geoContent.stream().skip(from).forEach(item -> {
            String shopIdStr = item.getContent().getName();
            shopIds.add(Long.valueOf(shopIdStr));

            Distance distance = item.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查店铺, 也要保证有序
        String shopIdsStr = StrUtil.join(",", shopIds);
        List<Shop> shops = query().in("id", shopIds).last("ORDER BY FIELD (id, " + shopIdsStr + ")").list();

        for (Shop shop : shops) {
            String shopId = shop.getId().toString();
            Distance shopDistance = distanceMap.get(shopId);
            shop.setDistance(shopDistance.getValue());
        }

        return Result.ok(shops);

    }
}
