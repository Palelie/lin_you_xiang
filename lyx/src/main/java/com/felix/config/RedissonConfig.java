package com.felix.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(){
        Config config = new Config();
        //配置
        config.useSingleServer().setAddress("redis://192.168.200.130:6379")
                .setPassword("531978");
        //创建Redisson对象
        return Redisson.create(config);
    }

}
