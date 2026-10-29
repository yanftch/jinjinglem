package com.duomaomao.api.service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 极简 TTL 缓存：够用即可，不引入 Caffeine 依赖。
 * key 数量很小（labels 2 个、route 若干、comment 若干），不需要复杂淘汰。
 */
public final class TtlCache<V> {

    private record Entry<V>(V value, long expiresAtNanos) {
    }

    private final Map<String, Entry<V>> store = new ConcurrentHashMap<>();
    private final int maxEntries;

    public TtlCache() {
        this(512);
    }

    public TtlCache(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be > 0");
        }
        this.maxEntries = maxEntries;
    }

    public V get(String key) {
        Entry<V> entry = store.get(key);
        if (entry == null) {
            return null;
        }
        if (System.nanoTime() - entry.expiresAtNanos() >= 0) {
            store.remove(key, entry);
            return null;
        }
        return entry.value();
    }

    public void put(String key, V value, Duration ttl) {
        if (value == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        if (store.size() >= maxEntries) {
            evictExpired();
            if (store.size() >= maxEntries) {
                store.clear();
            }
        }
        store.put(key, new Entry<>(value, System.nanoTime() + ttl.toNanos()));
    }

    public void remove(String key) {
        store.remove(key);
    }

    public void clear() {
        store.clear();
    }

    public int size() {
        return store.size();
    }

    private void evictExpired() {
        long now = System.nanoTime();
        store.entrySet().removeIf(e -> now - e.getValue().expiresAtNanos() >= 0);
    }
}
