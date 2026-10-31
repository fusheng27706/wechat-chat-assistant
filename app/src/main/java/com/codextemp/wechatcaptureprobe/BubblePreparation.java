package com.codextemp.wechatcaptureprobe;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** 后台只处理本次气泡的私有像素；来源、缓存与SDK仍由主线程管理。 */
final class BubblePreparation implements AutoCloseable {
    static final class Result implements AutoCloseable {
        final Bitmap bitmap;
        final List<Rect> graphics;
        final long visual;
        Result(Bitmap bitmap, List<Rect> graphics, long visual) {
            this.bitmap = bitmap; this.graphics = graphics; this.visual = visual;
        }
        @Override public void close() { bitmap.recycle(); }
    }

    interface Factory { Result create(int[] pixels, ChatLayout.Box box, float density); }
    private final ExecutorService worker;
    private final Handler delivery;
    private final Factory factory;

    BubblePreparation() {
        this(Executors.newSingleThreadExecutor(), new Handler(Looper.getMainLooper()), BubblePreparation::create);
    }

    BubblePreparation(ExecutorService worker, Handler delivery, Factory factory) {
        this.worker = worker; this.delivery = delivery; this.factory = factory;
    }

    /** 主线程调用，逐气泡串行；交付后结果归接收方，null表示准备失败。 */
    void prepare(int[] pixels, ChatLayout.Box box, float density, Consumer<Result> receiver) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Preparation requires main thread");
        try {
            worker.execute(() -> {
                Result result;
                try { result = factory.create(pixels, box, density); }
                catch (RuntimeException failure) { result = null; }
                Result ready = result;
                // 使用独立Handler，服务清空自身回调后仍可交付晚到结果并回收。
                if (!delivery.post(() -> receiver.accept(ready)) && ready != null) ready.close();
            });
        } catch (RejectedExecutionException failure) {
            receiver.accept(null);
        }
    }

    /** 不等待或丢弃已提交任务；调用方关闭后仍须接收结果，只回收、不启动SDK。 */
    @Override public void close() { worker.shutdown(); }

    static Result create(int[] pixels, ChatLayout.Box box, float density) {
        int width = box.right - box.left, height = box.bottom - box.top;
        List<GraphicDetector.Region> graphics = GraphicDetector.detect(
                (horizontal, vertical) -> pixels[(vertical - box.top) * width + horizontal - box.left], box, density);
        Bitmap prepared = Bitmap.createBitmap(width + 24, height + 24, Bitmap.Config.ARGB_8888);
        try {
            prepared.eraseColor(Color.WHITE);
            prepared.setPixels(pixels, 0, width, 12, 12, width, height);
            Canvas canvas = new Canvas(prepared);
            android.graphics.Paint paint = new android.graphics.Paint();
            paint.setColor(Color.WHITE);
            List<Rect> graphicBounds = new ArrayList<>();
            long visual = 0;
            for (GraphicDetector.Region region : graphics) {
                Rect rectangle = new Rect(region.left - box.left + 12, region.top - box.top + 12,
                        region.right - box.left + 12, region.bottom - box.top + 12);
                graphicBounds.add(rectangle);
                canvas.drawRect(rectangle.left - 2, rectangle.top - 2, rectangle.right + 2, rectangle.bottom + 2, paint);
                visual = visual == 0 ? 1469598103934665603L : visual;
                for (int vertical = region.top; vertical < region.bottom; vertical += 2) {
                    for (int horizontal = region.left; horizontal < region.right; horizontal += 2) {
                        int color = pixels[(vertical - box.top) * width + horizontal - box.left];
                        visual = (visual ^ (color & 0x00f8f8f8)) * 1099511628211L;
                    }
                }
            }
            return new Result(prepared, graphicBounds, visual);
        } catch (RuntimeException failure) {
            prepared.recycle();
            throw failure;
        }
    }
}
