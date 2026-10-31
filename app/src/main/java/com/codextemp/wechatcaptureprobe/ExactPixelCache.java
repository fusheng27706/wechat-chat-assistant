package com.codextemp.wechatcaptureprobe;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** 单线程纯计算缓存。隔离条目不授予来源许可，调用方只能用已验证的新图查询。 */
final class ExactPixelCache<T> {
    private static final long ACTIVE = Long.MAX_VALUE;
    private static final class Entry<T> {
        final int[] storage;
        final int length;
        final boolean encoded;
        final T value;
        long expires = ACTIVE;
        Entry(int[] pixels, T value, int runs) {
            length = pixels.length;
            this.value = value;
            encoded = runs > 0;
            if (!encoded) { storage = pixels.clone(); return; }
            storage = new int[runs * 2];
            int offset = 0;
            for (int start = 0; start < pixels.length;) {
                int end = start + 1;
                while (end < pixels.length && pixels[end] == pixels[start]) end++;
                storage[offset++] = pixels[start];
                storage[offset++] = end - start;
                start = end;
            }
        }
        /** 无损比较全部ARGB位与长度，不解压分配，也不以相似度代替相等。 */
        boolean matches(int[] pixels) {
            if (pixels.length != length) return false;
            if (!encoded) return Arrays.equals(storage, pixels);
            int at = 0;
            for (int i = 0; i < storage.length; i += 2) {
                int end = at + storage[i + 1];
                while (at < end) if (pixels[at++] != storage[i]) return false;
            }
            return true;
        }
    }
    private final Map<String, Entry<T>> entries = new LinkedHashMap<>(32, 0.75f, true);
    private final int maxPixels, maxEntries;
    private int pixelCount, storedInts;
    private long lastNow = -1;
    /** maxPixels同时限制单条原始像素数与所有条目实际保存的int数；不含对象/文字开销。 */
    ExactPixelCache(int maxPixels, int maxEntries) {
        if (maxPixels < 1 || maxEntries < 1) throw new IllegalArgumentException();
        this.maxPixels = maxPixels; this.maxEntries = maxEntries;
    }
    T get(String key, int[] pixels, long now) {
        prune(now);
        Entry<T> entry = entries.get(key);
        if (entry == null || !entry.matches(pixels)) return null;
        return entry.value;
    }
    /** 当前来源复核通过后提交命中；复核期间淘汰或过期的条目不得复活。 */
    boolean activate(String key, int[] pixels, T expected, long now) {
        prune(now);
        Entry<T> entry = entries.get(key);
        if (entry == null || entry.value != expected || !entry.matches(pixels)) return false;
        entry.expires = ACTIVE;
        return true;
    }
    void put(String key, int[] pixels, T value, long now) {
        prune(now);
        Entry<T> old = entries.remove(key);
        if (old != null) release(old);
        if (pixels.length > maxPixels) return;
        int runs = compressedRuns(pixels);
        int cost = runs == 0 ? pixels.length : runs * 2;
        // 同一4MiB存储预算内保留更多完整结果；单条原始像素上限仍不放宽。
        while (storedInts > maxPixels - cost || entries.size() >= maxEntries) {
            Iterator<Entry<T>> iterator = entries.values().iterator();
            Entry<T> entry = iterator.next(); iterator.remove(); release(entry);
        }
        entries.put(key, new Entry<>(pixels, value, runs));
        pixelCount += pixels.length;
        storedInts += cost;
    }
    /** 不可压缩时提前退出；不会为编码保留比原数组更大的副本。 */
    private static int compressedRuns(int[] pixels) {
        if (pixels.length < 3) return 0;
        int runs = 1;
        for (int i = 1; i < pixels.length; i++) {
            if (pixels[i] != pixels[i - 1] && ++runs * 2L >= pixels.length) return 0;
        }
        return runs;
    }
    /** 首次失效起五秒；反复失效不能给已隔离条目续期。 */
    void quarantine(long now) {
        prune(now);
        long deadline = now > Long.MAX_VALUE - 5000 ? Long.MAX_VALUE - 1 : now + 5000;
        for (Entry<T> entry : entries.values()) if (entry.expires == ACTIVE) entry.expires = deadline;
    }
    void prune(long now) {
        if (now < 0 || now < lastNow) clear();
        lastNow = now;
        Iterator<Entry<T>> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry<T> entry = iterator.next();
            if (entry.expires != ACTIVE && now >= entry.expires) {
                iterator.remove(); release(entry);
            }
        }
    }
    void clear() {
        for (Entry<T> entry : entries.values()) release(entry);
        entries.clear();
    }
    private void release(Entry<T> entry) {
        pixelCount -= entry.length;
        storedInts -= entry.storage.length;
        Arrays.fill(entry.storage, 0);
    }
    /** 只决定是否跳过推测准备，不续期或更新LRU；实际命中仍须get和activate。 */
    boolean mayContain(String key) { return entries.containsKey(key); }
    int size() { return entries.size(); }
    int pixels() { return pixelCount; }
    int storedInts() { return storedInts; }
}
