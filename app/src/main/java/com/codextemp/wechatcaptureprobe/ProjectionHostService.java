package com.codextemp.wechatcaptureprobe;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import android.view.Surface;

public final class ProjectionHostService extends Service {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String session;
    private boolean retiring;
    private MediaProjection projection;
    private MediaProjection.Callback callback;
    private VirtualDisplay display;
    private Surface output;
    private IProjectionEvents events;
    private IBinder.DeathRecipient parentDeath;
    private int width;
    private int height;
    private int densityDpi;
    private long revision;
    private long sourceVersion;
    private int sourceWidth;
    private int sourceHeight;
    private boolean sourceVisible = true;
    private final IProjectionHost.Stub binder = new IProjectionHost.Stub() {
        @Override public Bundle open(String owner, long version, int resultCode, Intent grant, String name,
                int horizontal, int vertical, int density, Surface surface, IProjectionEvents listener) throws RemoteException {
            enforceCaller();
            synchronized (ProjectionHostService.this) {
                int openingStage = 0;
                try {
                    requireOwner(owner);
                    openingStage = 1;
                    if (projection != null || resultCode != Activity.RESULT_OK || grant == null || listener == null
                            || !("WechatAssistant".equals(name) || "WechatCaptureProbe".equals(name))) {
                        throw new IllegalStateException("Projection startup rejected");
                    }
                    validateGeometry(horizontal, vertical, density);
                    events = listener;
                    parentDeath = () -> handler.post(() -> finishSession(3));
                    listener.asBinder().linkToDeath(parentDeath, 0);
                    openingStage = 2;
                    projection = getSystemService(MediaProjectionManager.class).getMediaProjection(resultCode, grant);
                    openingStage = 3;
                    callback = new MediaProjection.Callback() {
                        @Override public void onStop() {
                            finishSession(1);
                        }
                        @Override public void onCapturedContentResize(int horizontal, int vertical) {
                            synchronized (ProjectionHostService.this) {
                                sourceWidth = horizontal;
                                sourceHeight = vertical;
                                reportSource();
                            }
                        }
                        @Override public void onCapturedContentVisibilityChanged(boolean visible) {
                            synchronized (ProjectionHostService.this) {
                                sourceVisible = visible;
                                reportSource();
                            }
                        }
                    };
                    projection.registerCallback(callback, handler);
                    revision = version;
                    width = horizontal;
                    height = vertical;
                    densityDpi = density;
                    output = surface;
                    openingStage = 4;
                    display = projection.createVirtualDisplay(name, width, height, densityDpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, output, null, handler);
                    if (display == null) {
                        throw new IllegalStateException("Projection display unavailable");
                    }
                    Log.i("ProjectionHost", "OPEN pid=" + Process.myPid() + " displayId=" + display.getDisplay().getDisplayId());
                    return state();
                } catch (RuntimeException | RemoteException failure) {
                    // 只记录阶段与异常类型，避免把系统异常消息中的凭据或内容写入日志。
                    Log.w("ProjectionHost", "OPEN_REJECTED stage=" + openingStage + " type=" + failure.getClass().getSimpleName());
                    finishSession(4);
                    throw failure;
                } finally {
                    if (surface != null && surface != output) {
                        surface.release();
                    }
                }
            }
        }

        @Override public Bundle update(String owner, long version, int horizontal, int vertical, int density, Surface surface) {
            enforceCaller();
            synchronized (ProjectionHostService.this) {
                try {
                    requireOwner(owner);
                    validateGeometry(horizontal, vertical, density);
                    if (display == null || version <= revision || surface != null && !surface.isValid()) {
                        throw new IllegalStateException("Projection update rejected");
                    }
                    if (width != horizontal || height != vertical || densityDpi != density) {
                        display.resize(horizontal, vertical, density);
                    }
                    Surface previous = output;
                    display.setSurface(surface);
                    output = surface;
                    if (previous != null) {
                        previous.release();
                    }
                    width = horizontal;
                    height = vertical;
                    densityDpi = density;
                    revision = version;
                    return state();
                } catch (RuntimeException failure) {
                    finishSession(4);
                    throw failure;
                } finally {
                    if (surface != null && surface != output) {
                        surface.release();
                    }
                }
            }
        }

        @Override public void requestStop(String owner) {
            enforceCaller();
            synchronized (ProjectionHostService.this) {
                if (!retiring && owner != null && owner.equals(session) && projection != null) {
                    projection.stop();
                }
            }
        }

        @Override public void close(String owner) {
            enforceCaller();
            synchronized (ProjectionHostService.this) {
                if (owner != null && owner.equals(session)) {
                    finishSession(2);
                }
            }
        }
    };

    @Override public synchronized int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || retiring || session != null) {
            finishSession(4);
            return START_NOT_STICKY;
        }
        try {
            Notification notification = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra("notification", Notification.class) : intent.getParcelableExtra("notification");
            session = intent.getStringExtra("session");
            int notificationId = intent.getIntExtra("notificationId", 0);
            if (session == null || notification == null || notificationId != 1 && notificationId != 10) {
                throw new IllegalArgumentException("Projection foreground context unavailable");
            }
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(notificationId, notification);
            }
            handler.postDelayed(() -> {
                synchronized (ProjectionHostService.this) {
                    if (projection == null && !retiring) {
                        finishSession(4);
                    }
                }
            }, 5000);
        } catch (RuntimeException failure) {
            finishSession(4);
        }
        return START_NOT_STICKY;
    }

    private void enforceCaller() {
        if (Binder.getCallingUid() != Process.myUid()) {
            throw new SecurityException("Projection caller rejected");
        }
    }

    private void requireOwner(String owner) {
        if (retiring || owner == null || !owner.equals(session)) {
            throw new IllegalStateException("Projection owner changed");
        }
    }

    private static void validateGeometry(int width, int height, int density) {
        if (width <= 0 || height <= 0 || width > 16384 || height > 16384 || density < 72 || density > 2000) {
            throw new IllegalArgumentException("Projection geometry rejected");
        }
    }

    private Bundle state() {
        Bundle result = new Bundle();
        result.putLong("revision", revision);
        result.putInt("displayId", display.getDisplay().getDisplayId());
        result.putInt("hostPid", Process.myPid());
        result.putBoolean("outputAttached", display.getSurface() != null && display.getSurface().isValid());
        result.putLong("sourceVersion", sourceVersion);
        result.putInt("sourceWidth", sourceWidth);
        result.putInt("sourceHeight", sourceHeight);
        result.putBoolean("sourceVisible", sourceVisible);
        return result;
    }

    private void reportSource() {
        if (retiring || events == null) {
            return;
        }
        sourceVersion++;
        try {
            events.source(session, revision, sourceVersion, sourceWidth, sourceHeight, sourceVisible);
        } catch (RemoteException failure) {
            finishSession(3);
        }
    }

    private synchronized void finishSession(int reason) {
        if (retiring) {
            return;
        }
        retiring = true;
        boolean tokenReleased = display == null;
        boolean surfaceReleased = output == null;
        try {
            if (display != null) {
                display.release();
                tokenReleased = display.toString().contains("token=null");
                display = null;
            }
        } catch (RuntimeException failure) {
            Log.w("ProjectionHost", "RELEASE_DISPLAY_FAILED");
        }
        try {
            if (output != null) {
                output.release();
                surfaceReleased = !output.isValid();
                output = null;
            }
        } finally {
            if (projection != null) {
                try {
                    if (callback != null) {
                        projection.unregisterCallback(callback);
                    }
                    projection.stop();
                } catch (RuntimeException failure) {
                    Log.w("ProjectionHost", "RELEASE_PROJECTION_FAILED");
                }
                projection = null;
                callback = null;
            }
            if (events != null) {
                try {
                    events.stopped(session, reason, tokenReleased, surfaceReleased);
                    if (parentDeath != null) {
                        events.asBinder().unlinkToDeath(parentDeath, 0);
                    }
                } catch (RemoteException | RuntimeException failure) {
                    Log.w("ProjectionHost", "OWNER_ALREADY_GONE");
                }
            }
            events = null;
            Log.i("ProjectionHost", "CLOSE reason=" + reason + " tokenReleased=" + tokenReleased + " surfaceReleased=" + surfaceReleased);
            handler.post(() -> {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                handler.postDelayed(() -> {
                    if (ownWorkerProcess()) {
                        Process.killProcess(Process.myPid());
                    }
                }, 200);
            });
        }
    }

    private boolean ownWorkerProcess() {
        if (Build.VERSION.SDK_INT >= 28) {
            return (getPackageName() + ":projection").equals(Application.getProcessName());
        }
        ActivityManager manager = getSystemService(ActivityManager.class);
        java.util.List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        return processes != null && processes.stream().anyMatch(process -> process.pid == Process.myPid()
                && process.uid == Process.myUid() && process.processName.equals(getPackageName() + ":projection"));
    }

    @Override public void onDestroy() {
        finishSession(2);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return binder;
    }
}
