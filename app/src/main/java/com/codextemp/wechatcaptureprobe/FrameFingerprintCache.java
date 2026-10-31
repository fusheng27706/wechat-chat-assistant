package com.codextemp.wechatcaptureprobe;

import java.util.function.LongSupplier;

/** 缓存已取得帧的纯数值扫描；有效性、会话和权限仍由调用方每次实时检查。 */
final class FrameFingerprintCache {
    private Object frame;
    private ChatViewport viewport;
    private long header, body;

    void prepare(Object frame, ChatViewport viewport, LongSupplier headerScan, LongSupplier bodyScan) {
        if (this.frame == frame && this.viewport != null && this.viewport.same(viewport)) return;
        long nextHeader = headerScan.getAsLong();
        long nextBody = bodyScan.getAsLong();
        this.frame = frame;
        this.viewport = viewport;
        header = nextHeader;
        body = nextBody;
    }

    long header() { return header; }
    long body() { return body; }
    void clear() { frame = null; viewport = null; }
}
