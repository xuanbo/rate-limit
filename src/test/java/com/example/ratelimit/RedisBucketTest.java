package com.example.ratelimit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;

import java.util.concurrent.CountDownLatch;

/**
 * 测试令牌桶
 *
 * @author xuan
 * @since 1.0.0
 */
public class RedisBucketTest {

    private static final Logger LOG = LoggerFactory.getLogger(SemaphoreTest.class);

    private CountDownLatch latch = new CountDownLatch(200);

    private JedisPool pool;
    private Bucket bucket;

    @Before
    public void setup() {
        pool = new JedisPool();
        // 每秒10个凭据
        bucket = new RedisBucket(pool, 10);
    }

    /**
     * 测试获取凭据
     */
    @Test
    public void tryAcquire() {
        for (int i = 0; i < 200; i++) {
            new Thread(() -> {
                // 尝试获取凭据
                if (bucket.tryAcquire()) {
                    LOG.info("{} - acquired", Thread.currentThread().getName());
                    // 获取到凭据之后，做一些什么
                    doSomething();
                } else {
                    LOG.warn("{} - not acquired", Thread.currentThread().getName());
                }
                latch.countDown();
            }).start();
        }
        try {
            latch.await();
            LOG.info("main await end");
        } catch (InterruptedException e) {
            LOG.error("main await error", e);
        }
    }

    private void doSomething() {
        try {
            Thread.sleep(500L);
        } catch (InterruptedException e) {
            LOG.error("InterruptedException", e);
        }
    }

    @After
    public void cleanup() {
        bucket = null;
        pool.destroy();
    }

}
