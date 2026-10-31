package com.codextemp.wechatcaptureprobe;

import android.app.ActivityManager;
import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import android.view.Surface;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ProjectionSession {
    interface Listener {
        void ready();
        void stopped(String reason);
        void source(int width, int height, boolean visible);
    }

    private static volatile ProjectionSession current;
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService transport = Executors.newSingleThreadExecutor();
    private final String session = UUID.randomUUID().toString();
    private final Listener listener;
    private final String name;
    private final int resultCode;
    private Intent grant;
    private IProjectionHost host;
    private IBinder hostBinder;
    private boolean bound;
    private boolean retired;
    private volatile boolean closed;
    private volatile boolean applied;
    private volatile boolean sourceBlocked;
    private volatile long revision;
    private long sourceVersion = -1;
    private volatile ScreenGeometry geometry;
    private Surface surface;
    private int hostPid;
    private int displayId = -1;
    private boolean resourcesReleased;
    private int stopReason;
    private boolean outputAttached;
    private final IProjectionEvents events = new IProjectionEvents.Stub() {
        @Override public void stopped(String owner, int reason, boolean tokenReleased, boolean surfaceReleased) {
            if (!session.equals(owner)) {
                return;
            }
            applied = false;
            handler.post(() -> {
                resourcesReleased = tokenReleased && surfaceReleased;
                stopReason = reason;
                Log.i("ProjectionClient", "STOP reason=" + reason + " resourcesReleased=" + resourcesReleased);
                if (!closed) {
                    fail("共享已结束或采集进程不可用，请重新开启");
                }
            });
        }

        @Override public void source(String owner, long version, long source, int width, int height, boolean visible) {
            if (session.equals(owner) && version == revision) {
                sourceBlocked = blockedSource(width, height, visible);
                handler.post(() -> acceptSource(version, source, width, height, visible));
            }
        }
    };
    private final IBinder.DeathRecipient death = () -> {
        applied = false;
        handler.post(this::hostDied);
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName component, IBinder binder) {
            if (retired) {
                return;
            }
            hostBinder = binder;
            host = IProjectionHost.Stub.asInterface(binder);
            try {
                binder.linkToDeath(death, 0);
            } catch (RemoteException failure) {
                hostDied();
                return;
            }
            if (closed) {
                releaseRemote();
                return;
            }
            Intent permission = grant;
            grant = null;
            send(true, permission);
        }
        @Override public void onServiceDisconnected(ComponentName component) {
            applied = false;
            hostDied();
        }
        @Override public void onBindingDied(ComponentName component) {
            applied = false;
            fail("采集连接已失效，未自动恢复；请重新开启");
            forceRetire();
        }
        @Override public void onNullBinding(ComponentName component) {
            fail("采集进程连接失败，请重新开启");
        }
    };

    static boolean available() {
        return current == null;
    }

    ProjectionSession(Context context, int resultCode, Intent grant, String name, ScreenGeometry geometry,
            Surface surface, int notificationId, Notification notification, Listener listener) {
        if (Looper.myLooper() != Looper.getMainLooper() || current != null) {
            throw new IllegalStateException("Projection cleanup pending");
        }
        this.context = context.getApplicationContext();
        this.resultCode = resultCode;
        this.grant = grant;
        this.name = name;
        this.geometry = geometry;
        this.surface = surface;
        this.listener = listener;
        current = this;
        try {
            Intent start = new Intent(context, ProjectionHostService.class).putExtra("session", session)
                    .putExtra("notificationId", notificationId).putExtra("notification", notification);
            this.context.startForegroundService(start);
            bound = this.context.bindService(new Intent(this.context, ProjectionHostService.class), connection, Context.BIND_AUTO_CREATE);
            if (!bound) {
                throw new IllegalStateException("Projection binding unavailable");
            }
            timeout(0);
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    boolean ready() {
        return !closed && applied && !sourceBlocked && hostBinder != null && hostBinder.isBinderAlive();
    }

    long version() { return revision; }

    void update(ScreenGeometry next, Surface output) {
        if (closed) {
            throw new IllegalStateException("Projection session ended");
        }
        geometry = next;
        surface = output;
        revision++;
        applied = false;
        if (host != null) {
            send(false, null);
        }
        timeout(revision);
    }

    private void send(boolean opening, Intent permission) {
        long version = revision;
        ScreenGeometry dimensions = geometry;
        Surface output = duplicate(surface);
        IProjectionHost target = host;
        transport.execute(() -> {
            try {
                if (closed) {
                    return;
                }
                Bundle state = opening
                        ? target.open(session, version, resultCode, permission, name, dimensions.width,
                                dimensions.height, dimensions.densityDpi, output, events)
                        : target.update(session, version, dimensions.width, dimensions.height, dimensions.densityDpi, output);
                handler.post(() -> accept(version, state, opening));
            } catch (RemoteException | RuntimeException failure) {
                applied = false;
                handler.post(() -> fail("采集进程通信失败，请重新开启"));
            } finally {
                if (output != null) {
                    output.release();
                }
            }
        });
    }

    private static Surface duplicate(Surface surface) {
        if (surface == null) {
            return null;
        }
        Parcel parcel = Parcel.obtain();
        try {
            surface.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return Surface.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    private void accept(long version, Bundle state, boolean opening) {
        if (closed || state == null) {
            return;
        }
        hostPid = state.getInt("hostPid");
        displayId = state.getInt("displayId", -1);
        if (hostPid == Process.myPid() || hostPid <= 0 || displayId < 0 || state.getLong("revision", -1) != version) {
            fail("采集进程状态不一致，请重新开启");
            return;
        }
        if (version == revision) {
            applied = true;
            outputAttached = state.getBoolean("outputAttached");
            acceptSource(version, state.getLong("sourceVersion"), state.getInt("sourceWidth"),
                    state.getInt("sourceHeight"), state.getBoolean("sourceVisible", true));
        }
        if (opening) {
            listener.ready();
        }
    }

    private void acceptSource(long version, long sequence, int width, int height, boolean visible) {
        if (!closed && version == revision && sequence >= sourceVersion) {
            sourceVersion = sequence;
            sourceBlocked = blockedSource(width, height, visible);
            listener.source(width, height, visible);
        }
    }

    private boolean blockedSource(int width, int height, boolean visible) {
        ScreenGeometry current = geometry;
        return !visible || Build.VERSION.SDK_INT >= 34 && width > 0 && height > 0
                && (width != current.width || height != current.height);
    }

    void requestStop() {
        if (!closed && host != null) {
            transport.execute(() -> {
                try {
                    host.requestStop(session);
                } catch (RemoteException | RuntimeException failure) {
                    handler.post(() -> fail("共享停止失败，已清理本机采集"));
                }
            });
        }
    }

    void close() {
        if (closed) {
            return;
        }
        closed = true;
        applied = false;
        grant = null;
        surface = null;
        if (host != null) {
            releaseRemote();
        }
        handler.postDelayed(this::forceRetire, 2500);
    }

    private void releaseRemote() {
        IProjectionHost target = host;
        transport.execute(() -> {
            try {
                target.close(session);
            } catch (RemoteException | RuntimeException failure) {
                Log.i("ProjectionClient", "HOST_ALREADY_CLOSED");
            }
        });
    }

    private void timeout(long version) {
        handler.postDelayed(() -> {
            if (!closed && revision == version && !applied) {
                fail("采集进程响应超时，已停止；请重新开启");
                forceRetire();
            }
        }, 3500);
    }

    private void fail(String reason) {
        if (!closed) {
            close();
            listener.stopped(reason);
        }
    }

    private void hostDied() {
        if (retired) {
            return;
        }
        boolean unexpected = !closed;
        closed = true;
        applied = false;
        retire();
        if (unexpected) {
            listener.stopped("采集进程已结束，未自动恢复；请重新授权开启");
        }
    }

    private void forceRetire() {
        if (retired || current != this) {
            return;
        }
        context.stopService(new Intent(context, ProjectionHostService.class));
        detach();
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        java.util.List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes != null) {
            for (ActivityManager.RunningAppProcessInfo process : processes) {
                if (process.uid == Process.myUid() && process.pid != Process.myPid()
                        && process.processName.equals(context.getPackageName() + ":projection")) {
                    Process.killProcess(process.pid);
                }
            }
        }
        handler.postDelayed(this::confirmGone, 350);
    }

    private void confirmGone() {
        if (retired || current != this) {
            return;
        }
        java.util.List<ActivityManager.RunningAppProcessInfo> processes = context.getSystemService(ActivityManager.class).getRunningAppProcesses();
        if (processes != null && processes.stream().noneMatch(process -> process.uid == Process.myUid()
                && process.processName.equals(context.getPackageName() + ":projection"))) {
            retire();
        } else {
            handler.postDelayed(this::confirmGone, 500);
        }
    }

    private void retire() {
        retired = true;
        handler.removeCallbacksAndMessages(null);
        detach();
        transport.shutdown();
        if (current == this) {
            current = null;
        }
        grant = null;
        surface = null;
        Log.i("ProjectionClient", "RETIRED parentPid=" + Process.myPid() + " hostPid=" + hostPid);
    }

    private void detach() {
        if (bound) {
            bound = false;
            context.unbindService(connection);
        }
    }
}
