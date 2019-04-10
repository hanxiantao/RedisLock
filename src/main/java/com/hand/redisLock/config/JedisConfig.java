package com.hand.redisLock.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * @description:
 * @version: 1.0
 * @author: xiantao.han@hand-china.com
 * @Date: 2019/4/9
 */
@Configuration
public class JedisConfig {
    @Value("${jedisPool.host}")
    private String host;

    @Value("${jedisPool.port}")
    private Integer port;

    @Bean
    public Jedis jedis() {
        JedisPool jedisPool = new JedisPool(host, port);
        Jedis jedis = jedisPool.getResource();
        return jedis;
    }
}
