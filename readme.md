# rate-limit

在单机中，我们常用的限流方式有Semaphore（Java包中用来限制**并发量**）、RateLimit（Guava中令牌桶实现，用来控制**并发速率**），但是在分布式系统中，就没啥用了，下面介绍基于Redis实现。

## Semaphore

### 原理

例如，我们设置的并发量为100，我们利用string数据结构将凭据设置为100，每个线程获取时减一，说明拿到了。如果不大于0，说明获取不到了。释放凭据时，再加一。

我们很容易写出这样的代码：

```java
public boolean tryAcquire() {
    Jedis jedis = null;
    try {
        jedis = pool.getResource();
        // 获取当前剩余的凭据数
        Long current = Long.valueOf(jedis.get(key));
        if (current > 0) {
            // 凭据数大于0，则获取成功，减一
            jedis.incr(key);
            return true;
        }
        return false;
    } catch (JedisException e) {
        LOG.error("tryAcquire error", e);
        return false;
    } finally {
        returnResource(jedis);
    }
}
```

感觉上这么简单，然而`get(key)`、`incr(key)`不是原子性，因此**不行滴**。

我们可以用lua脚本将`get`、`incr`命令封装下，由于redis单线程，因此可以解决这个问题。

**提示**：`eval`命令。

### 代码

```java
/**
 * 基于redis lua实现Semaphore
 *
 * @author xuan
 * @since 1.0.0
 */
public class RedisSemaphore implements Semaphore {

    private static final Logger LOG = LoggerFactory.getLogger(Semaphore.class);

    /**
     * redis默认存储的key
     */
    private static final String DEFAULT_KEY = "rateLimit:semaphore";

    /**
     * lua执行脚本，如果大于0，则减一，返回1，代表获取成功
     */
    private static final String SCRIPT_LIMIT =
            "local key = KEYS[1] " +
            "local current = tonumber(redis.call('get', key)) " +
            "local res = 0 " +
            "if current > 0 then " +
            "   redis.call('decr', key) " +
            "   res = 1 " +
            "end " +
            "return res ";

    /**
     * Redis连接池
     */
    private final Pool<Jedis> pool;

    /**
     * redis存储的key
     */
    private final String key;

    /**
     * 凭据限制的数目
     */
    private final Long limits;

    public RedisSemaphore(Pool<Jedis> pool, Long limits) {
        this(pool, DEFAULT_KEY, limits);
    }

    public RedisSemaphore(Pool<Jedis> pool, String key, Long limits) {
        this.pool = pool;
        this.key = key;
        this.limits = limits;
        setup();
    }

    /**
     * 尝试获取凭据，获取不到凭据不等待，直接返回
     *
     * @return 获取到凭据返回true，否则返回false
     */
    @Override
    public boolean tryAcquire() {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            Long res = (Long) jedis.eval(SCRIPT_LIMIT, Collections.singletonList(key), Collections.<String>emptyList());
            return res > 0;
        } catch (JedisException e) {
            LOG.error("tryAcquire error", e);
            return false;
        } finally {
            returnResource(jedis);
        }
    }

    /**
     * 释放获取到的凭据
     */
    @Override
    public void release() {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            jedis.incr(key);
        } catch (JedisException e) {
            LOG.error("release error", e);
        } finally {
            returnResource(jedis);
        }
    }

    private void setup() {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            jedis.del(key);
            jedis.incrBy(key, limits);
        } finally {
            returnResource(jedis);
        }
    }

    private void returnResource(Jedis jedis) {
        if (jedis != null) {
            jedis.close();
        }
    }

}
```

emm，代码比较简单，没有实现获取不到锁，然后等待那种操作。这里直接是快速失败了，让业务逻辑去处理。

## RateLimit

Semaphore控制了并发量，但是没有控制速率，就是那种每秒最多给你多少张凭据，获取完了等待下一秒。。

### 原理

[令牌桶算法](https://baike.baidu.com/item/%E4%BB%A4%E7%89%8C%E6%A1%B6%E7%AE%97%E6%B3%95/6597000)

emm，实现起来还是挺复杂的。发现有位道友写的挺棒的，就按照他的[代码思路](https://www.cnblogs.com/xuwc/p/9123078.html)实现了。

### 代码

```java
/**
 * 基于redis lua实现令牌桶算法
 * 依赖于本地时间，分布式需要服务器时钟同步
 *
 * @author xuan
 * @since 1.0.0
 */
public class RedisBucket implements Bucket {

    private static final Logger LOG = LoggerFactory.getLogger(RedisBucket.class);

    /**
     * redis默认存储的key
     */
    private static final String DEFAULT_KEY = "rateLimit:bucket:";

    /**
     * lua执行脚本，如果超过限制则返回0，否则返回1
     */
    private static final String SCRIPT_LIMIT =
            "local key = KEYS[1] " +
            "local limit = tonumber(ARGV[1]) " +
            "local current = tonumber(redis.call('get', key) or '0') " +
            "local res " +
            // 如果超出限流大小
            "if current + 1 > limit then " +
            "   res = 0 " +
            // 请求数+1，并设置2秒过期
            "else " +
            "   redis.call('incrBy', key, 1) " +
            "   redis.call('expire', key, 2) " +
            "   res = 1 " +
            "end " +
            "return res ";

    /**
     * Redis连接池
     */
    private final Pool<Jedis> pool;

    /**
     * redis存储的key
     */
    private final String key;

    /**
     * 每秒凭据限制的数目
     */
    private final String permitsPerSecond;

    public RedisBucket(Pool<Jedis> pool, Integer permitsPerSecond) {
        this(pool, DEFAULT_KEY, permitsPerSecond);
    }

    public RedisBucket(Pool<Jedis> pool, String key, Integer permitsPerSecond) {
        this.pool = pool;
        this.key = key;
        this.permitsPerSecond = String.valueOf(permitsPerSecond);
    }

    @Override
    public boolean tryAcquire() {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            // 将当前时间戳取秒数
            String key = this.key + System.currentTimeMillis() / 1000;
            Long res = (Long) jedis.eval(SCRIPT_LIMIT, Collections.singletonList(key), Collections.singletonList(permitsPerSecond));
            return res == 1;
        } catch (JedisException e) {
            LOG.error("tryAcquire error" + e.getMessage(), e);
            return false;
        } finally {
            returnResource(jedis);
        }
    }

    private void returnResource(Jedis jedis) {
        if (jedis != null) {
            jedis.close();
        }
    }
}
```

**由于依赖于本地时间，因此分布式下需要服务器时钟同步，否则。。。**

## 说明

推荐redisson实现的[Semaphore](https://github.com/redisson/redisson/wiki/8.-distributed-locks-and-synchronizers#86-semaphore)