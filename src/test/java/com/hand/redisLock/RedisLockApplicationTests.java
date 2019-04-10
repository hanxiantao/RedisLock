package com.hand.redisLock;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import redis.clients.jedis.Jedis;

@RunWith(SpringRunner.class)
@SpringBootTest
public class RedisLockApplicationTests {

    @Autowired
    private Jedis jedis;

    @Test
    public void redisConnectionTest() {
        String result = jedis.set("test", "hello world");
        System.out.println("result:" + result);
    }

}
