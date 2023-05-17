package com.felix.utils;

public interface ILock {

    /**
     * 尝试获取分布式锁
     * @param timeoutSec 锁持有的超时时间，到期自动释放锁
     * @return true：获取成功 ；false：获取失败
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
