package com.codextemp.wechatcaptureprobe;

import android.graphics.Bitmap;
import com.google.mlkit.vision.text.TextRecognizer;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 仅测试：hold准备交付或父图读取，正常停止后放行；图片只在内存检查回收标记。 */
final class PreparationStopHold implements AutoCloseable {
    private final Object service;
    private final BubbleOcr ocr;
    private final ExecutorService worker;
    private final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    private final AtomicInteger bodySdkCalls = new AtomicInteger();
    private volatile Bitmap image;
    private volatile boolean ended;
    private volatile boolean releasedNormally;
    private final boolean snapshotMode;
    private volatile boolean copiedAfterRelease;

    PreparationStopHold() {
        this(false);
    }

    /** snapshot模式先hold再真实读取父图，用于确认服务销毁不会提前回收它。 */
    PreparationStopHold(boolean snapshotMode) {
        this.snapshotMode = snapshotMode;
        service = field(AssistantCaptureService.class, null, "instance");
        ocr = (BubbleOcr) field(AssistantCaptureService.class, service, "bubbleOcr");
        BubblePreparation preparation = (BubblePreparation) field(BubbleOcr.class, ocr, "preparation");
        worker = snapshotMode ? (ExecutorService) field(BubbleOcr.class, ocr, "snapshotWorker")
                : (ExecutorService) field(BubblePreparation.class, preparation, "worker");
        BubblePreparation.Factory original = (BubblePreparation.Factory) field(BubblePreparation.class, preparation, "factory");
        TextRecognizer recognizer = (TextRecognizer) field(BubbleOcr.class, ocr, "chinese");
        TextRecognizer counting = (TextRecognizer) Proxy.newProxyInstance(TextRecognizer.class.getClassLoader(),
                new Class<?>[]{TextRecognizer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("process")) bodySdkCalls.incrementAndGet();
                    try { return method.invoke(recognizer, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
        set(BubbleOcr.class, ocr, "chinese", counting);
        if (snapshotMode) {
            BubbleOcr.SnapshotFactory originalSnapshot = (BubbleOcr.SnapshotFactory) field(BubbleOcr.class, ocr, "snapshotFactory");
            set(BubbleOcr.class, ocr, "snapshotFactory", (BubbleOcr.SnapshotFactory) (screen, box, density) -> {
                image = screen;
                entered.countDown();
                try {
                    releasedNormally = release.await(30, TimeUnit.SECONDS);
                    if (!releasedNormally) throw new IllegalStateException("snapshot-hold-timeout");
                    BubbleOcr.PixelSnapshot result = originalSnapshot.create(screen, box, density);
                    copiedAfterRelease = true;
                    return result;
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("snapshot-hold-interrupted");
                } finally { ended = true; }
            });
        } else {
        set(BubblePreparation.class, preparation, "factory", (BubblePreparation.Factory) (pixels, box, density) -> {
            BubblePreparation.Result ready = original.create(pixels, box, density);
            image = ready.bitmap;
            entered.countDown();
            try {
                releasedNormally = release.await(30, TimeUnit.SECONDS);
                if (!releasedNormally) throw new IllegalStateException("preparation-hold-timeout");
                return ready;
            } catch (InterruptedException failure) {
                ready.close(); Thread.currentThread().interrupt();
                throw new IllegalStateException("preparation-hold-interrupted");
            } catch (RuntimeException failure) {
                ready.close(); throw failure;
            } finally { ended = true; }
        });
        }
        ocr.clearCache();
    }

    boolean pending() {
        return entered.getCount() == 0 && !ended && image != null && !image.isRecycled()
                && Boolean.TRUE.equals(field(AssistantCaptureService.class, service, "busy"))
                && bodySdkCalls.get() == 0;
    }

    boolean destroyedWhileHeld() {
        return pending() && field(AssistantCaptureService.class, null, "instance") == null
                && Boolean.TRUE.equals(field(AssistantCaptureService.class, service, "stopped"))
                && Boolean.TRUE.equals(field(BubbleOcr.class, ocr, "closed"))
                && worker.isShutdown() && !worker.isTerminated();
    }

    void release() { release.countDown(); }

    boolean reclaimed() {
        return releasedNormally && (!snapshotMode || copiedAfterRelease)
                && worker.isTerminated() && image != null && image.isRecycled()
                && Boolean.FALSE.equals(field(AssistantCaptureService.class, service, "busy"))
                && ((ExactPixelCache<?>) field(BubbleOcr.class, ocr, "cache")).size() == 0
                && bodySdkCalls.get() == 0;
    }

    @Override public void close() { release(); }

    private static Object field(Class<?> type, Object object, String name) {
        try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("preparation-stop-field-unavailable"); }
    }
    private static void set(Class<?> type, Object object, String name, Object value) {
        try { Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(object,value); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("preparation-stop-hook-unavailable"); }
    }
}
