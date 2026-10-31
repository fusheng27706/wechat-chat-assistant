package com.codextemp.wechatcaptureprobe;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 主线程所有权的单次新几何读取；后台仅持有请求标识，不持有识别图像。 */
final class FreshGeometryRead implements AutoCloseable {
    static final class Reading {
        final ScreenGeometry geometry;
        final long startedAt;
        Reading(ScreenGeometry geometry, long startedAt) { this.geometry = geometry; this.startedAt = startedAt; }
        // 排队、系统读取及主线程消费都计入期限；证据不能保存给后续识别复用。
        boolean fresh(long now) { return geometry != null && now >= startedAt && now - startedAt < 200; }
    }
    private final Executor worker, main;
    private final LongSupplier clock;
    private final Supplier<ScreenGeometry> reader;
    private Object pending;
    private Consumer<Reading> callback;
    private boolean closed;

    FreshGeometryRead(Executor worker, Executor main, LongSupplier clock, Supplier<ScreenGeometry> reader) {
        this.worker = worker; this.main = main; this.clock = clock; this.reader = reader;
    }

    /** 仅主线程调用；成功受理后恰好回调一次，null表示失效、失败或关闭。 */
    boolean request(Consumer<Reading> completion) {
        if (closed || pending != null) return false;
        Object identity = new Object();
        long started = clock.getAsLong();
        pending = identity;
        callback = completion;
        try {
            worker.execute(() -> {
                ScreenGeometry value;
                try { value = reader.get(); } catch (RuntimeException failure) { value = null; }
                Reading result = new Reading(value, started);
                main.execute(() -> complete(identity, result));
            });
        } catch (RuntimeException rejected) {
            complete(identity, null);
        }
        return true;
    }

    private void complete(Object identity, Reading result) {
        if (pending != identity) return;
        Consumer<Reading> completion = callback;
        pending = null;
        callback = null;
        completion.accept(result != null && result.fresh(clock.getAsLong()) ? result : null);
    }

    /** 主线程关闭，不等待Binder；先解除回调所有权，迟到结果不能触碰新操作。 */
    @Override public void close() {
        closed = true;
        if (pending != null) complete(pending, null);
    }
}
