package com.codextemp.wechatcaptureprobe;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 授权测试内存中的无损像素诊断；只输出计数，不输出键、颜色或图像。 */
final class CachePixelSnapshot implements AutoCloseable {
    /** 诊断回调仅在调用期借用数组；不得保留、修改或将真实像素写出。 */
    interface PreparedComparison {
        String compare(int[] before, int[] after, int width, int height, boolean outgoing, float density);
    }
    private final Map<String, Sample> samples = new LinkedHashMap<>();
    private static final class Sample {
        final int width, height, length;
        final boolean encoded;
        final int[] storage;
        final String fixedCode;
        Sample(String key, Object entry, boolean classifyFixed) throws ReflectiveOperationException {
            String[] parts = key.split(":", -1);
            if (parts.length != 5) throw new IllegalArgumentException("pixel-key-format");
            width = Integer.parseInt(parts[0]); height = Integer.parseInt(parts[1]);
            length = (Integer) field(entry, "length");
            encoded = (Boolean) field(entry, "encoded");
            if (width <= 0 || height <= 0 || (long) width * height != length || length > 1048576)
                throw new IllegalArgumentException("pixel-shape");
            // 仅保留公开固定码，不把缓存正文复制到诊断快照或输出中。
            Set<String> codes = classifyFixed ? ConversationSamples.matchedCodes((String) field(field(entry, "value"), "text"))
                    : java.util.Collections.emptySet();
            fixedCode = codes.size() == 1 ? codes.iterator().next() : "";
            storage = ((int[]) field(entry, "storage")).clone();
        }
        int[] decode() {
            int[] pixels = new int[length];
            try {
                if (!encoded) {
                    if (storage.length != length) throw new IllegalArgumentException("raw-size");
                    System.arraycopy(storage, 0, pixels, 0, length);
                } else {
                    if (storage.length % 2 != 0) throw new IllegalArgumentException("rle-pairs");
                    int at = 0;
                    for (int i = 0; i < storage.length; i += 2) {
                        int count = storage[i + 1];
                        if (count <= 0 || count > length - at) throw new IllegalArgumentException("rle-count");
                        Arrays.fill(pixels, at, at + count, storage[i]); at += count;
                    }
                    if (at != length) throw new IllegalArgumentException("rle-size");
                }
                return pixels;
            } catch (RuntimeException failure) { Arrays.fill(pixels, 0); throw failure; }
        }
    }
    CachePixelSnapshot(Map<?, ?> entries) {
        this(entries, false);
    }
    CachePixelSnapshot(Map<?, ?> entries, boolean classifyFixed) {
        try {
            if (entries.size() > 32) throw new IllegalArgumentException("pixel-entry-limit");
            for (Map.Entry<?, ?> entry : entries.entrySet()) {
                // 此诊断配对整条气泡；英文token不含原气泡角色/固定码，不能混作正文样本。
                if (entry.getKey() instanceof String && ((String) entry.getKey()).startsWith("latin-token:")) continue;
                samples.put((String) entry.getKey(), new Sample((String) entry.getKey(), entry.getValue(), classifyFixed));
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            close(); throw new IllegalArgumentException("pixel-snapshot", failure);
        }
    }
    private static Object field(Object entry, String name) throws ReflectiveOperationException {
        Field field = entry.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(entry);
    }
    private static String shape(String key) { return key.substring(0, key.lastIndexOf(':')); }

    /** 固定码只约束候选，不证明消息身份；多版本取完整像素差最小者，绝不改变正式缓存。 */
    String compareFixed(CachePixelSnapshot after) {
        return compareFixed(after, null);
    }

    String compareFixed(CachePixelSnapshot after, PreparedComparison preparedComparison) {
        StringBuilder result = new StringBuilder();
        StringBuilder prepared = new StringBuilder();
        int pairs = 0, unknown = 0, absent = 0, shapeChanged = 0, multiple = 0;
        for (Map.Entry<String, Sample> entry : after.samples.entrySet()) {
            if (samples.containsKey(entry.getKey())) continue;
            Sample next = entry.getValue();
            if (next.fixedCode.isEmpty()) { unknown++; continue; }
            int codeCandidates = 0, shapeCandidates = 0;
            Sample bestSample = null;
            long[] best = null;
            int[] target = next.decode();
            try {
                for (Map.Entry<String, Sample> prior : samples.entrySet()) {
                    if (!next.fixedCode.equals(prior.getValue().fixedCode)) continue;
                    codeCandidates++;
                    if (!shape(prior.getKey()).equals(shape(entry.getKey()))) continue;
                    shapeCandidates++;
                    int[] source = prior.getValue().decode();
                    try {
                        long[] diff = difference(source, target, next.width, next.height);
                        if (best == null || diff[0] < best[0]) { best = diff; bestSample = prior.getValue(); }
                    } finally { Arrays.fill(source, 0); }
                }
                if (codeCandidates == 0) { absent++; continue; }
                if (best == null) { shapeChanged++; continue; }
                if (shapeCandidates > 1) multiple++;
                int[] source = bestSample.decode();
                long[] shifted;
                try {
                    shifted = shiftedDifference(source, target, next.width, next.height);
                    if (preparedComparison != null) {
                        String[] parts = entry.getKey().split(":", -1);
                        prepared.append(';').append(next.fixedCode).append(':').append(preparedComparison.compare(
                                source, target, next.width, next.height, Boolean.parseBoolean(parts[2]),
                                Float.intBitsToFloat(Integer.parseInt(parts[3]))));
                    }
                }
                finally { Arrays.fill(source, 0); }
                pairs++;
                result.append(';').append(next.fixedCode).append(':').append(best[0]).append('/').append(next.length)
                        .append('/').append(best[1]).append('/').append(shifted[1]).append('/').append(shifted[0])
                        .append('/').append(shapeCandidates);
            } finally { Arrays.fill(target, 0); }
        }
        return "fixedPairs=" + pairs + " unknownCode=" + unknown + " absentCode=" + absent
                + " changedShape=" + shapeChanged + " multipleCandidates=" + multiple + " fixedDiffs=" + result
                + (preparedComparison == null ? "" : " preparedDiffs=" + prepared);
    }

    /** 取相同输入形状中差异最少者，不能据此断言同一条消息，更不能授予缓存命中。 */
    String compare(CachePixelSnapshot after) {
        int pairs = 0, missing = 0;
        long pixels = 0, changed = 0, interior = 0, smallRgb = 0, alphaOnly = 0;
        long shiftedCompared = 0, shiftedChanged = 0;
        int shiftedPairs = 0, exactInteriorPairs = 0;
        for (Map.Entry<String, Sample> entry : after.samples.entrySet()) {
            if (samples.containsKey(entry.getKey())) continue;
            Sample next = entry.getValue(); int[] target = next.decode();
            long[] best = null;
            long[] bestShift = null;
            try {
                for (Map.Entry<String, Sample> prior : samples.entrySet()) {
                    if (!shape(prior.getKey()).equals(shape(entry.getKey()))) continue;
                    int[] source = prior.getValue().decode();
                    try {
                        long[] diff = difference(source, target, next.width, next.height);
                        if (best == null || diff[0] < best[0]) {
                            best = diff;
                            bestShift = shiftedDifference(source, target, next.width, next.height);
                        }
                    } finally { Arrays.fill(source, 0); }
                }
            } finally { Arrays.fill(target, 0); }
            if (best == null) { missing++; continue; }
            pairs++; pixels += next.length; changed += best[0]; interior += best[1];
            smallRgb += best[2]; alphaOnly += best[3];
            shiftedCompared += bestShift[0]; shiftedChanged += bestShift[1];
            if (bestShift[2] != 0) shiftedPairs++;
            if (bestShift[0] > 0 && bestShift[1] == 0) exactInteriorPairs++;
        }
        return "pixelPairs=" + pairs + " unmatchedShapes=" + missing + " comparedPixels=" + pixels
                + " changedPixels=" + changed + " interiorChanged=" + interior
                + " smallRgbChanged=" + smallRgb + " alphaOnlyChanged=" + alphaOnly
                + " shiftedCompared=" + shiftedCompared + " shiftedChanged=" + shiftedChanged
                + " shiftedPairs=" + shiftedPairs + " exactInteriorPairs=" + exactInteriorPairs;
    }
    /** 固定去掉两像素边缘后比较整数平移；省略边缘，仅诊断，绝不能据此复用OCR。 */
    static long[] shiftedDifference(int[] before, int[] after, int width, int height) {
        if (width <= 0 || height <= 0 || (long) width * height != before.length || before.length != after.length)
            throw new IllegalArgumentException("shift-shape");
        if (width <= 4 || height <= 4) return new long[]{0, 0, 0};
        long count = (long) (width - 4) * (height - 4);
        long best = Long.MAX_VALUE; boolean shifted = false;
        // 相同差异数时优先零位移，避免把均匀背景误记为发生平移。
        for (int candidate = 0; candidate <= 25; candidate++) {
            int dx = candidate == 0 ? 0 : (candidate - 1) % 5 - 2;
            int dy = candidate == 0 ? 0 : (candidate - 1) / 5 - 2;
            long changed = 0;
            for (int y = 2; y < height - 2; y++)
                for (int x = 2; x < width - 2; x++)
                    if (before[(y + dy) * width + x + dx] != after[y * width + x]) changed++;
            if (changed < best) { best = changed; shifted = dx != 0 || dy != 0; }
        }
        return new long[]{count, best, shifted ? 1 : 0};
    }
    static long[] difference(int[] before, int[] after, int width, int height) {
        if (width <= 0 || height <= 0 || (long) width * height != before.length || before.length != after.length)
            throw new IllegalArgumentException("difference-shape");
        long[] result = new long[4];
        for (int i = 0; i < before.length; i++) {
            int a = before[i], b = after[i]; if (a == b) continue;
            result[0]++;
            int x = i % width, y = i / width;
            if (x >= 4 && x < width - 4 && y >= 4 && y < height - 4) result[1]++;
            boolean equalAlpha = (a >>> 24) == (b >>> 24);
            if (equalAlpha && Math.abs((a & 255) - (b & 255)) <= 2
                    && Math.abs(((a >>> 8) & 255) - ((b >>> 8) & 255)) <= 2
                    && Math.abs(((a >>> 16) & 255) - ((b >>> 16) & 255)) <= 2) result[2]++;
            if ((a & 0xffffff) == (b & 0xffffff)) result[3]++;
        }
        return result;
    }
    @Override public void close() {
        for (Sample sample : samples.values()) Arrays.fill(sample.storage, 0);
        samples.clear();
    }
}
