package com.dp;

import com.dp.model.entity.Shop;
import com.dp.service.impl.ShopServiceImpl;
import com.dp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static com.dp.utils.RedisConstants.SHOP_GEO_KEY;

@RunWith(SpringRunner.class)
@SpringBootTest
class FindShopApplicationTests {
    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private ExecutorService executorService = Executors.newFixedThreadPool(500);

    @Test
    public void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        //【Java 8 新特性】Java 8 Runnable和Callable使用Lambda表达式示例(带参数)
        // https://blog.csdn.net/qq_31635851/article/details/116986062
        Runnable run = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println(id);
            }

            latch.countDown();
        };


        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            executorService.submit(run);
        }

        latch.await();
        long end = System.currentTimeMillis();

        System.out.println(end-begin);
    }

    @Test
    public void loadShopData() {
        List<Shop> list = shopService.list();

        // 按照typeId分组
        // map: typeId, shop
        HashMap<Long, List<Shop>> hashMap = new HashMap<>();

        Map<Long, List<Shop>> collect = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));

        for (Map.Entry<Long, List<Shop>> entry : collect.entrySet()) {
            Long typeId = entry.getKey();
            List<Shop> shopList = entry.getValue();

            List<RedisGeoCommands.GeoLocation<String>> geoLocations = new ArrayList<>(shopList.size());
            for (Shop shop : shopList) {
                // 写入redis
                //stringRedisTemplate.opsForGeo().add(SHOP_GEO_KEY + typeId, new Point(shop.getX(), shop.getY()), shop.getId() + "");
                geoLocations.add(
                        new RedisGeoCommands.GeoLocation<>(
                                shop.getId().toString(),
                                new Point(shop.getX(), shop.getY()))
                );
            }
            stringRedisTemplate.opsForGeo().add(SHOP_GEO_KEY + typeId, geoLocations);

        }

    }




}
