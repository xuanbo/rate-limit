package com.example.ratelimit;

/**
 * 令牌桶
 *
 * @author xuan
 * @since 1.0.0
 */
public interface Bucket {

    /**
     * 尝试获取凭据，获取不到凭据不等待，直接返回
     *
     * @return 获取到凭据返回true，否则返回false
     */
    boolean tryAcquire();

}
