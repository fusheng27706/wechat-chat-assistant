package com.codextemp.wechatcaptureprobe;

import java.util.Map;
import java.util.TreeMap;

/** 仅测试：输出缓存形状分布，严格丢弃键末尾图像指纹，不保留键或像素。 */
final class CacheShapeSummary {
    static String summarize(Iterable<?> keys) {
        Map<String, Integer> counts = new TreeMap<>();
        int invalid = 0, seen = 0, tokens = 0;
        for (Object value : keys) {
            if (++seen > 32) return "overflow";
            if (!(value instanceof String)) { invalid++; continue; }
            String key = (String) value;
            if (key.startsWith("latin-token:")) { tokens++; continue; }
            int a = key.indexOf(':'), b = key.indexOf(':', a + 1);
            int c = key.indexOf(':', b + 1), d = key.indexOf(':', c + 1);
            if (a < 1 || b <= a + 1 || c <= b + 1 || d <= c + 1) { invalid++; continue; }
            try {
                int width = Integer.parseInt(key.substring(0, a));
                int height = Integer.parseInt(key.substring(a + 1, b));
                int density = Integer.parseInt(key.substring(c + 1, d));
                String role = key.substring(b + 1, c);
                if (width <= 0 || height <= 0 || !(role.equals("true") || role.equals("false"))) {
                    invalid++; continue;
                }
                String shape = width + "x" + height + "r" + (role.equals("true") ? 1 : 0) + "d" + density;
                counts.put(shape, counts.getOrDefault(shape, 0) + 1);
            } catch (NumberFormatException malformed) { invalid++; }
        }
        StringBuilder result = new StringBuilder("invalid").append(invalid);
        if (tokens > 0) result.append(";latinTokens").append(tokens);
        for (Map.Entry<String, Integer> entry : counts.entrySet())
            result.append(';').append(entry.getKey()).append('n').append(entry.getValue());
        return result.toString();
    }
}
