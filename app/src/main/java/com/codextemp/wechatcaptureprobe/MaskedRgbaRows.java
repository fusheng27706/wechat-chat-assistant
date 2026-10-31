package com.codextemp.wechatcaptureprobe;

import java.util.Arrays;

final class MaskedRgbaRows {
    interface Source {
        void copy(int offset, byte[] target, int targetOffset, int length);
    }

    private final ChatViewport viewport;
    private final int stride;
    private final Source source;
    private final byte[] rgba;

    MaskedRgbaRows(ChatViewport viewport, int stride, int limit, Source source) {
        if (viewport == null || source == null || viewport.bounds.left < 0 || viewport.bounds.top < 0
                || viewport.width() <= 0 || viewport.height() <= 0 || stride < (long) viewport.bounds.right * 4
                || ((long) viewport.bounds.bottom - 1) * stride + (long) viewport.bounds.right * 4 > limit) {
            throw new IllegalArgumentException("Invalid cropped RGBA frame");
        }
        this.viewport = viewport;
        this.stride = stride;
        this.source = source;
        rgba = new byte[Math.multiplyExact(viewport.width(), 4)];
    }

    void copyRow(int vertical, int[] target) {
        if (vertical < 0 || vertical >= viewport.height() || target.length != viewport.width()) {
            throw new IllegalArgumentException("Invalid cropped RGBA row");
        }
        Arrays.fill(rgba, (byte) 0x77);
        int cursor = 0;
        java.util.List<ChatViewport.Bounds> segments = viewport.maskSegments();
        for (int index = 0; index < segments.size(); index++) {
            ChatViewport.Bounds mask = segments.get(index);
            if (vertical < mask.top || vertical >= mask.bottom) continue;
            int left = Math.max(0, mask.left);
            int right = Math.min(viewport.width(), mask.right);
            if (left > cursor) copy(cursor, left, vertical);
            cursor = Math.max(cursor, right);
        }
        if (cursor < viewport.width()) copy(cursor, viewport.width(), vertical);
        for (int horizontal = 0; horizontal < target.length; horizontal++) {
            int offset = horizontal * 4;
            target[horizontal] = 0xff000000 | (rgba[offset] & 255) << 16
                    | (rgba[offset + 1] & 255) << 8 | (rgba[offset + 2] & 255);
        }
    }

    private void copy(int left, int right, int vertical) {
        int offset = (viewport.bounds.top + vertical) * stride + (viewport.bounds.left + left) * 4;
        source.copy(offset, rgba, left * 4, (right - left) * 4);
    }
}
