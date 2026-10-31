package com.codextemp.wechatcaptureprobe;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.Instrumentation;
import android.os.Bundle;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class OwnExitTraceProbe extends Instrumentation {
    private int requestedPid;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        requestedPid = Integer.parseInt(arguments.getString("pid"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            ModelSettingsStore settings = new ModelSettingsStore(getTargetContext());
            if (settings.hasKey() || settings.cloudAllowed() || AssistantCaptureService.running() || CaptureService.running()) {
                throw new IllegalStateException("Read-only exit probe precondition failed");
            }
            String ownPackage = getTargetContext().getPackageName();
            List<ApplicationExitInfo> exits = getTargetContext().getSystemService(ActivityManager.class)
                    .getHistoricalProcessExitReasons(ownPackage, requestedPid, 4);
            int traceLines = 0;
            int entries = 0;
            int traceStreams = 0;
            int matchedSections = 0;
            for (ApplicationExitInfo exit : exits) {
                if (exit.getPid() != requestedPid || !ownPackage.equals(exit.getProcessName())) continue;
                entries++;
                Log.i("OwnExitProbe", "EXIT pid=" + requestedPid + " reason=" + exit.getReason() + " timestamp=" + exit.getTimestamp());
                try (InputStream input = exit.getTraceInputStream()) {
                    if (input == null) continue;
                    traceStreams++;
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                        boolean ownProcess = false;
                        boolean mainThread = false;
                        String line;
                        while ((line = reader.readLine()) != null && traceLines < 30) {
                            if (line.startsWith("----- pid ")) {
                                ownProcess = line.startsWith("----- pid " + requestedPid + " ");
                                if (ownProcess) matchedSections++;
                                mainThread = false;
                            }
                            if (!ownProcess) continue;
                            if (line.startsWith("\"")) mainThread = line.startsWith("\"main\"");
                            if (mainThread && (line.trim().startsWith("at ") || line.trim().startsWith("native: #"))) {
                                Log.i("OwnExitProbe", "MAIN_STACK " + line.trim());
                                traceLines++;
                            }
                        }
                    }
                }
            }
            result.putString("result", "READ ownExitEntries=" + entries + " traceStreams=" + traceStreams
                    + " matchedOwnSections=" + matchedSections + " ownMainStackLines=" + traceLines
                    + " requestedPid=" + requestedPid + " rawTraceFiles=0 chatFiles=0 modelRequests=0");
        } catch (Throwable failure) {
            result.putString("result", "FAIL type=" + failure.getClass().getSimpleName());
        }
        finish(result.getString("result").startsWith("READ") ? -1 : 0, result);
    }
}
