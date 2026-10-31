package com.codextemp.wechatcaptureprobe;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

final class DraftOperations {
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final DraftWorker shared = new DraftWorker(
            Executors.newSingleThreadExecutor(action -> {
                Thread thread = new Thread(action, "chat-draft-nodes");
                thread.setDaemon(true);
                return thread;
            }), action -> handler.post(action), (action, delay) -> handler.postDelayed(action, delay),
            SystemClock::elapsedRealtime, 800);
    private final DraftWorker worker;
    private final Function<AssistantCaptureService.ComposerProof, BooleanSupplier> permissions;
    private final BiFunction<AssistantCaptureService.ComposerProof, BooleanSupplier, DraftPlan.Editor> editors;

    DraftOperations() {
        this(shared, AssistantCaptureService::composerPermission, ChatGateService::composerEditor);
    }

    DraftOperations(DraftWorker worker, Function<AssistantCaptureService.ComposerProof, BooleanSupplier> permissions,
            BiFunction<AssistantCaptureService.ComposerProof, BooleanSupplier, DraftPlan.Editor> editors) {
        this.worker = worker;
        this.permissions = permissions;
        this.editors = editors;
    }

    <Value> DraftWorker.Task<Value> submit(AssistantCaptureService.ComposerProof proof,
            BooleanSupplier current, BiFunction<DraftPlan.Editor, BooleanSupplier, Value> operation,
            Consumer<DraftWorker.Result<Value>> callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Draft admission requires UI thread");
        BooleanSupplier source = permissions.apply(proof);
        return worker.submit(() -> current.getAsBoolean() && source.getAsBoolean(), allowed -> {
            DraftPlan.Editor editor = editors.apply(proof, allowed);
            return allowed.getAsBoolean() ? operation.apply(editor, allowed) : null;
        }, callback);
    }
}
