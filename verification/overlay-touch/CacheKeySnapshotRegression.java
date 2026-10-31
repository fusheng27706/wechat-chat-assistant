package com.codextemp.wechatcaptureprobe;

import java.util.Arrays;
import java.util.Collections;

/** 固定虚构键验证诊断分类，不能替代真实缓存命中或图像证据。 */
public final class CacheKeySnapshotRegression {
    public static void main(String[] args) {
        try (CacheKeySnapshot before = new CacheKeySnapshot(1, Arrays.asList("20:30:false:1:100", "40:50:true:1:200"));
             CacheKeySnapshot after = new CacheKeySnapshot(1, Arrays.asList("20:30:false:1:100", "20:30:false:1:101", "40:51:true:1:201"));
             CacheKeySnapshot empty = new CacheKeySnapshot(2, Collections.emptyList())) {
            String result = before.compare(after);
            if (!result.equals("cacheBefore=2 cacheAfter=3 retainedKeys=1 removedKeys=1 sameShapeNewFingerprint=1 newShapeKeys=1 epochChanged=false"))
                throw new AssertionError("classification");
            if (!before.compare(empty).equals("cacheBefore=2 cacheAfter=0 retainedKeys=0 removedKeys=2 sameShapeNewFingerprint=0 newShapeKeys=0 epochChanged=true"))
                throw new AssertionError("clear");
            before.close();
            if (!before.compare(empty).startsWith("cacheBefore=0 ")) throw new AssertionError("release");
            if (result.contains("false:1") || result.contains("100") || result.contains("101")) throw new AssertionError("raw-key-output");
        }
        boolean refused = false;
        try (CacheKeySnapshot invalid = new CacheKeySnapshot(0, Collections.singleton("wrong"))) {
            throw new AssertionError("invalid-format-accepted");
        } catch (IllegalArgumentException expected) { refused = true; }
        if (!refused) throw new AssertionError("format");
        System.out.println("PASS cacheKeySnapshotChecks=5 syntheticOnly=true");
    }
}
