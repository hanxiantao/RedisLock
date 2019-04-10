**使用Jedis实现Redis客户端，且只考虑Redis服务端单机部署的场景** 

## 一、可靠性

为了确保分布式锁可用，锁的实现至少要同时满足以下三个条件：

- 互斥性。在任意时刻，只有一个客户端能持有锁
- 不会发生死锁。即使有一个客户端在持有锁的期间崩溃而没有主动解锁，也能保证后续其他客户端能加锁。
- 加锁和解锁必须是同一个客户端，客户端自己不能把别人加的锁给解了。



## 二、依赖和配置信息

1）、引入依赖

```xml
        <dependency>
            <groupId>redis.clients</groupId>
            <artifactId>jedis</artifactId>
            <version>2.9.0</version>
        </dependency>
```

2）、相关配置

application.properties

```properties
#redis连接信息
jedisPool.host=127.0.0.1
jedisPool.port=6379
```

配置类

```java
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
```



## 三、加锁的实现

```java
public class RedisLock {
    private static final String LOCK_SUCCESS = "OK";
    private static final String SET_IF_NOT_EXIST = "NX";
    private static final String SET_WITH_EXPIRE_TIME = "PX";

    /**
     * 尝试获取分布式锁
     *
     * @param jedis      Redis客户端
     * @param lockKey    锁标识
     * @param requestId  请求标识
     * @param expireTime 超期时间(秒)
     * @return 是否获取成功
     */
    public static boolean getLock(Jedis jedis, String lockKey, String requestId, int expireTime) {
        String result = jedis.set(lockKey, requestId, SET_IF_NOT_EXIST, SET_WITH_EXPIRE_TIME, expireTime * 1000);
        if (LOCK_SUCCESS.equals(result)) {
            return true;
        }
        return false;
    }
```

加锁过程中主要使用的redis命令`set key value nx px expireTime`，当key不存在或者已经过期时，进行set操作，返回OK；当key存在时，不做任何操作

对应的Java代码为：jedis.set(lockKey, requestId, SET_IF_NOT_EXIST, SET_WITH_EXPIRE_TIME, expireTime * 1000)

- key：传入锁标识
- value：传入的是requestId，目的是为了实现加锁和解锁必须是同一个客户端，客户端自己不能把别人加的锁给解了，requestId可以使用`UUID.randomUUID().toString()`方法生成
- nxxx：NX
- expx：PX
- time：key的过期时间

setnx保证了如果已有key存在，则函数不会调用成功，只有一个客户端能持有锁，满足互斥性

设置了过期时间，即使锁的持有者后续发生崩溃而没有解锁，锁也会因为到了过期时间而自动解锁（即key被删除），不会发生死锁

将value赋值为requestId，代表加锁的客户端请求标识，那么在客户端在解锁的时候就可以进行校验是否是同一个客户端

1）、错误示例1

```java
    public static void getLock(Jedis jedis, String lockKey, String requestId, int expireTime) {
        Long result = jedis.setnx(lockKey, requestId);
        if (result == 1) {
            // 若在这里程序突然崩溃，则无法设置过期时间，将发生死锁         jedis.expire(lockKey, expireTime);
        }
    }
```

实现思路：使用`jedis.setnx()`和`jedis.expire()`组合实现加锁

存在的问题：通过两条Redis命令，不具有原子性，如果程序在执行完`jedis.setnx()`之后突然崩溃，导致锁没有设置过期时间，那么将会发生死锁（低版本的jedis并不支持多参数的set()方法）

2）、错误示例2

```java
    public static boolean getLock(Jedis jedis, String lockKey, int expireTime) {
        long expires = System.currentTimeMillis() + expireTime;
        String expiresStr = String.valueOf(expires);
        // 如果当前锁不存在，返回加锁成功
        if (jedis.setnx(lockKey, expiresStr) == 1) {
            return true;
        }
        // 如果锁存在，获取锁的过期时间
        String currentValueStr = jedis.get(lockKey);
        if (currentValueStr != null && Long.parseLong(currentValueStr) < System.currentTimeMillis()) {
            // 锁已过期，获取上一个锁的过期时间，并设置现在锁的过期时间
            String oldValueStr = jedis.getSet(lockKey, expiresStr);
            if (oldValueStr != null && oldValueStr.equals(currentValueStr)) {
                // 考虑多线程并发的情况，只有一个线程的设置值和当前值相同，它才有权利加锁
                return true;
            }
        }
        // 其他情况，一律返回加锁失败
        return false;
    }
```

实现思路：使用`jedis.setnx()`命令实现加锁，其中key是锁，value是锁的过期时间。通过`setnx()`方法尝试加锁，如果当前锁不存在，返回加锁成功。 如果锁已经存在则获取锁的过期时间，和当前时间比较，如果锁已经过期，则设置新的过期时间，返回加锁成功

存在的问题： 

- 由于是客户端自己生成过期时间，所以需要强制要求分布式下每个客户端的时间必须同步
- 当锁过期的时候，如果多个客户端同时执行`jedis.getSet()`方法，那么虽然最终只有一个客户端可以加锁，但是这个客户端的锁的过期时间可能被其他客户端覆盖
- 锁不具备拥有者标识，即任何客户端都可以解锁



## 四、解锁的实现

```java
    private static final Long RELEASE_SUCCESS = 1L;

    /**
     * 释放分布式锁
     *
     * @param jedis     Redis客户端
     * @param lockKey   锁标识
     * @param requestId 请求标识
     * @return 是否释放成功
     */
    public static boolean releaseLock(Jedis jedis, String lockKey, String requestId) {
        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
        Object result = jedis.eval(script, Collections.singletonList(lockKey), Collections.singletonList(requestId));
        if (RELEASE_SUCCESS.equals(result)) {
            return true;
        }
        return false;
    }
```

这段Lua代码的功能是首先获取锁对应的value值，检查是否与requestId相等，如果相等则删除锁（解锁）。使用`eval()`方法执行Lua语言来实现可以确保上述操作是原子性的

1）、错误示例

```java
    public static void releaseLock(Jedis jedis, String lockKey, String requestId) {
        // 判断加锁与解锁是不是同一个客户端
        if (requestId.equals(jedis.get(lockKey))) {
            // 若在此时，这把锁突然不是这个客户端的，则会误解锁
            jedis.del(lockKey);
        }
    }
```

存在的问题：如果调用`jedis.del()`方法的时候，这把锁已经不属于当前客户端的时候会解除他人加的锁比如客户端A加锁，一段时间之后客户端A解锁，在执行`jedis.del()`之前，锁突然过期了，此时客户端B尝试加锁成功，然后客户端A再执行`del()`方法，则将客户端B的锁给解除了
