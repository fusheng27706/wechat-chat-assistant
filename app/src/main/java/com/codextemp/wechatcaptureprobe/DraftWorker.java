package com.codextemp.wechatcaptureprobe;

import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

final class DraftWorker {
    enum Status { COMPLETED, EXPIRED, FAILED }

    static final class Result<Value> {
        final Status status;
        final Value value;

        Result(Status status, Value value) {
            this.status = status;
            this.value = value;
        }
    }

    final class Task<Value> {
        private final long startedAt;
        private volatile BooleanSupplier permitted;
        private Consumer<Result<Value>> callback;
        private volatile boolean cancelled;

        Task(BooleanSupplier permitted, Consumer<Result<Value>> callback) {
            startedAt = clock.getAsLong();
            this.permitted = permitted;
            this.callback = callback;
        }

        boolean current() {
            long now = clock.getAsLong();
            BooleanSupplier permission = permitted;
            if (cancelled || permission == null || now < startedAt || now - startedAt >= lifetime) return false;
            try {
                if (!permission.getAsBoolean()) return false;
                now = clock.getAsLong();
                return !cancelled && now >= startedAt && now - startedAt < lifetime;
            } catch (RuntimeException failure) { return false; }
        }

        synchronized void cancel() {
            cancelled = true;
            permitted = null;
            callback = null;
        }

        void complete(Result<Value> result) {
            Result<Value> delivered = result.status == Status.EXPIRED || !current()
                    ? new Result<>(Status.EXPIRED, null) : result;
            Consumer<Result<Value>> listener;
            synchronized (this) {
                listener = callback;
                callback = null;
                cancelled = true;
                permitted = null;
            }
            if (listener != null) listener.accept(delivered);
        }
    }

    private final Executor executor;
    private final Executor delivery;
    private final BiConsumer<Runnable, Long> timer;
    private final LongSupplier clock;
    private final long lifetime;
    private Task<?> pending;

    DraftWorker(Executor executor, Executor delivery, BiConsumer<Runnable, Long> timer,
                LongSupplier clock, long lifetime) {
        if (executor == null || delivery == null || timer == null || clock == null || lifetime <= 0) {
            throw new IllegalArgumentException("Invalid draft worker");
        }
        this.executor = executor;
        this.delivery = delivery;
        this.timer = timer;
        this.clock = clock;
        this.lifetime = lifetime;
    }

    <Value> Task<Value> submit(BooleanSupplier permitted, Function<BooleanSupplier, Value> operation,
                             Consumer<Result<Value>> callback) {
        if (permitted == null || operation == null || callback == null) {
            throw new IllegalArgumentException("Draft task contract missing");
        }
        Task<Value> task;
        synchronized (this) {
            if (pending != null) return null;
            task = new Task<>(permitted, callback);
            pending = task;
        }
        try {
            timer.accept(() -> task.complete(new Result<>(Status.EXPIRED, null)), lifetime);
            executor.execute(() -> run(task, operation));
        } catch (RuntimeException failure) {
            release(task);
            deliver(task, new Result<>(Status.FAILED, null));
        }
        return task;
    }

    synchronized boolean busy() { return pending != null; }

    private <Value> void run(Task<Value> task, Function<BooleanSupplier, Value> operation) {
        Result<Value> result;
        try {
            Value value = task.current() ? operation.apply(task::current) : null;
            result = task.current() ? new Result<>(Status.COMPLETED, value) : new Result<>(Status.EXPIRED, null);
        } catch (RuntimeException failure) {
            result = new Result<>(Status.FAILED, null);
        } finally { release(task); }
        deliver(task, result);
    }

    private synchronized void release(Task<?> task) {
        if (pending == task) pending = null;
    }

    private <Value> void deliver(Task<Value> task, Result<Value> result) {
        try { delivery.execute(() -> task.complete(result)); }
        catch (RuntimeException failure) { task.cancel(); }
    }
}
