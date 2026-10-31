package com.codextemp.wechatcaptureprobe;

import java.util.HashSet;
import java.util.Set;

/** 仅供授权真机诊断；键只暂留内存，输出计数，不能作为来源或命中证明。 */
final class CacheKeySnapshot implements AutoCloseable {
    private final long epoch;
    private final Set<String> keys = new HashSet<>();

    CacheKeySnapshot(long epoch, Iterable<?> source) {
        this.epoch = epoch;
        for (Object key : source) {
            if (!(key instanceof String)) throw new IllegalArgumentException("cache-key-format");
            String text = (String) key;
            int parts = text.split(":", -1).length;
            if (parts != (text.startsWith("latin-token:") ? 6 : 5))
                throw new IllegalArgumentException("cache-key-format");
            keys.add((String) key);
        }
    }

    // 相同尺寸不等于同一条消息；这里只区分新增键是否使用过同一种输入形状。
    String compare(CacheKeySnapshot after) {
        Set<String> shapes = new HashSet<>();
        for (String key : keys) shapes.add(shape(key));
        int retained = 0, sameShapeNewFingerprint = 0, newShape = 0;
        for (String key : after.keys) {
            if (keys.contains(key)) retained++;
            else if (shapes.contains(shape(key))) sameShapeNewFingerprint++;
            else newShape++;
        }
        return "cacheBefore=" + keys.size() + " cacheAfter=" + after.keys.size()
                + " retainedKeys=" + retained + " removedKeys=" + (keys.size() - retained)
                + " sameShapeNewFingerprint=" + sameShapeNewFingerprint + " newShapeKeys=" + newShape
                + " epochChanged=" + (epoch != after.epoch);
    }

    private static String shape(String key) { return key.substring(0, key.lastIndexOf(':')); }

    @Override public void close() { keys.clear(); }
}
