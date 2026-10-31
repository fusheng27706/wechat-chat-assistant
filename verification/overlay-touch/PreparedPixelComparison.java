package com.codextemp.wechatcaptureprobe;

import java.util.Arrays;

/** 测试线程重放生产准备函数；不调用SDK、不授予来源许可，不留存Bitmap/像素。 */
final class PreparedPixelComparison {
    static String compare(int[] before, int[] after, int width, int height, boolean outgoing, float density) {
        if (!Float.isFinite(density) || density <= 0) throw new IllegalArgumentException("prepared-density");
        // 检测采样网格相对于box起点；统一局部原点不会改变准备图和局部图形区域。
        ChatLayout.Box box = new ChatLayout.Box(0, 0, width, height, outgoing);
        try (BubblePreparation.Result left = BubblePreparation.create(before, box, density);
             BubblePreparation.Result right = BubblePreparation.create(after, box, density)) {
            int w = left.bitmap.getWidth(), h = left.bitmap.getHeight();
            if (right.bitmap.getWidth() != w || right.bitmap.getHeight() != h)
                throw new IllegalStateException("prepared-shape");
            int[] a = new int[w * h], b = null;
            try {
                b = new int[w * h];
                left.bitmap.getPixels(a, 0, w, 0, 0, w, h);
                right.bitmap.getPixels(b, 0, w, 0, 0, w, h);
                long changed = 0;
                for (int i = 0; i < a.length; i++) if (a[i] != b[i]) changed++;
                return changed + "/" + a.length + "/" + left.graphics.equals(right.graphics)
                        + "/" + (left.visual == right.visual);
            } finally {
                Arrays.fill(a, 0);
                if (b != null) Arrays.fill(b, 0);
            }
        }
    }
}
