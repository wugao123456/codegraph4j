package com.codegraph.resolution;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于 LinkedHashMap 的 LRU 缓存实现。
 * 用于限制解析器缓存增长，防止内存溢出。
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
public class LRUCache<K, V> {

    private final int maxSize;
    private final Map<K, V> store;

    public LRUCache(int maxSize) {
        this.maxSize = Math.max(maxSize, 1);
        this.store = new LinkedHashMap<K, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > LRUCache.this.maxSize;
            }
        };
    }

    /**
     * 获取缓存值，若存在则将其移到尾部（标记为最近使用）。
     */
    public V get(K key) {
        return store.get(key);
    }

    /**
     * 设置缓存值。若 key 已存在则更新，若缓存已满则淘汰最久未使用的条目。
     */
    public void put(K key, V value) {
        store.put(key, value);
    }

    /**
     * 检查 key 是否存在。
     */
    public boolean containsKey(K key) {
        return store.containsKey(key);
    }

    /**
     * 清空缓存。
     */
    public void clear() {
        store.clear();
    }

    /**
     * 获取当前缓存大小。
     */
    public int size() {
        return store.size();
    }

    /**
     * 获取最大缓存大小。
     */
    public int getMaxSize() {
        return maxSize;
    }
}
