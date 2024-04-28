package com.dp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient redissonClient() {
        // 配置
        Config config = new Config();

        // 配置
        config.useSingleServer().setAddress("redis://192.168.49.129:6379").setPassword("123456");

        return Redisson.create(config);
    }
}
