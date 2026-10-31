package com.codextemp.wechatcaptureprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.List;

/** 固定生成字节，验证批量路径与旧路径逐像素相同、遮挡从未被读取。 */
public final class MaskedRgbaWordsRegression {
    private static int checks;
    private static void require(boolean value) { checks++; if (!value) throw new AssertionError("word-copy-" + checks); }
    private static void rejected(Runnable work) {
        boolean failed = false; try { work.run(); } catch (IllegalArgumentException expected) { failed = true; }
        require(failed);
    }
    private static void allowed(ChatViewport viewport, int stride, int offset, int count) {
        int y = offset / stride, x = offset % stride / 4;
        require(offset % 4 == 0 && y >= viewport.bounds.top && y < viewport.bounds.bottom
                && x >= viewport.bounds.left && x + count <= viewport.bounds.right);
        for (int i = 0; i < count; i++) require(!viewport.masked(x + i - viewport.bounds.left, y - viewport.bounds.top));
    }
    public static void main(String[] args) {
        ScreenGeometry screen = new ScreenGeometry(1080, 2400, 440, 0, 0, 69, 0, 130, true);
        for (int trial = 0; trial < 8; trial++) {
            List<ChatViewport.Bounds> masks = trial % 2 == 0 ? List.of() : List.of(
                    new ChatViewport.Bounds(0, 300, 210, 500), new ChatViewport.Bounds(180, 340, 430, 610),
                    new ChatViewport.Bounds(900, 500, 1100, 750), new ChatViewport.Bounds(0, 800, 1080, 880));
            ChatViewport viewport = ChatViewport.create(screen, new ChatViewport.Bounds(20, 100, 1020, 1100), -1, 0, 0, masks);
            require(viewport != null);
            int stride = 1080 * 4 + (trial % 3) * 16;
            int limit = (viewport.bounds.bottom - 1) * stride + viewport.bounds.right * 4;
            ByteBuffer original = (trial % 2 == 0 ? ByteBuffer.allocate(limit) : ByteBuffer.allocateDirect(limit)).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < limit; i++) original.put(i, (byte) (i * 73 + (i >>> 7) * 19));
            original.position(97);
            ByteBuffer bytes = original.asReadOnlyBuffer();
            ByteBuffer wordBytes = original.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN);
            wordBytes.position(0); IntBuffer words = wordBytes.asIntBuffer();
            MaskedRgbaRows reference = new MaskedRgbaRows(viewport, stride, limit, (offset, target, at, length) -> {
                allowed(viewport, stride, offset, length / 4);
                bytes.position(offset); bytes.get(target, at, length);
            });
            MaskedRgbaWords candidate = new MaskedRgbaWords(viewport, stride, limit, (offset, target, at, count) -> {
                allowed(viewport, stride, offset, count);
                words.position(offset / 4); words.get(target, at, count);
            });
            int height = trial % 4 < 2 ? 132 : viewport.height();
            int[] actual = new int[viewport.width() * height + 18]; Arrays.fill(actual, 0x12345678);
            int[] expected = new int[viewport.width()];
            for (int y = 0; y < height; y++) {
                candidate.copyRow(y, actual, 7 + y * viewport.width()); reference.copyRow(y, expected);
                for (int x = 0; x < expected.length; x++) require(expected[x] == actual[7 + y * expected.length + x]);
            }
            for (int i = 0; i < 7; i++) require(actual[i] == 0x12345678);
            for (int i = actual.length - 11; i < actual.length; i++) require(actual[i] == 0x12345678);
            require(original.position() == 97 && original.limit() == limit && original.order() == ByteOrder.LITTLE_ENDIAN);
            rejected(() -> candidate.copyRow(-1, actual, 0));
            rejected(() -> candidate.copyRow(viewport.height(), actual, 0));
            rejected(() -> candidate.copyRow(0, actual, -1));
            rejected(() -> candidate.copyRow(0, actual, Integer.MAX_VALUE));
            rejected(() -> candidate.copyRow(0, new int[10], 0));
            rejected(() -> new MaskedRgbaWords(viewport, stride + 1, limit, (a,b,c,d) -> {}));
            rejected(() -> new MaskedRgbaWords(viewport, stride, limit - 1, (a,b,c,d) -> {}));
        }
        System.out.println("PASS maskedWordChecks=" + checks + " cases=8 forbiddenReads=0 syntheticOnly=true");
    }
}
