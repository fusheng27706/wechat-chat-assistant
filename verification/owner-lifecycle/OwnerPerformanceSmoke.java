package com.codextemp.wechatcaptureprobe;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.system.Os;
import android.system.OsConstants;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

public final class OwnerPerformanceSmoke extends Instrumentation {
    private final AtomicInteger command = new AtomicInteger();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int checks;
    private Activity main;
    private Activity assistant;
    private boolean receiverRegistered;
    private String variant;
    private int worker;
    private long ticksPerSecond;
    private ProcessCpuSnapshot parentIdentity;
    private ProcessCpuSnapshot workerIdentity;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if ("com.codextemp.wechatcaptureprobe.OWNER_PERF_SCROLL".equals(intent.getAction())) command.set(1);
            if ("com.codextemp.wechatcaptureprobe.OWNER_PERF_OUTSIDE".equals(intent.getAction())) command.set(2);
            if ("com.codextemp.wechatcaptureprobe.OWNER_PERF_RETURN".equals(intent.getAction())) command.set(3);
        }
    };

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        variant = args == null ? "owner016" : args.getString("variant", "owner016");
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            ModelSettingsStore settings = new ModelSettingsStore(getTargetContext());
            require(!settings.hasKey() && !settings.cloudAllowed() && !AssistantCaptureService.running(), "no-model-or-capture-precondition");
            int version = getTargetContext().getPackageManager().getPackageInfo(getTargetContext().getPackageName(), 0).versionCode;
            require(variant.equals("baseline015") && version == 15 || variant.equals("owner016") && version == 16, "expected-installed-variant");
            IntentFilter filter = new IntentFilter();
            filter.addAction("com.codextemp.wechatcaptureprobe.OWNER_PERF_SCROLL");
            filter.addAction("com.codextemp.wechatcaptureprobe.OWNER_PERF_OUTSIDE");
            filter.addAction("com.codextemp.wechatcaptureprobe.OWNER_PERF_RETURN");
            if (Build.VERSION.SDK_INT >= 33) getTargetContext().registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            else getTargetContext().registerReceiver(receiver, filter);
            receiverRegistered = true;
            phase("WAITING_FOR_FIXTURE_ACTIVITY");
            main = startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            assistant = startActivitySync(new Intent(getTargetContext(), AssistantActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            phase("WAITING_FOR_MANUAL_GRANT");
            await(AssistantCaptureService::running, 180000, "normal-system-grant");
            await(() -> !AssistantState.messages.isEmpty(), 60000, "initial-test-chat");
            worker = workerPid();
            require(variant.equals("baseline015") ? worker == 0 : worker > 0, "expected-process-topology");
            ticksPerSecond = Os.sysconf(OsConstants._SC_CLK_TCK);
            require(ticksPerSecond > 0, "clock-rate");
            parentIdentity = ProcessCpuSnapshot.read(Process.myPid());
            workerIdentity = worker == 0 ? null : ProcessCpuSnapshot.read(worker);
            StringBuilder sampleText = new StringBuilder();
            for (ChatMessage message : AssistantState.messages) sampleText.append(message.text).append('\n');
            Log.i("OwnerPerformance", "METHOD variant=" + variant + " parentPid=" + Process.myPid() + " workerPid=" + worker
                    + " clockTicksPerSecond=" + ticksPerSecond + " initialMessages=" + AssistantState.messages.size()
                    + " samples=" + String.join(",", ConversationSamples.matchedCodes(sampleText.toString())));
            phase("WARMUP");
            SystemClock.sleep(8000);
            measure("IDLE", 60000);
            require(ChatGateService.snapshot().valid() && !AssistantState.messages.isEmpty(), "idle-current-chat");
            phase("WAITING_SCROLL_TRIGGER");
            await(() -> command.get() == 1, 150000, "scroll-trigger");
            measure("SCROLL", 60000);
            measure("RECOVERY", 30000);
            require(ChatGateService.snapshot().valid(), "recovery-current-chat");
            phase("WAITING_OUTSIDE_TRIGGER");
            await(() -> command.get() == 2, 150000, "outside-trigger");
            await(() -> !ChatGateService.snapshot().valid(), 5000, "left-wechat");
            require(AssistantState.messages.isEmpty(), "outside-clears-text");
            int outsideFrames = frames();
            measure("OUTSIDE", 20000);
            require(frames() == outsideFrames && AssistantState.messages.isEmpty(), "no-new-ocr-outside");
            phase("WAITING_RETURN_TRIGGER");
            await(() -> command.get() == 3, 150000, "return-trigger");
            long returnStarted = SystemClock.elapsedRealtime();
            await(() -> !AssistantState.messages.isEmpty(), 25000, "return-recognized");
            Log.i("OwnerPerformance", "RETURN_RECOGNIZED triggerToPublicationMs=" + (SystemClock.elapsedRealtime() - returnStarted)
                    + " messages=" + AssistantState.messages.size() + " ocrWorkMs=" + AssistantState.ocrMs);
            measure("RETURN_IDLE", 20000);
            require(!settings.hasKey() && !settings.cloudAllowed(), "model-stays-off");
            result.putString("result", "PASS combinedPerformanceChecks=" + checks + " variant=" + variant
                    + " parentPid=" + Process.myPid() + " workerPid=" + worker + " modelRequests=0 wechatWrites=0 rawChatFiles=0");
        } catch (Throwable failure) {
            result.putString("result", "FAIL checks=" + checks + " type=" + failure.getClass().getSimpleName() + " check=" + failure.getMessage());
        } finally {
            if (receiverRegistered) getTargetContext().unregisterReceiver(receiver);
            runOnMainSync(() -> {
                CaptureControl.stopAll(getTargetContext());
                if (assistant != null) assistant.finish();
                if (main != null) main.finish();
            });
        }
        Log.i("OwnerPerformance", result.getString("result"));
        finish(result.getString("result").startsWith("PASS") ? -1 : 0, result);
    }

    private void measure(String stage, long duration) throws Exception {
        List<Long> queue = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean pending = new AtomicBoolean();
        AtomicBoolean measuring = new AtomicBoolean(true);
        AtomicLong mainStart = new AtomicLong();
        AtomicLong mainEnd = new AtomicLong();
        runOnMainSync(() -> mainStart.set(Debug.threadCpuTimeNanos()));
        long[] pssBefore = memory();
        long allocatedBefore = stat("art.gc.bytes-allocated");
        long gcBefore = stat("art.gc.gc-count");
        int frameStart = frames();
        long cpuStart = Process.getElapsedCpuTime();
        ProcessCpuSnapshot parentStart = ProcessCpuSnapshot.read(Process.myPid());
        ProcessCpuSnapshot workerStart = worker == 0 ? null : ProcessCpuSnapshot.read(worker);
        long started = SystemClock.elapsedRealtime();
        phase("MEASURE_" + stage);
        while (SystemClock.elapsedRealtime() - started < duration) {
            if (pending.compareAndSet(false, true)) {
                long posted = SystemClock.elapsedRealtimeNanos();
                handler.post(() -> {
                    if (measuring.get()) queue.add((SystemClock.elapsedRealtimeNanos() - posted) / 1000000);
                    pending.set(false);
                });
            }
            SystemClock.sleep(250);
        }
        long elapsed = SystemClock.elapsedRealtime() - started;
        long cpu = Process.getElapsedCpuTime() - cpuStart;
        ProcessCpuSnapshot parentEnd = ProcessCpuSnapshot.read(Process.myPid());
        ProcessCpuSnapshot workerEnd = worker == 0 ? null : ProcessCpuSnapshot.read(worker);
        long parentCpu = parentEnd.elapsedMillis(parentStart, ticksPerSecond);
        long workerCpu = worker == 0 ? 0 : workerEnd.elapsedMillis(workerStart, ticksPerSecond);
        require(parentEnd.startedTicks == parentIdentity.startedTicks && parentEnd.pid == parentIdentity.pid,
                "parent-identity-stable-" + stage);
        require(workerPid() == worker && (worker == 0 || workerEnd.startedTicks == workerIdentity.startedTicks),
                "worker-topology-stable-" + stage);
        runOnMainSync(() -> mainEnd.set(Debug.threadCpuTimeNanos()));
        measuring.set(false);
        long[] pssAfter = memory();
        List<Long> sorted;
        synchronized (queue) { sorted = new ArrayList<>(queue); }
        Collections.sort(sorted);
        long javaUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        Log.i("OwnerPerformance", String.format(Locale.ROOT,
                "METRIC variant=%s stage=%s wallMs=%d parentCpuMs=%d workerCpuMs=%d combinedCpuMs=%d combinedCorePct=%.2f parentApiCpuMs=%d mainCpuMs=%d parentPssStartKb=%d parentPssEndKb=%d workerPssStartKb=%d workerPssEndKb=%d combinedPssStartKb=%d combinedPssEndKb=%d parentJavaUsedKb=%d parentNativeAllocatedKb=%d parentAllocatedDeltaBytes=%d parentGcDelta=%d processingRounds=%d queueSamples=%d queueP50Ms=%d queueP95Ms=%d queueMaxMs=%d",
                variant, stage, elapsed, parentCpu, workerCpu, parentCpu + workerCpu, (parentCpu + workerCpu) * 100.0 / elapsed,
                cpu, (mainEnd.get() - mainStart.get()) / 1000000,
                pssBefore[0], pssAfter[0], pssBefore[1], pssAfter[1], pssBefore[0] + pssBefore[1], pssAfter[0] + pssAfter[1],
                javaUsed / 1024, Debug.getNativeHeapAllocatedSize() / 1024,
                delta(allocatedBefore, stat("art.gc.bytes-allocated")), delta(gcBefore, stat("art.gc.gc-count")), frames() - frameStart,
                sorted.size(), percentile(sorted, 0.5), percentile(sorted, 0.95), sorted.isEmpty() ? -1 : sorted.get(sorted.size() - 1)));
        require(AssistantCaptureService.running(), "capture-alive-" + stage);
    }

    private long[] memory() {
        int[] pids = worker == 0 ? new int[]{Process.myPid()} : new int[]{Process.myPid(), worker};
        Debug.MemoryInfo[] memory = getTargetContext().getSystemService(ActivityManager.class).getProcessMemoryInfo(pids);
        require(memory.length == pids.length && memory[0].getTotalPss() > 0
                && (worker == 0 || memory[1].getTotalPss() > 0), "valid-own-process-memory");
        return new long[]{memory[0].getTotalPss(), worker == 0 ? 0 : memory[1].getTotalPss()};
    }

    private int workerPid() {
        List<ActivityManager.RunningAppProcessInfo> processes = getTargetContext().getSystemService(ActivityManager.class).getRunningAppProcesses();
        if (processes == null) return -1;
        List<ActivityManager.RunningAppProcessInfo> owned = new ArrayList<>();
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process.uid == Process.myUid() && process.processName.equals(getTargetContext().getPackageName() + ":projection")) {
                owned.add(process);
            }
        }
        if (owned.size() > 1) throw new AssertionError("multiple-workers");
        return owned.isEmpty() ? 0 : owned.get(0).pid;
    }

    private long percentile(List<Long> values, double percentile) {
        return values.isEmpty() ? -1 : values.get(Math.min(values.size() - 1, Math.max(0, (int) Math.ceil(values.size() * percentile) - 1)));
    }
    private long stat(String name) {
        try { return Long.parseLong(Debug.getRuntimeStat(name)); }
        catch (RuntimeException failure) { return -1; }
    }
    private long delta(long before, long after) { return before < 0 || after < 0 ? -1 : after - before; }
    private int frames() {
        try {
            Field instance = AssistantCaptureService.class.getDeclaredField("instance"); instance.setAccessible(true);
            Object service = instance.get(null);
            if (service == null) return -1;
            Field frameCount = AssistantCaptureService.class.getDeclaredField("frameCount"); frameCount.setAccessible(true);
            return frameCount.getInt(service);
        } catch (ReflectiveOperationException failure) { throw new AssertionError("fixture-frames", failure); }
    }
    private void phase(String phase) {
        Log.i("OwnerPerformance", "PHASE " + phase);
        Bundle status = new Bundle(); status.putString("phase", phase); sendStatus(1, status);
    }
    private void await(BooleanSupplier condition, long timeout, String label) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(40);
        require(condition.getAsBoolean(), label);
    }
    private void require(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
}

