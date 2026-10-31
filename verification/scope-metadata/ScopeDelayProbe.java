package com.codextemp.wechatcaptureprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

public final class ScopeDelayProbe extends Instrumentation {
    private final AtomicBoolean ready = new AtomicBoolean();
    private final AtomicBoolean fixtureLive = new AtomicBoolean();
    private final AtomicLong fixtureDelayObservedAt = new AtomicLong();
    private final String nonce = UUID.randomUUID().toString();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int checks;
    private Activity main;
    private boolean registered;
    private final BroadcastReceiver trigger = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if ("com.codextemp.wechatcaptureprobe.SCOPE_DELAY_START".equals(intent.getAction())) ready.set(true);
            else if (nonce.equals(intent.getStringExtra("nonce"))) {
                if ("com.codextemp.wechatcaptureprobe.SCOPE_FIXTURE_LIVE".equals(intent.getAction())) fixtureLive.set(true);
                if ("com.codextemp.wechatcaptureprobe.SCOPE_FIXTURE_DELAY".equals(intent.getAction())) {
                    fixtureDelayObservedAt.set(SystemClock.elapsedRealtime());
                }
            }
        }
    };

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            ModelSettingsStore settings = new ModelSettingsStore(getTargetContext());
            require(!settings.hasKey() && !settings.cloudAllowed() && !AssistantCaptureService.running()
                    && !CaptureService.running(), "no-capture-no-model-precondition");
            IntentFilter filter = new IntentFilter("com.codextemp.wechatcaptureprobe.SCOPE_DELAY_START");
            filter.addAction("com.codextemp.wechatcaptureprobe.SCOPE_FIXTURE_LIVE");
            filter.addAction("com.codextemp.wechatcaptureprobe.SCOPE_FIXTURE_DELAY");
            if (Build.VERSION.SDK_INT >= 33) getTargetContext().registerReceiver(trigger, filter, Context.RECEIVER_EXPORTED);
            else getTargetContext().registerReceiver(trigger, filter);
            registered = true;
            phase("WAITING_FOR_FOREGROUND_MAIN");
            main = startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            phase("WAITING_FOR_SCOPE_AND_TRIGGER");
            await(() -> ChatGateService.enabled() && ready.get(), 180000, "normal-scope-enable-and-external-trigger");
            List<Long> queue = Collections.synchronizedList(new ArrayList<>());
            AtomicBoolean pending = new AtomicBoolean();
            AtomicLong postedAt = new AtomicLong();
            runOnMainSync(() -> main.startActivity(new Intent().setClassName(getContext().getPackageName(),
                    SlowMetadataActivity.class.getName()).putExtra("nonce", nonce).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            phase("MEASURING_SYNTHETIC_WINDOW");
            long started = SystemClock.elapsedRealtime();
            boolean reported = false;
            boolean workerSampled = false;
            int validSamples = 0;
            int missingScopeSamples = 0;
            while (SystemClock.elapsedRealtime() - started < 120000) {
                if (pending.compareAndSet(false, true)) {
                    long posted = SystemClock.elapsedRealtime();
                    postedAt.set(posted);
                    handler.post(() -> {
                        ChatGateService.verify();
                        queue.add(SystemClock.elapsedRealtime() - posted);
                        pending.set(false);
                    });
                }
                if (fixtureLive.get() && ChatGateService.snapshot().valid()) validSamples++;
                if (fixtureLive.get() && !ChatGateService.enabled()) missingScopeSamples++;
                if (pending.get() && SystemClock.elapsedRealtime() - postedAt.get() > 1200 && !reported) {
                    reported = true;
                    for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                        if (entry.getKey().getName().equals("main")) {
                            for (int index = 0; index < Math.min(24, entry.getValue().length); index++) {
                                Log.i("ScopeDelayProbe", "OWN_MAIN_STACK " + entry.getValue()[index]);
                            }
                        }
                    }
                }
                if (fixtureLive.get() && fixtureDelayObservedAt.get() > 0 && !workerSampled) {
                    for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                        if (entry.getKey().getName().equals("chat-scope-metadata")
                                && java.util.Arrays.stream(entry.getValue()).anyMatch(frame -> frame.getClassName().contains("AccessibilityInteractionClient"))) {
                            workerSampled = true;
                            for (int index = 0; index < Math.min(18, entry.getValue().length); index++) {
                                Log.i("ScopeDelayProbe", "OWN_WORKER_STACK " + entry.getValue()[index]);
                            }
                        }
                    }
                }
                if (fixtureLive.get() && fixtureDelayObservedAt.get() > 0
                        && SystemClock.elapsedRealtime() - fixtureDelayObservedAt.get() >= 8500) break;
                SystemClock.sleep(100);
            }
            runOnMainSync(() -> {});
            List<Long> sorted;
            synchronized (queue) { sorted = new ArrayList<>(queue); }
            Collections.sort(sorted);
            require(!sorted.isEmpty(), "main-queue-samples-collected");
            require(fixtureLive.get() && fixtureDelayObservedAt.get() > 0, "actual-fixture-and-delayed-node-response-observed");
            require(missingScopeSamples == 0 && ChatGateService.enabled(), "range-service-remained-connected-during-fixture");
            require(validSamples == 0 && !AssistantCaptureService.running() && !CaptureService.running(), "synthetic-page-never-captured");
            require(!settings.cloudAllowed() && !settings.hasKey(), "model-remains-off");
            long percentile = sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.95) - 1));
            output.putString("result", "MEASURED scopeDelayChecks=" + checks + " parentPid=" + Process.myPid()
                    + " samples=" + sorted.size() + " mainTaskP95Ms=" + percentile + " mainTaskMaxMs=" + sorted.get(sorted.size() - 1)
                    + " stalledStackSampled=" + reported + " invalidSourceAccepted=" + validSamples
                    + " workerWaitSampled=" + workerSampled
                    + " screenGrants=0 imageReads=0 modelRequests=0 wechatWrites=0 chatFiles=0");
        } catch (Throwable failure) {
            output.putString("result", "FAIL checks=" + checks + " type=" + failure.getClass().getSimpleName()
                    + (failure instanceof AssertionError ? " check=" + failure.getMessage() : ""));
        } finally {
            if (registered) getTargetContext().unregisterReceiver(trigger);
            runOnMainSync(() -> { if (main != null) main.finish(); });
        }
        Log.i("ScopeDelayProbe", output.getString("result"));
        finish(output.getString("result").startsWith("MEASURED") ? -1 : 0, output);
    }

    private void phase(String name) {
        Log.i("ScopeDelayProbe", "PHASE " + name);
        Bundle status = new Bundle(); status.putString("phase", name); sendStatus(1, status);
    }

    private void await(BooleanSupplier condition, long timeout, String name) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
        require(condition.getAsBoolean(), name);
    }

    private void require(boolean condition, String name) { checks++; if (!condition) throw new AssertionError(name); }
}
