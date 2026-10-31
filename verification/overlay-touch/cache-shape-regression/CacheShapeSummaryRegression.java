package com.codextemp.wechatcaptureprobe;

import java.util.Arrays;
import java.util.Collections;

public final class CacheShapeSummaryRegression {
    public static void main(String[] args) {
        String summary = CacheShapeSummary.summarize(Arrays.asList(
                "40:20:false:123:PRIVATE_A", "40:20:false:123:PRIVATE_B", "41:20:true:123:PRIVATE_C"));
        require(summary.equals("invalid0;40x20r0d123n2;41x20r1d123n1"));
        require(!summary.contains("PRIVATE"));
        require(CacheShapeSummary.summarize(Arrays.asList("SECRET", "1:2:secret:3:x", "a:2:false:3:x", null))
                .equals("invalid4"));
        require(CacheShapeSummary.summarize(Collections.emptyList()).equals("invalid0"));
        require(CacheShapeSummary.summarize(Collections.nCopies(33, "1:2:false:3:x")).equals("overflow"));
        require(CacheShapeSummary.summarize(Arrays.asList("-1:2:false:3:x", "1:0:true:3:x")).equals("invalid2"));
        require(CacheShapeSummary.summarize(Arrays.asList("latin-token:40:20:false:123:PRIVATE_TOKEN", "40:20:false:123:PRIVATE_BODY"))
                .equals("invalid0;latinTokens1;40x20r0d123n1"));
        System.out.println("PASS cacheShapeChecks=7");
    }
    private static void require(boolean value) { if (!value) throw new AssertionError("cache-shape-summary"); }
}
