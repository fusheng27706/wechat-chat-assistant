package com.codextemp.wechatcaptureprobe;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class MaskedRgbaRowsRegression {
    private static int checks;

    public static void main(String[] arguments) {
        Random random = new Random(589243);
        for (int trial = 0; trial < 160; trial++) {
            ScreenGeometry screen = new ScreenGeometry(1080, 2400, 440, 0, 0, 69, 0, 130, true);
            List<ChatViewport.Bounds> masks = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                int left = random.nextInt(700);
                int top = 400 + random.nextInt(200);
                masks.add(new ChatViewport.Bounds(left, top, left + 20 + random.nextInt(300), top + 10 + random.nextInt(150)));
            }
            ChatViewport viewport = ChatViewport.create(screen, new ChatViewport.Bounds(20, 100, 1020, 1100), -1, 0, 0,
                    trial % 2 == 0 ? masks : List.of());
            require(viewport != null, "approved-viewport");
            int stride = 1080 * 4 + 16;
            int limit = stride * 2400;
            MaskedRgbaRows copier = new MaskedRgbaRows(viewport, stride, limit, (offset, target, targetOffset, length) -> {
                require(offset % 4 == 0 && length % 4 == 0, "aligned-chunks");
                int sourceVertical = offset / stride;
                int sourceHorizontal = offset % stride / 4;
                require(sourceVertical >= viewport.bounds.top && sourceVertical < viewport.bounds.bottom
                        && sourceHorizontal >= viewport.bounds.left && sourceHorizontal + length / 4 <= viewport.bounds.right,
                        "no-other-pane-or-padding-read");
                for (int position = 0; position < length; position += 4) {
                    int horizontal = sourceHorizontal + position / 4 - viewport.bounds.left;
                    int vertical = sourceVertical - viewport.bounds.top;
                    if (viewport.masked(horizontal, vertical)) throw new AssertionError("Masked source bytes were read");
                    target[targetOffset + position] = (byte) (horizontal * 3);
                    target[targetOffset + position + 1] = (byte) (vertical * 5);
                    target[targetOffset + position + 2] = (byte) (horizontal + vertical);
                    target[targetOffset + position + 3] = (byte) random.nextInt(256);
                }
            });
            int[] row = new int[viewport.width()];
            for (int vertical = 0; vertical < viewport.height(); vertical += 13) {
                copier.copyRow(vertical, row);
                for (int horizontal = 0; horizontal < row.length; horizontal++) {
                    int expected = viewport.masked(horizontal, vertical) ? 0xff777777 : 0xff000000
                            | ((horizontal * 3) & 255) << 16 | ((vertical * 5) & 255) << 8 | ((horizontal + vertical) & 255);
                    require(row[horizontal] == expected, "rgba-and-mask-equivalent");
                }
            }
            rejected(() -> copier.copyRow(-1, row));
            rejected(() -> copier.copyRow(viewport.height(), row));
            rejected(() -> copier.copyRow(0, new int[1]));
            rejected(() -> new MaskedRgbaRows(viewport, 1, limit, (offset, target, targetOffset, length) -> {}));
            rejected(() -> new MaskedRgbaRows(viewport, stride, 20, (offset, target, targetOffset, length) -> {}));
        }
        System.out.println("PASS maskedRgbaChecks=" + checks + " forbiddenReads=0 chatFiles=0");
    }

    private static void rejected(Runnable action) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "invalid-dimensions-rejected");
    }

    private static void require(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}
