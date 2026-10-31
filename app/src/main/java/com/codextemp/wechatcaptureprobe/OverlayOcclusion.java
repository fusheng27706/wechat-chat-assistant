package com.codextemp.wechatcaptureprobe;

/** 自有窗口的几何版本；只用于排除识别区域，不授予微信来源权限。 */
final class OverlayOcclusion {
    final boolean known;
    final ChatViewport.Bounds bounds;
    final long changedAt;

    OverlayOcclusion(boolean known, ChatViewport.Bounds bounds, long changedAt) {
        this.known = known;
        this.bounds = bounds;
        this.changedAt = changedAt;
    }

    boolean sameShape(boolean known, ChatViewport.Bounds bounds) {
        return this.known == known && (this.bounds == null ? bounds == null : this.bounds.same(bounds));
    }
}
