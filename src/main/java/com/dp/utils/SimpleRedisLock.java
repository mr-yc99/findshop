package com.dp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;


import java.util.Collections;
import java.util.concurrent.TimeUnit;


public class SimpleRedisLock implements ILock {

    private String name;
    private StringRedisTemplate stringRedisTemplate;


    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String LOCK_PREFIX = "lock:";
    // 线程标识：uuid-线程id
    private static final String THREAD_ID_PREFIX = UUID.randomUUID().toString(true) + "-"; // toString(true): 把横线都去掉
    // 类加载时就加载脚本
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean isLocked(long timeoutSec) {
        String key = LOCK_PREFIX + name;

        // 线程标识
        String threadId = THREAD_ID_PREFIX + Thread.currentThread().getId();

        //获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, threadId, timeoutSec, TimeUnit.SECONDS);

        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        String key = LOCK_PREFIX + name;
        // 调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(key),
                THREAD_ID_PREFIX + Thread.currentThread().getId()
        );
    }

/*    @Override
    public void unlock() {
        // 获取当前线程标识
        String threadId = THREAD_ID_PREFIX + Thread.currentThread().getId();

        // 获取redis中的标识
        String id = stringRedisTemplate.opsForValue().get(LOCK_PREFIX + name);

        // 判断redis中的标识和当前线程标识是否一致
        if(id.equals(threadId)) {
            // 释放锁
            stringRedisTemplate.delete(LOCK_PREFIX + name);
        }
        // 不一致就不管
    }*/
}