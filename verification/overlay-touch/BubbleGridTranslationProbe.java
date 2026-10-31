package com.codextemp.wechatcaptureprobe;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/** 固定合成气泡平移诊断；用于区分裁剪网格与真实内容变化，不代表真机失配归因。 */
public final class BubbleGridTranslationProbe {
    private static ChatLayout.Pixels scene(int dy) {
        return (x, y) -> {
            int localY = y - dy;
            if (x < 202 || x >= 802 || localY < 300 || localY >= 480) return 0xffededed;
            if (x >= 240 && x < 600 && localY >= 340 && localY < 420 && (x % 20 < 5)) return 0xff111111;
            return 0xffffffff;
        };
    }
    private static int[] crop(ChatLayout.Pixels pixels, ChatLayout.Box box) {
        int width = box.right - box.left, height = box.bottom - box.top;
        int[] result = new int[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            result[y * width + x] = pixels.colorAt(box.left + x, box.top + y);
        return result;
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Method detect = ChatLayout.class.getDeclaredMethod("findBubbles", int.class, int.class, int.class,
                float.class, ChatLayout.Pixels.class, boolean.class);
        detect.setAccessible(true);
        int[] baseline = null;
        int baselineTop = 0;
        for (int dy = 0; dy < 6; dy++) {
            ChatLayout.Pixels pixels = scene(dy);
            List<ChatLayout.Box> boxes = (List<ChatLayout.Box>) detect.invoke(null, 1080, 132, 1900, 2.75f, pixels, false);
            if (boxes.size() != 1) throw new AssertionError("synthetic-single-bubble");
            ChatLayout.Box box = boxes.get(0);
            int[] current = crop(pixels, box);
            if (baseline == null) { baseline = current.clone(); baselineTop = box.top; }
            System.out.println("syntheticGrid dy=" + dy + " width=" + (box.right - box.left)
                    + " height=" + (box.bottom - box.top) + " relativeTopError=" + (box.top - baselineTop - dy)
                    + " exactPixels=" + Arrays.equals(baseline, current));
        }
    }
}
