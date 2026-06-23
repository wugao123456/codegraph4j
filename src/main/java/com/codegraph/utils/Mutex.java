package com.codegraph.utils;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class Mutex {
    private final ReentrantLock lock;
    
    public Mutex() {
        this.lock = new ReentrantLock();
    }
    
    public Mutex(boolean fair) {
        this.lock = new ReentrantLock(fair);
    }
    
    public void lock() {
        lock.lock();
    }
    
    public boolean tryLock() {
        return lock.tryLock();
    }
    
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        return lock.tryLock(time, unit);
    }
    
    public void unlock() {
        lock.unlock();
    }
    
    public boolean isLocked() {
        return lock.isLocked();
    }
    
    public boolean isHeldByCurrentThread() {
        return lock.isHeldByCurrentThread();
    }
    
    public int getHoldCount() {
        return lock.getHoldCount();
    }
}