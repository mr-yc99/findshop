package com.dp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("com.dp.mapper")
@SpringBootApplication
public class FindShopApplication {

    public static void main(String[] args) {
        SpringApplication.run(FindShopApplication.class, args);
    }
}
