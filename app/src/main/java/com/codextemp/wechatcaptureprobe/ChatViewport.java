package com.codextemp.wechatcaptureprobe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class ChatViewport {
    static final class Bounds {
        final int left;
        final int top;
        final int right;
        final int bottom;

        Bounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        boolean intersects(int left, int top, int right, int bottom) {
            return this.left < right && this.right > left && this.top < bottom && this.bottom > top;
        }

        boolean same(Bounds other) {
            return other != null && left == other.left && top == other.top && right == other.right && bottom == other.bottom;
        }
    }

    final Bounds bounds;
    private final ScreenGeometry screen;
    /** 外部遮挡（其他应用窗口）：数量参与 CaptureCoverage 与范围校验语义，且参与气泡剔除。 */
    final List<Bounds> masks;
    /** 自有安全窗口（意图卡/建议面板）：只做像素遮蔽，不参与遮挡计数与气泡剔除——
     *  它们是助手自己的渲染内容，计入遮挡会让分析时与校验时的窗口数不一致（面板出现即差 2）。 */
    private final List<Bounds> selfMasks;
    /** masks + selfMasks 排序合并；所有像素级遮蔽（detect 采样、OCR 复制、指纹）统一使用。 */
    private final List<Bounds> maskSegments;

    private ChatViewport(ScreenGeometry screen, Bounds bounds, List<Bounds> masks, List<Bounds> selfMasks) {
        this.screen = screen;
        this.bounds = bounds;
        this.masks = Collections.unmodifiableList(masks);
        this.selfMasks = Collections.unmodifiableList(selfMasks);
        List<Bounds> combined = new ArrayList<>(masks);
        combined.addAll(selfMasks);
        combined.sort(Comparator.comparingInt((Bounds value) -> value.left).thenComparingInt(value -> value.top)
                .thenComparingInt(value -> value.right).thenComparingInt(value -> value.bottom));
        this.maskSegments = Collections.unmodifiableList(combined);
    }

    static ChatViewport create(ScreenGeometry screen, Bounds application, int keyboardTop, int keyboardLeft,
                               int keyboardRight, List<Bounds> occlusions) {
        if (screen == null || !screen.supported() || application == null) {
            return null;
        }
        int left = Math.max(screen.leftInset, application.left);
        int top = Math.max(screen.topInset, application.top);
        int right = Math.min(screen.width - screen.rightInset, application.right);
        int bottom = Math.min(screen.height - screen.bottomInset, application.bottom);
        if (keyboardTop >= 0 && keyboardTop < bottom && keyboardRight > left && keyboardLeft < right) {
            if (keyboardLeft > left + screen.dp(8) || keyboardRight < right - screen.dp(8)) {
                return null;
            }
            bottom = keyboardTop;
        }
        if (right - left < screen.dp(180) || bottom - top < screen.dp(200)) {
            return null;
        }
        List<Bounds> masks = new ArrayList<>();
        for (Bounds occlusion : occlusions) {
            int maskLeft = Math.max(left, occlusion.left);
            int maskTop = Math.max(top, occlusion.top);
            int maskRight = Math.min(right, occlusion.right);
            int maskBottom = Math.min(bottom, occlusion.bottom);
            if (maskLeft < maskRight && maskTop < maskBottom) {
                if (maskTop < top + screen.dp(48) || maskBottom > bottom - screen.dp(56)) {
                    return null;
                }
                masks.add(new Bounds(maskLeft - left, maskTop - top, maskRight - left, maskBottom - top));
            }
        }
        masks.sort(Comparator.comparingInt((Bounds value) -> value.left).thenComparingInt(value -> value.top)
                .thenComparingInt(value -> value.right).thenComparingInt(value -> value.bottom));
        return new ChatViewport(screen, new Bounds(left, top, right, bottom), masks, java.util.Collections.emptyList());
    }

    /** 把自有安全窗口覆盖区加入局部快照；标题/输入带被盖住时仍拒绝识别。 */
    ChatViewport withOverlay(Bounds overlay) {
        if (overlay == null || !overlay.intersects(bounds.left, bounds.top, bounds.right, bounds.bottom)) return this;
        int left = Math.max(bounds.left, overlay.left) - bounds.left;
        int top = Math.max(bounds.top, overlay.top) - bounds.top;
        int right = Math.min(bounds.right, overlay.right) - bounds.left;
        int bottom = Math.min(bounds.bottom, overlay.bottom) - bounds.top;
        if (top < screen.dp(48) || bottom > height() - screen.dp(56)) return null;
        List<Bounds> combined = new ArrayList<>(masks);
        combined.add(new Bounds(left, top, right, bottom));
        combined.sort(Comparator.comparingInt((Bounds value) -> value.left).thenComparingInt(value -> value.top)
                .thenComparingInt(value -> value.right).thenComparingInt(value -> value.bottom));
        return new ChatViewport(screen, bounds, combined, java.util.Collections.emptyList());
    }

    /**
     * 自有安全窗口（意图标注卡/回复建议面板）的实际可见区域并入识别遮蔽。与外部遮挡不同：
     * 这些区域在投影帧里是助手自己的渲染内容，若不遮蔽，面板/卡片上的文字会被 OCR 当成
     * 新聊天消息，触发「面板出现→消息集合变化→结果清空→面板消失→重新分析→面板再出现」
     * 的自指循环，且每轮都烧一次付费模型调用。允许覆盖输入区（贴底兜底形态），因此不做
     * withOverlay 的标题/输入带拒绝；灰区不产生气泡框，真实消息不会因灰区被误判。
     */
    ChatViewport withSelfWindow(Bounds overlay) {
        if (overlay == null || !overlay.intersects(bounds.left, bounds.top, bounds.right, bounds.bottom)) return this;
        int left = Math.max(bounds.left, overlay.left) - bounds.left;
        int top = Math.max(bounds.top, overlay.top) - bounds.top;
        int right = Math.min(bounds.right, overlay.right) - bounds.left;
        int bottom = Math.min(bounds.bottom, overlay.bottom) - bounds.top;
        if (left >= right || top >= bottom) return this;
        List<Bounds> combined = new ArrayList<>(selfMasks);
        combined.add(new Bounds(left, top, right, bottom));
        combined.sort(Comparator.comparingInt((Bounds value) -> value.left).thenComparingInt(value -> value.top)
                .thenComparingInt(value -> value.right).thenComparingInt(value -> value.bottom));
        return new ChatViewport(screen, bounds, masks, combined);
    }

    int width() {
        return bounds.right - bounds.left;
    }

    boolean matchesScreen(ScreenGeometry geometry) { return screen.same(geometry); }

    int height() {
        return bounds.bottom - bounds.top;
    }

    boolean same(ChatViewport other) {
        if (other == null || !screen.same(other.screen) || !bounds.same(other.bounds) || masks.size() != other.masks.size()) {
            return false;
        }
        for (int index = 0; index < masks.size(); index++) {
            if (!masks.get(index).same(other.masks.get(index))) {
                return false;
            }
        }
        return true;
    }

    /** 像素级遮蔽判定：外部遮挡 + 自有安全窗口统一灰化。 */
    boolean masked(int horizontal, int vertical) {
        for (int index = 0; index < maskSegments.size(); index++) {
            Bounds mask = maskSegments.get(index);
            if (mask.intersects(horizontal, vertical, horizontal + 1, vertical + 1)) {
                return true;
            }
        }
        return false;
    }

    /** 像素复制用的遮蔽段（已按左/上排序）；供 MaskedRgbaWords/Rows 逐行跳过灰区。 */
    List<Bounds> maskSegments() {
        return maskSegments;
    }

    /** 气泡剔除只看外部遮挡：自有窗口不允许剔除真实气泡（灰区本身不产生气泡框，
     *  而窗口动画期把真气泡整条剔除会再次造成消息集合振荡）。 */
    boolean occludes(ChatLayout.Box box) {
        int padding = screen.dp(8);
        for (Bounds mask : masks) {
            if (mask.intersects(box.left - padding, box.top - padding, box.right + padding, box.bottom + padding)) {
                return true;
            }
        }
        return false;
    }

    ScreenGeometry overlayGeometry() {
        return new ScreenGeometry(screen.width, screen.height, screen.densityDpi, screen.rotation,
                bounds.left, bounds.top, screen.width - bounds.right, screen.height - bounds.bottom, true);
    }
}
