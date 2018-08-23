package com.example.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.util.Pool;

import java.util.Collections;

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
