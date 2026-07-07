package com.codegraph.utils;

import java.util.LinkedHashMap;
import java.util.Map;

public class LruCache<K, V> {
    private final LinkedHashMap<K, V> cache;
    private final int maxSize;
    
    public LruCache(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive");
        }
        this.maxSize = maxSize;
        this.cache = new LinkedHashMap<K, V>(maxSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxSize;
            }
        };
    }
    
    public synchronized V get(K key) {
        return cache.get(key);
    }
    
    public synchronized V put(K key, V value) {
        return cache.put(key, value);
    }
    
    public synchronized void remove(K key) {
        cache.remove(key);
    }
    
    public synchronized boolean containsKey(K key) {
        return cache.containsKey(key);
    }
    
    public synchronized int size() {
        return cache.size();
    }
    
    public synchronized void clear() {
        cache.clear();
    }
    
    public synchronized int getMaxSize() {
        return maxSize;
    }
    
    public synchronized boolean isEmpty() {
        return cache.isEmpty();
    }
}