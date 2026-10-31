package com.codextemp.projectionprocessverify;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.ResultReceiver;
import android.view.Surface;

public final class ProjectionHostService extends Service {
    static final String START = "start";
    static final String STOP = "stop";
    static final String EXIT = "exit";
    static final String CRASH = "crash-fixture-only";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final IBinder liveness = new Binder();
    private MediaProjection projection;
    private MediaProjection.Callback callback;
    private VirtualDisplay display;
    private Surface output;
    private ResultReceiver receiver;
    private int displayId = -1;
    private boolean tokenReleased;
    private boolean surfaceReleased;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (CRASH.equals(action)) {
            int parent = intent.getIntExtra("parentPid", -1);
            boolean helper = Build.VERSION.SDK_INT >= 28
                    && Application.getProcessName().equals(getPackageName() + ":projection")
                    && parent > 0 && parent != Process.myPid();
            if (helper && projection != null && display != null) {
                Process.killProcess(Process.myPid());
            } else {
                send("FAIL_CRASH_GUARD");
            }
            return START_NOT_STICKY;
        }
        if (EXIT.equals(action)) {
            receiver = intent.getParcelableExtra("receiver");
            int parent = intent.getIntExtra("parentPid", -1);
            boolean helper = Build.VERSION.SDK_INT >= 28
                    && Application.getProcessName().equals(getPackageName() + ":projection")
                    && parent > 0 && parent != Process.myPid();
            if (!helper || projection != null || display != null || output != null) {
                send("FAIL_EXIT_GUARD");
                stopSelf();
                return START_NOT_STICKY;
            }
            send("EXITING_HELPER_ONLY");
            stopSelf();
            handler.postDelayed(() -> Process.killProcess(Process.myPid()), 150);
            return START_NOT_STICKY;
        }
        if (STOP.equals(action)) {
            if (projection == null) {
                send("FAIL_NO_PROJECTION");
            } else {
                projection.stop();
            }
            return START_NOT_STICKY;
        }
        if (!START.equals(action) || projection != null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        receiver = intent.getParcelableExtra("receiver");
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel("fixture", "临时共享进程验证", NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, "fixture")
                .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("临时虚构画面验证")
                .setContentText("只验证本测试页，完成后结束共享并卸载").build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(81, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(81, notification);
            }
            output = intent.getParcelableExtra("surface");
            Intent grant = intent.getParcelableExtra("grant");
            projection = getSystemService(MediaProjectionManager.class)
                    .getMediaProjection(intent.getIntExtra("resultCode", 0), grant);
            callback = new MediaProjection.Callback() {
                @Override public void onStop() {
                    release(false);
                    send("PROJECTION_STOPPED");
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
            };
            projection.registerCallback(callback, handler);
            display = projection.createVirtualDisplay("ProjectionProcessFixture",
                    intent.getIntExtra("width", 0), intent.getIntExtra("height", 0), intent.getIntExtra("dpi", 0),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, output, null, handler);
            if (display == null) {
                throw new IllegalStateException("No virtual display");
            }
            displayId = display.getDisplay().getDisplayId();
            send("STARTED");
        } catch (RuntimeException failure) {
            release(true);
            send("FAIL_START_" + failure.getClass().getSimpleName());
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void release(boolean stopProjection) {
        if (display != null) {
            display.release();
            tokenReleased = display.toString().contains("token=null");
            display = null;
        }
        if (output != null) {
            output.release();
            surfaceReleased = !output.isValid();
            output = null;
        }
        if (projection != null) {
            if (callback != null) {
                projection.unregisterCallback(callback);
                callback = null;
            }
            if (stopProjection) {
                projection.stop();
            }
            projection = null;
        }
    }

    private void send(String event) {
        if (receiver == null) {
            return;
        }
        Bundle result = new Bundle();
        result.putString("event", event);
        result.putInt("hostPid", Process.myPid());
        result.putInt("displayId", displayId);
        result.putBoolean("tokenReleased", tokenReleased);
        result.putBoolean("surfaceReleased", surfaceReleased);
        result.putBinder("liveness", liveness);
        receiver.send(0, result);
    }

    @Override public void onDestroy() {
        release(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
