package com.codextemp.wechatcaptureprobe;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;

final class CaptureControl {
    static void stopAll(Context context) {
        AssistantCaptureService.stopCurrent();
        CaptureService.stopCurrent();
        context.stopService(new Intent(context, AssistantCaptureService.class));
        context.stopService(new Intent(context, CaptureService.class));
        AssistantState.clear();
        AssistantState.status = "已停止所有采集并清空";
        ProbeState.clear();
        context.getSystemService(NotificationManager.class).cancel(2);
    }

    private CaptureControl() {
    }
}
