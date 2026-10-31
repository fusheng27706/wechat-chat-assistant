package com.codextemp.wechatcaptureprobe;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** 固定像素检验诊断分类与副本隔离，不代表真实消息相同或OCR可复用。 */
public final class CachePixelSnapshotRegression {
    private static final class Entry {
        final Value value;
        final int[] storage;
        final int length;
        final boolean encoded;
        Entry(int[] storage, int length, boolean encoded) {
            this(storage, length, encoded, "");
        }
        Entry(int[] storage, int length, boolean encoded, String text) {
            this.storage = storage; this.length = length; this.encoded = encoded;
            value = new Value(text);
        }
    }
    private static final class Value {
        final String text;
        Value(String text) { this.text = text; }
    }
    private static int checks;
    private static void require(boolean value) { checks++; if (!value) throw new AssertionError("pixel-check-" + checks); }
    public static void main(String[] args) throws Exception {
        int[] base = new int[100]; Arrays.fill(base, 0xffaaaaaa);
        int[] next = base.clone(); next[0] = 0xffababab; next[55] = 0xff111111; next[99] = 0x00aaaaaa;
        require(Arrays.equals(CachePixelSnapshot.difference(base, next, 10, 10), new long[]{3, 1, 1, 1}));
        Map<String, Object> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
        before.put("10:10:false:1:100", new Entry(new int[]{0xffaaaaaa, 100}, 100, true));
        before.put("10:10:false:1:101", new Entry(new int[100], 100, false));
        after.put("10:10:false:1:200", new Entry(next, 100, false));
        // token甚至不需要诊断可读的value；正文配对必须完全忽略它。
        before.put("latin-token:10:10:false:1:300", new Object());
        after.put("latin-token:10:10:false:1:301", new Object());
        try (CachePixelSnapshot a = new CachePixelSnapshot(before); CachePixelSnapshot b = new CachePixelSnapshot(after)) {
            next[55] = 0xffaaaaaa;
            require(a.compare(b).equals("pixelPairs=1 unmatchedShapes=0 comparedPixels=100 changedPixels=3 interiorChanged=1 smallRgbChanged=1 alphaOnlyChanged=1 shiftedCompared=36 shiftedChanged=1 shiftedPairs=0 exactInteriorPairs=0"));
            require(a.compare(a).contains("pixelPairs=0"));
            java.lang.reflect.Field samples = CachePixelSnapshot.class.getDeclaredField("samples"); samples.setAccessible(true);
            Object sample = ((Map<?, ?>) samples.get(b)).values().iterator().next();
            java.lang.reflect.Field storage = sample.getClass().getDeclaredField("storage"); storage.setAccessible(true);
            int[] owned = (int[]) storage.get(sample);
            b.close(); require(Arrays.stream(owned).allMatch(value -> value == 0));
            require(next[0] == 0xffababab);
            require(a.compare(b).contains("comparedPixels=0"));
        }
        boolean refused = false;
        try { CachePixelSnapshot.difference(new int[1], new int[2], 1, 1); }
        catch (IllegalArgumentException expected) { refused = true; }
        require(refused);
        int[] pattern = new int[100], translated = new int[100];
        for (int i = 0; i < 100; i++) pattern[i] = 0xff000000 | i;
        System.arraycopy(pattern, 10, translated, 0, 90);
        require(Arrays.equals(CachePixelSnapshot.shiftedDifference(pattern, translated, 10, 10), new long[]{36, 0, 1}));
        require(Arrays.equals(CachePixelSnapshot.shiftedDifference(base, base, 10, 10), new long[]{36, 0, 0}));
        require(Arrays.equals(CachePixelSnapshot.shiftedDifference(new int[4], new int[4], 2, 2), new long[]{0, 0, 0}));
        after.clear(); after.put("11:10:false:1:201", new Entry(new int[110], 110, false));
        try (CachePixelSnapshot a = new CachePixelSnapshot(before); CachePixelSnapshot b = new CachePixelSnapshot(after)) {
            require(a.compare(b).contains("unmatchedShapes=1"));
        }
        after.clear(); after.put("10:10:false:1:201", new Entry(new int[]{0, 101}, 100, true));
        refused = false;
        try (CachePixelSnapshot a = new CachePixelSnapshot(before); CachePixelSnapshot b = new CachePixelSnapshot(after)) {
            a.compare(b);
        } catch (IllegalArgumentException expected) { refused = true; }
        require(refused);
        String firstCode = "预算最多8000元，能做吗？", secondCode = "可以先确认必须交付的功能。";
        int[] wrongPixels = new int[100], correctCodePixels = new int[100];
        Arrays.fill(correctCodePixels, 2);
        before.clear(); after.clear();
        before.put("10:10:false:1:10", new Entry(wrongPixels, 100, false, secondCode));
        before.put("10:10:false:1:11", new Entry(correctCodePixels, 100, false, firstCode));
        after.put("10:10:false:1:12", new Entry(wrongPixels, 100, false, firstCode));
        try (CachePixelSnapshot a = new CachePixelSnapshot(before, true); CachePixelSnapshot b = new CachePixelSnapshot(after, true)) {
            require(a.compareFixed(b).contains("fixedPairs=1"));
            require(a.compareFixed(b).contains("T01:100/100/4/36/36/1"));
            require(a.compareFixed(a).contains("fixedPairs=0"));
            int[][] borrowed = new int[2][];
            require(a.compareFixed(b, (oldPixels, newPixels, width, height, outgoing, density) -> {
                borrowed[0] = oldPixels; borrowed[1] = newPixels;
                require(width == 10 && height == 10 && !outgoing);
                return "numeric-only";
            }).contains("preparedDiffs=;T01:numeric-only"));
            require(Arrays.stream(borrowed[0]).allMatch(value -> value == 0)
                    && Arrays.stream(borrowed[1]).allMatch(value -> value == 0));
            boolean callbackFailed = false;
            try { a.compareFixed(b, (oldPixels, newPixels, width, height, outgoing, density) -> {
                borrowed[0] = oldPixels; borrowed[1] = newPixels;
                throw new IllegalStateException("fixed-test");
            }); } catch (IllegalStateException expected) { callbackFailed = true; }
            require(callbackFailed && Arrays.stream(borrowed[0]).allMatch(value -> value == 0)
                    && Arrays.stream(borrowed[1]).allMatch(value -> value == 0));
        }
        after.clear(); after.put("11:10:false:1:12", new Entry(new int[110], 110, false, firstCode));
        try (CachePixelSnapshot a = new CachePixelSnapshot(before, true); CachePixelSnapshot b = new CachePixelSnapshot(after, true)) {
            require(a.compareFixed(b).contains("changedShape=1"));
        }
        after.clear(); after.put("10:10:false:1:12", new Entry(wrongPixels, 100, false, firstCode + secondCode));
        try (CachePixelSnapshot a = new CachePixelSnapshot(before, true); CachePixelSnapshot b = new CachePixelSnapshot(after, true)) {
            require(a.compareFixed(b).contains("unknownCode=1"));
        }
        before.remove("10:10:false:1:11");
        after.clear(); after.put("10:10:false:1:12", new Entry(wrongPixels, 100, false, firstCode));
        try (CachePixelSnapshot a = new CachePixelSnapshot(before, true); CachePixelSnapshot b = new CachePixelSnapshot(after, true)) {
            require(a.compareFixed(b).contains("absentCode=1"));
        }
        System.out.println("PASS cachePixelSnapshotChecks=" + checks + " syntheticOnly=true");
    }
}
