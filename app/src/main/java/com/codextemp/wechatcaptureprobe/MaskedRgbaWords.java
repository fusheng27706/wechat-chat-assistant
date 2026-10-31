package com.codextemp.wechatcaptureprobe;

import java.util.Arrays;

/** 对齐的RGBA输入按大端整像素读取，未遮挡区段直接写最终ARGB数组；不读取遮挡或行填充。 */
final class MaskedRgbaWords {
    interface Source {
        /** offset为源字节偏移，targetOffset/count为目标像素单位；输入整数须为RRGGBBAA。 */
        void copy(int offset, int[] target, int targetOffset, int count);
    }
    private final ChatViewport viewport;
    private final int stride;
    private final Source source;

    MaskedRgbaWords(ChatViewport viewport, int stride, int limit, Source source) {
        if (viewport == null || source == null || viewport.bounds.left < 0 || viewport.bounds.top < 0
                || viewport.width() <= 0 || viewport.height() <= 0 || stride % 4 != 0
                || stride < (long) viewport.bounds.right * 4
                || ((long) viewport.bounds.bottom - 1) * stride + (long) viewport.bounds.right * 4 > limit)
            throw new IllegalArgumentException("Invalid aligned RGBA frame");
        this.viewport = viewport; this.stride = stride; this.source = source;
    }

    void copyRow(int vertical, int[] target, int targetOffset) {
        int width = viewport.width();
        if (vertical < 0 || vertical >= viewport.height() || targetOffset < 0 || targetOffset > target.length - width)
            throw new IllegalArgumentException("Invalid aligned RGBA row");
        Arrays.fill(target, targetOffset, targetOffset + width, 0xff777777);
        int cursor = 0;
        java.util.List<ChatViewport.Bounds> segments = viewport.maskSegments();
        for (int index = 0; index < segments.size(); index++) {
            ChatViewport.Bounds mask = segments.get(index);
            if (vertical < mask.top || vertical >= mask.bottom) continue;
            int left = Math.max(0, mask.left), right = Math.min(width, mask.right);
            if (left > cursor) copy(cursor, left, vertical, target, targetOffset);
            cursor = Math.max(cursor, right);
        }
        if (cursor < width) copy(cursor, width, vertical, target, targetOffset);
    }

    private void copy(int left, int right, int vertical, int[] target, int targetOffset) {
        int offset = (viewport.bounds.top + vertical) * stride + (viewport.bounds.left + left) * 4;
        source.copy(offset, target, targetOffset + left, right - left);
        // 原路径忽略源alpha并强制不透明；大端字节序使一次无符号移位得到完全相同RGB。
        for (int i = targetOffset + left; i < targetOffset + right; i++) target[i] = 0xff000000 | (target[i] >>> 8);
    }
}
