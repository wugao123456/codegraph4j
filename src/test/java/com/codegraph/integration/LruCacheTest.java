package com.codegraph.integration;

import com.codegraph.utils.LruCache;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * LRU 缓存单元测试。
 *
 * <p>覆盖解析器所依赖的淘汰保证：
 * <ul>
 *   <li>容量强制约束（永不超过最大值）</li>
 *   <li>LRU 排序：热点 key 在淘汰轮次中存活</li>
 *   <li>{@code containsKey()/get()/put()/clear()} 行为与标准 Map 一致</li>
 *   <li>{@code null} 值可存储（文件内容缓存使用 null 表示"读取失败"）</li>
 * </ul>
 */
public class LruCacheTest {

    /**
     * 当插入超出容量的新条目时，最久未使用的条目必须被淘汰。
     * 缓存大小不得超出配置的最大值。
     */
    @Test
    public void testEnforcesCapacityByEvictingOldestEntryOnOverflow() {
        LruCache<String, Integer> cache = new LruCache<>(3);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        cache.put("d", 4);

        assertEquals(3, cache.size());
        assertFalse(cache.containsKey("a"));
        assertNull(cache.get("a"));
        assertEquals(Integer.valueOf(2), cache.get("b"));
        assertEquals(Integer.valueOf(3), cache.get("c"));
        assertEquals(Integer.valueOf(4), cache.get("d"));
    }

    /**
     * 通过 {@code get()} 访问条目会将其提升到最新位置，
     * 因此频繁访问的（"热"）key 在淘汰轮次中能够存活。
     */
    @Test
    public void testPromotesTouchedKeysToMostRecentSoTheySurviveEviction() {
        LruCache<String, Integer> cache = new LruCache<>(3);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);

        assertEquals(Integer.valueOf(1), cache.get("a"));

        cache.put("d", 4);

        assertTrue(cache.containsKey("a"));
        assertFalse(cache.containsKey("b"));
        assertTrue(cache.containsKey("c"));
        assertTrue(cache.containsKey("d"));
    }

    /**
     * 覆盖已存在的 key 会更新其值并刷新其访问时间，
     * 但不会增加缓存大小。更新后的 key 应在下一次淘汰中存活。
     */
    @Test
    public void testOverwritingExistingKeyRefreshesRecencyButDoesNotGrowSize() {
        LruCache<String, Integer> cache = new LruCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("a", 99);

        assertEquals(2, cache.size());
        assertEquals(Integer.valueOf(99), cache.get("a"));

        cache.put("c", 3);

        assertTrue(cache.containsKey("a"));
        assertFalse(cache.containsKey("b"));
        assertTrue(cache.containsKey("c"));
    }

    /**
     * 缓存必须支持存储 {@code null} 值。
     * 文件内容缓存使用 null 表示"读取失败"，
     * 因此 null 必须是有效可存储和可检索的值。
     */
    @Test
    public void testStoresNullValuesUsedByFileContentCache() {
        LruCache<String, String> cache = new LruCache<>(2);
        cache.put("missing.ts", null);
        assertTrue(cache.containsKey("missing.ts"));
        assertNull(cache.get("missing.ts"));
    }

    /**
     * {@code clear()} 必须将缓存重置为空状态。
     */
    @Test
    public void testClearResetsTheCache() {
        LruCache<String, Integer> cache = new LruCache<>(3);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.clear();
        assertEquals(0, cache.size());
        assertFalse(cache.containsKey("a"));
    }

    /**
     * 构造函数必须拒绝非正容量值（0 或负数）。
     * 零或负容量对 LRU 缓存没有语义意义。
     */
    @Test
    public void testRejectsNonPositiveCapacity() {
        try {
            new LruCache<>(0);
            fail("Should throw IllegalArgumentException for capacity 0");
        } catch (IllegalArgumentException e) {
            // expected
        }

        try {
            new LruCache<>(-1);
            fail("Should throw IllegalArgumentException for capacity -1");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    /**
     * OOM 场景回归测试：即使在高强度写入（10000 次插入）下，
     * 缓存也必须保持在其配置容量范围内。
     * 只有最近 {@code capacity} 条记录应保留。
     */
    @Test
    public void testStaysBoundedUnderHeavyChurnRegressionForOomScenario() {
        LruCache<String, Integer> cache = new LruCache<>(100);
        for (int i = 0; i < 10_000; i++) {
            cache.put("key" + i, i);
        }
        assertEquals(100, cache.size());
        assertTrue(cache.containsKey("key9999"));
        assertTrue(cache.containsKey("key9900"));
        assertFalse(cache.containsKey("key0"));
    }
}
