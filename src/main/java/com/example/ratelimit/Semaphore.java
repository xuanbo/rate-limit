package com.example.ratelimit;

/**
 * Semaphore
 *
 * @author xuan
 * @since 1.0.0
 */
public interface Semaphore {

    /**
     * 尝试获取凭据，获取不到凭据不等待，直接返回
     *
     * @return 获取到凭据返回true，否则返回false
     */
    boolean tryAcquire();

    /**
     * 释放获取到的凭据
     */
    void release();

}
