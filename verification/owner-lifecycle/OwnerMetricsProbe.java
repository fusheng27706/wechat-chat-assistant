package com.codextemp.wechatcaptureprobe;

import android.app.ActivityManager;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public final class OwnerMetricsProbe extends Instrumentation {
    private int checks;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Intent host = new Intent().setClassName(getTargetContext().getPackageName(),
                getTargetContext().getPackageName() + ".ProjectionHostService");
        try {
            ModelSettingsStore settings = new ModelSettingsStore(getTargetContext());
            require(!settings.hasKey() && !settings.cloudAllowed() && !AssistantCaptureService.running()
                    && !CaptureService.running() && workerPid() == 0, "idle-no-model-precondition");
            require(getTargetContext().getApplicationInfo().uid == Process.myUid(), "probe-runs-as-own-app-uid");
            long ticksPerSecond = Os.sysconf(OsConstants._SC_CLK_TCK);
            require(ticksPerSecond > 0, "real-clock-tick-rate");
            NotificationManager notifications = getTargetContext().getSystemService(NotificationManager.class);
            notifications.createNotificationChannel(new NotificationChannel("metric-preflight", "临时资源计数验证", NotificationManager.IMPORTANCE_LOW));
            Notification notification = new Notification.Builder(getTargetContext(), "metric-preflight")
                    .setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("临时自有进程计数验证")
                    .setContentText("未申请共享，不读取屏幕；完成后自动结束").build();
            getTargetContext().startForegroundService(new Intent(host).putExtra("session", UUID.randomUUID().toString())
                    .putExtra("notificationId", 10).putExtra("notification", notification));
            await(() -> workerPid() > 0, 4000, "own-worker-started-without-projection");
            int worker = workerPid();
            long beforeApi = Process.getElapsedCpuTime();
            ProcessCpuSnapshot parent = ProcessCpuSnapshot.read(Process.myPid());
            long afterApi = Process.getElapsedCpuTime();
            long parentCpu = parent.ticks * 1000L / ticksPerSecond;
            require(parentCpu >= beforeApi - 30 && parentCpu <= afterApi + 30, "parent-stat-agrees-with-public-process-api");
            ProcessCpuSnapshot workerStart = ProcessCpuSnapshot.read(worker);
            SystemClock.sleep(350);
            ProcessCpuSnapshot workerEnd = ProcessCpuSnapshot.read(worker);
            require(workerEnd.elapsedMillis(workerStart, ticksPerSecond) >= 0, "own-worker-proc-counters-readable-and-stable");
            Debug.MemoryInfo[] memory = getTargetContext().getSystemService(ActivityManager.class)
                    .getProcessMemoryInfo(new int[]{Process.myPid(), worker});
            require(memory.length == 2 && memory[0].getTotalPss() > 0 && memory[1].getTotalPss() > 0, "both-own-process-pss-readable");
            require(Arrays.stream(getTargetContext().getSystemService(DisplayManager.class).getDisplays())
                    .noneMatch(display -> display.getName().equals("WechatAssistant") || display.getName().equals("WechatCaptureProbe")), "no-projection-was-created");
            getTargetContext().stopService(host);
            await(() -> workerPid() == 0, 5000, "preflight-worker-cleaned-up");
            result.putString("result", "PASS metricsProbeChecks=" + checks + " parentPid=" + Process.myPid() + " workerPid=" + worker
                    + " clockTicksPerSecond=" + ticksPerSecond + " parentPssKb=" + memory[0].getTotalPss()
                    + " workerPssKb=" + memory[1].getTotalPss() + " normalSystemGrants=0 projectionStarts=0 modelRequests=0 chatFiles=0");
        } catch (Throwable failure) {
            result.putString("result", "FAIL metricsProbeChecks=" + checks + " type=" + failure.getClass().getSimpleName()
                    + (failure instanceof AssertionError ? " check=" + failure.getMessage() : ""));
        } finally {
            getTargetContext().stopService(host);
        }
        finish(result.getString("result").startsWith("PASS") ? -1 : 0, result);
    }

    private int workerPid() {
        List<ActivityManager.RunningAppProcessInfo> processes = getTargetContext().getSystemService(ActivityManager.class).getRunningAppProcesses();
        if (processes == null) return -1;
        return processes.stream().filter(process -> process.uid == Process.myUid() && process.pid != Process.myPid()
                && process.processName.equals(getTargetContext().getPackageName() + ":projection"))
                .mapToInt(process -> process.pid).findFirst().orElse(0);
    }

    private void await(BooleanSupplier condition, long timeout, String name) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(40);
        require(condition.getAsBoolean(), name);
    }

    private void require(boolean condition, String name) { checks++; if (!condition) throw new AssertionError(name); }
}
