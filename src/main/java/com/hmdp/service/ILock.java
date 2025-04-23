package com.hmdp.service;

public interface ILock {
    public boolean tryLock(long timeoutSec);
    public void unlock();
}
