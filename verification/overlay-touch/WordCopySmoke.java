package com.codextemp.wechatcaptureprobe;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** 固定生成画面交替运行新旧复制；不打开微信、不采集真实图像，不等同端到端速度。 */
public final class WordCopySmoke extends Instrumentation {
    private int checks;
    private void require(boolean condition, String code) { checks++; if (!condition) throw new AssertionError(code); }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> {
            try {
                require(!AssistantCaptureService.running() && !CaptureService.running(), "capture-must-be-off");
                ScreenGeometry geometry = new ScreenGeometry(1080, 2400, 440, 0, 0, 69, 0, 130, true);
                ChatViewport viewport = ChatViewport.create(geometry, new ChatViewport.Bounds(0, 69, 1080, 2270), -1, 0, 0,
                        List.of(new ChatViewport.Bounds(800, 400, 1080, 780)));
                require(viewport != null, "fixed-viewport");
                int stride = 1080 * 4 + 16;
                ByteBuffer source = ByteBuffer.allocateDirect(stride * 2400);
                for (int i = 0; i < source.limit(); i++) source.put(i, (byte) (i * 73 + (i >>> 7) * 19));
                int[] expected = new int[viewport.width() * viewport.height()];
                copy(source, viewport, stride, expected, false);
                long[] oldTimes = new long[12], newTimes = new long[12];
                for (int round = -4; round < 12; round++) {
                    for (int turn = 0; turn < 2; turn++) {
                        boolean words = ((round & 1) == 0) == (turn == 0);
                        long began = SystemClock.elapsedRealtimeNanos();
                        Bitmap bitmap = Bitmap.createBitmap(viewport.width(), viewport.height(), Bitmap.Config.ARGB_8888);
                        int[] pixels = new int[expected.length];
                        try {
                            copy(source, viewport, stride, pixels, words);
                            bitmap.setPixels(pixels, 0, viewport.width(), 0, 0, viewport.width(), viewport.height());
                            long elapsed = SystemClock.elapsedRealtimeNanos() - began;
                            require(Arrays.equals(expected, pixels), "full-argb-equivalence");
                            if (round >= 0) (words ? newTimes : oldTimes)[round] = elapsed;
                        } finally { bitmap.recycle(); Arrays.fill(pixels, 0); }
                    }
                }
                Arrays.fill(expected, 0);
                result.putString("result", "PASS wordCopyChecks=" + checks + " fixedOnly=true realImages=0 rounds=12"
                        + " byteMicros=" + micros(oldTimes) + " wordMicros=" + micros(newTimes));
            } catch (Throwable caught) { failure.set(caught); }
        });
        if (failure.get() != null) result.putString("result", "FAIL wordCopyChecks=" + checks + " type=" + failure.get().getClass().getSimpleName());
        finish(failure.get() == null ? -1 : 0, result);
    }
    private static String micros(long[] values) {
        long[] ordered = values.clone(); Arrays.sort(ordered);
        return (ordered[0] / 1000) + "," + (ordered[ordered.length / 2] / 1000) + "," + (ordered[ordered.length - 1] / 1000);
    }
    private static void copy(ByteBuffer source, ChatViewport viewport, int stride, int[] pixels, boolean words) {
        ByteBuffer bytes = source.duplicate(); bytes.position(0);
        if (words) {
            IntBuffer input = bytes.order(ByteOrder.BIG_ENDIAN).asIntBuffer();
            MaskedRgbaWords copier = new MaskedRgbaWords(viewport, stride, bytes.limit(), (offset, target, at, count) -> {
                input.position(offset / 4); input.get(target, at, count);
            });
            for (int y = 0; y < viewport.height(); y++) copier.copyRow(y, pixels, y * viewport.width());
        } else {
            MaskedRgbaRows copier = new MaskedRgbaRows(viewport, stride, bytes.limit(), (offset, target, at, count) -> {
                bytes.position(offset); bytes.get(target, at, count);
            });
            int[] row = new int[viewport.width()];
            for (int y = 0; y < viewport.height(); y++) {
                copier.copyRow(y, row); System.arraycopy(row, 0, pixels, y * viewport.width(), row.length);
            }
        }
    }
}
