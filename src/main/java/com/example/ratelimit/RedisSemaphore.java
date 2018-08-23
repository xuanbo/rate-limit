package com.example.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.util.Pool;

import java.util.Collections;

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
