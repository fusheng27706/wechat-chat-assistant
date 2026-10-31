package com.codextemp.wechatcaptureprobe;

import android.graphics.Bitmap;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** 仅测试：阻塞几何工作队列，不触碰SDK/来源。真实图像只检查回收标记；标题数组仅在停止后验证已清零，不输出内容。 */
final class HeaderStopHold implements AutoCloseable {
    private final Object service;
    private final FreshGeometryRead reader;
    private final ExecutorService worker;
    private final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
    private volatile boolean ended;
    private volatile boolean interrupted;
    private Bitmap image;
    private int[] headerPixels;

    HeaderStopHold() {
        service = field(AssistantCaptureService.class, null, "instance");
        reader = (FreshGeometryRead) field(AssistantCaptureService.class, service, "resultGeometry");
        worker = (ExecutorService) field(AssistantCaptureService.class, service, "resultGeometryWorker");
        worker.execute(() -> {
            started.countDown();
            try { release.await(30, TimeUnit.SECONDS); }
            catch (InterruptedException stopped) { interrupted = true; Thread.currentThread().interrupt(); }
            finally { ended = true; }
        });
    }

    boolean holding() { return started.getCount() == 0 && !ended; }

    /** 主线程调用，等待的是标题处理完成或精确复用后登记的真实续接，不能假造pending。 */
    boolean pendingImage() {
        if (!holding() || field(FreshGeometryRead.class, reader, "pending") == null) return false;
        Object callback = field(FreshGeometryRead.class, reader, "callback");
        Bitmap found = null;
        try {
            for (Field member : callback.getClass().getDeclaredFields()) {
                if (member.getType() != Bitmap.class) continue;
                member.setAccessible(true);
                if (found != null) throw new AssertionError("ambiguous-pending-image");
                found = (Bitmap) member.get(callback);
            }
        } catch (ReflectiveOperationException failure) { throw new AssertionError("pending-image-unavailable"); }
        if (found == null || found.isRecycled()) throw new AssertionError("pending-image-not-live");
        image = found;
        headerPixels=(int[])field(AssistantCaptureService.class,service,"processingHeaderPixels");
        return Boolean.TRUE.equals(field(AssistantCaptureService.class, service, "busy"));
    }

    boolean destroyedCleanly() {
        if(headerPixels==null)return false;
        for(int value:headerPixels)if(value!=0)return false;
        Object cache=field(AssistantCaptureService.class,service,"headerCache");
        if(!((java.util.List<?>)field(HeaderRecognitionCache.class,cache,"entries")).isEmpty())return false;
        return image != null && image.isRecycled()
                && Boolean.TRUE.equals(field(FreshGeometryRead.class, reader, "closed"))
                && field(FreshGeometryRead.class, reader, "pending") == null
                && field(FreshGeometryRead.class, reader, "callback") == null
                && Boolean.FALSE.equals(field(AssistantCaptureService.class, service, "busy"))
                && field(AssistantCaptureService.class,service,"processingHeaderPixels")==null
                && worker.isShutdown();
    }

    boolean terminated() { return worker.isTerminated() && interrupted; }

    @Override public void close() { release.countDown(); image = null; headerPixels=null; }

    private static Object field(Class<?> type, Object object, String name) {
        try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("header-stop-field-unavailable"); }
    }
}
