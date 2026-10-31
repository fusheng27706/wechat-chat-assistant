package com.codextemp.wechatcaptureprobe;

import java.util.LinkedHashMap;
import java.util.Map;

/** 只在授权诊断期持有token像素副本；不读取识别文字，输出形状与完整像素差异计数。 */
final class CacheTokenSnapshot implements AutoCloseable {
    private static final String PREFIX = "latin-token:";
    private final CachePixelSnapshot pixels;
    private final String shapes;
    private final int count;
    CacheTokenSnapshot(Map<?, ?> entries) {
        if (entries.size() > 32) throw new IllegalArgumentException("token-entry-limit");
        Map<String, Object> tokens = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : entries.entrySet()) {
            if (entry.getKey() instanceof String && ((String) entry.getKey()).startsWith(PREFIX))
                tokens.put(((String) entry.getKey()).substring(PREFIX.length()), entry.getValue());
        }
        shapes = CacheShapeSummary.summarize(tokens.keySet());
        count = tokens.size();
        pixels = new CachePixelSnapshot(tokens, false);
    }
    String compare(CacheTokenSnapshot after) {
        return "beforeTokens=" + count + " afterTokens=" + after.count + " beforeShapes=" + shapes
                + " afterShapes=" + after.shapes + " " + pixels.compare(after.pixels);
    }
    @Override public void close() { pixels.close(); }
}
