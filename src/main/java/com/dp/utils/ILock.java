package com.dp.utils;

public interface ILock {

    /**
     * 尝试获取锁
     * @param timeoutSec
     * @return true成功，false失败
     */
    boolean isLocked(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
