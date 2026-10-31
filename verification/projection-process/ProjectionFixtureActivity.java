package com.codextemp.projectionprocessverify;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.nio.ByteBuffer;

public final class ProjectionFixtureActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int parentPid = Process.myPid();
    private final ResultReceiver receiver = new ResultReceiver(handler) {
        @Override protected void onReceiveResult(int code, Bundle result) {
            receive(result);
        }
    };
    private TextView status;
    private ImageReader reader;
    private Surface parentSurface;
    private int hostPid;
    private int previousHostPid;
    private int displayId = -1;
    private int previousDisplayId = -1;
    private int rounds;
    private int completed;
    private int checks;
    private int actualStops;
    private int abruptDeaths;
    private int matchedFrames;
    private boolean running;
    private boolean stopped;
    private boolean held;
    private boolean failed;
    private boolean deathObserved;
    private boolean expectedCrash;
    private IBinder liveness;
    private long startedAt;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(28, 28, 28, 28);
        page.setBackgroundColor(Color.rgb(34, 102, 170));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(18);
        page.addView(status);
        add(page, "开始临时共享验证", this::startRound);
        add(page, "停止投射（先保留独立进程）", this::stopProjection);
        add(page, "结束已停止的独立采集进程", this::exitHost);
        add(page, "模拟采集子进程意外退出（仅第三轮）", this::crashHost);
        setContentView(page);
        show("仅显示虚构纯色，不打开微信；需要正常系统共享授权。主进程将持续存活。");
        Log.i("ProjectionProcessVerification", "READY parentPid=" + parentPid);
    }

    private void add(LinearLayout page, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> {
            try {
                action.run();
            } catch (RuntimeException failure) {
                fail("button-" + failure.getClass().getSimpleName());
            }
        });
        page.addView(button);
    }

    private void startRound() {
        if (failed || running || rounds != completed || rounds >= 3) {
            return;
        }
        check(Process.myPid() == parentPid, "parent-pid-before-grant");
        check(!displayPresent(), "no-previous-display-before-grant");
        startedAt = SystemClock.elapsedRealtime();
        startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(), 81);
    }

    @Override protected void onActivityResult(int request, int code, Intent data) {
        super.onActivityResult(request, code, data);
        if (request != 81) {
            return;
        }
        if (code != RESULT_OK || data == null) {
            show("授权已取消，没有启动共享。");
            return;
        }
        try {
            DisplayMetrics metrics = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
            reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 3);
            parentSurface = reader.getSurface();
            reader.setOnImageAvailableListener(this::inspect, handler);
            rounds++;
            matchedFrames = 0;
            running = true;
            stopped = false;
            held = false;
            deathObserved = false;
            expectedCrash = false;
            Intent intent = new Intent(this, ProjectionHostService.class).setAction(ProjectionHostService.START)
                    .putExtra("grant", data).putExtra("resultCode", code).putExtra("receiver", receiver)
                    .putExtra("surface", parentSurface).putExtra("width", metrics.widthPixels)
                    .putExtra("height", metrics.heightPixels).putExtra("dpi", metrics.densityDpi);
            startForegroundService(intent);
            show("临时共享已启动，等待实际纯色帧。");
            int round = rounds;
            handler.postDelayed(() -> {
                if (!failed && running && !stopped && rounds == round && matchedFrames < 3) {
                    fail("no-real-fixture-frames-before-timeout");
                }
            }, 15000);
        } catch (RuntimeException failure) {
            fail("grant-start-" + failure.getClass().getSimpleName());
        }
    }

    private void inspect(ImageReader source) {
        if (source != reader || failed) {
            return;
        }
        Image image = source.acquireLatestImage();
        if (image == null) {
            return;
        }
        try {
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer pixels = plane.getBuffer();
            int offset = image.getHeight() * 3 / 4 * plane.getRowStride()
                    + image.getWidth() / 2 * plane.getPixelStride();
            boolean matched = Math.abs((pixels.get(offset) & 255) - 34) <= 6
                    && Math.abs((pixels.get(offset + 1) & 255) - 102) <= 6
                    && Math.abs((pixels.get(offset + 2) & 255) - 170) <= 6;
            if (matched && ++matchedFrames == 3) {
                check(parentPid != hostPid && hostPid > 0, "separate-host-process");
                check(displayPresent(), "real-shared-frames-with-display");
                Log.i("ProjectionProcessVerification", "ACTUAL_FRAMES round=" + rounds + " matchedFrames=" + matchedFrames
                        + " parentPid=" + parentPid + " hostPid=" + hostPid + " displayId=" + displayId);
                show("已收到实际纯色帧，可点击停止投射；不会结束主进程。");
            }
        } finally {
            image.close();
        }
    }

    private void stopProjection() {
        if (failed || !running || stopped || matchedFrames < 3 || rounds > 2) {
            return;
        }
        check(displayPresent(), "display-present-before-real-stop");
        startService(new Intent(this, ProjectionHostService.class).setAction(ProjectionHostService.STOP));
    }

    private void receive(Bundle result) {
        if (failed) {
            return;
        }
        String event = result.getString("event", "");
        if (event.startsWith("FAIL")) {
            fail(event);
            return;
        }
        if (event.equals("STARTED")) {
            hostPid = result.getInt("hostPid");
            displayId = result.getInt("displayId", -1);
            check(hostPid != parentPid && displayId > 0, "host-started-with-own-display");
            liveness = result.getBinder("liveness");
            check(liveness != null && liveness.isBinderAlive(), "live-helper-binder");
            try {
                liveness.linkToDeath(() -> handler.post(this::hostDied), 0);
            } catch (android.os.RemoteException failure) {
                fail("helper-died-before-observer");
                return;
            }
            if (rounds > 1) {
                check(hostPid != previousHostPid, "fresh-helper-process");
                check(displayId != previousDisplayId, "fresh-display-after-new-grant");
            }
        } else if (event.equals("PROJECTION_STOPPED")) {
            check(rounds <= 2 && !stopped, "expected-normal-stop-round");
            actualStops++;
            check(result.getBoolean("tokenReleased"), "client-token-released");
            check(result.getBoolean("surfaceReleased"), "host-surface-released");
            stopped = true;
            closeReader();
            check(parentSurface != null && !parentSurface.isValid(), "parent-reader-surface-closed");
            handler.postDelayed(() -> {
                check(Process.myPid() == parentPid, "parent-alive-before-helper-exit");
                held = true;
                Log.i("ProjectionProcessVerification", "HOLD round=" + rounds + " displayPresent=" + displayPresent()
                        + " parentPid=" + parentPid + " hostPid=" + hostPid + " displayId=" + displayId);
                show("投射已停止且两端 Surface 已释放；当前显示记录存在=" + displayPresent()
                        + "。可结束已停止的独立进程。");
            }, 2000);
        } else if (event.equals("EXITING_HELPER_ONLY")) {
            check(result.getInt("hostPid") == hostPid, "only-original-helper-exits");
            waitForRemoval(SystemClock.elapsedRealtime() + 5000);
        }
    }

    private void exitHost() {
        if (failed || !running || !stopped || !held || rounds > 2) {
            return;
        }
        check(Process.myPid() == parentPid, "parent-alive-at-exit-request");
        startService(new Intent(this, ProjectionHostService.class).setAction(ProjectionHostService.EXIT)
                .putExtra("parentPid", parentPid).putExtra("receiver", receiver));
    }

    private void crashHost() {
        if (failed || !running || stopped || rounds != 3 || matchedFrames < 3) {
            return;
        }
        check(displayPresent() && liveness != null && liveness.isBinderAlive(), "live-display-before-abrupt-death");
        expectedCrash = true;
        startService(new Intent(this, ProjectionHostService.class).setAction(ProjectionHostService.CRASH)
                .putExtra("parentPid", parentPid));
    }

    private void hostDied() {
        if (failed) {
            return;
        }
        deathObserved = true;
        check(Process.myPid() == parentPid, "parent-alive-at-binder-death");
        if (expectedCrash) {
            abruptDeaths++;
            check(!stopped && reader != null, "abrupt-death-before-normal-cleanup");
            stopped = true;
            closeReader();
            check(parentSurface != null && !parentSurface.isValid(), "abrupt-death-closes-parent-reader");
            Log.i("ProjectionProcessVerification", "ABRUPT_DEATH parentPid=" + parentPid + " hostPid=" + hostPid);
            waitForRemoval(SystemClock.elapsedRealtime() + 5000);
        } else if (!stopped) {
            fail("unexpected-helper-death-during-normal-round");
        }
    }

    private void waitForRemoval(long deadline) {
        if (failed) {
            return;
        }
        if (displayPresent() || !deathObserved) {
            if (SystemClock.elapsedRealtime() < deadline) {
                handler.postDelayed(() -> waitForRemoval(deadline), 80);
            } else {
                fail("display-or-helper-still-present-after-exit");
            }
            return;
        }
        check(Process.myPid() == parentPid, "parent-survives-cleanup");
        check(liveness != null && !liveness.isBinderAlive(), "helper-death-confirmed");
        check(reader == null && parentSurface != null && !parentSurface.isValid(), "reader-remains-closed");
        completed++;
        running = false;
        previousHostPid = hostPid;
        previousDisplayId = displayId;
        Log.i("ProjectionProcessVerification", "ROUND_PASS round=" + completed + " checks=" + checks
                + " parentPid=" + parentPid + " hostPid=" + hostPid + " elapsedMs=" + (SystemClock.elapsedRealtime() - startedAt));
        if (completed == 3) {
            check(actualStops == 2 && abruptDeaths == 1 && rounds == 3, "all-required-stop-paths-exercised");
            Log.i("ProjectionProcessVerification", "PASS isolatedCleanupChecks=" + checks
                    + " actualProjectionStops=" + actualStops + " abruptHelperDeaths=" + abruptDeaths
                    + " normalSystemGrants=" + rounds + " parentPid=" + parentPid
                    + " modelRequests=0 wechatWrites=0 rawChatFiles=0");
            show("PASS 正常停止两轮、采集子进程意外退出一轮均清理显示，主进程存活；不是正式助手集成验收。");
        } else if (completed == 2) {
            show("前两轮通过。请重新授权第三轮，再点击模拟采集子进程意外退出。");
        } else {
            show("第一轮通过。请再次开始并正常授权，验证同一主进程中的全新采集进程。");
        }
    }

    private boolean displayPresent() {
        Display display = getSystemService(DisplayManager.class).getDisplay(displayId);
        return display != null && display.isValid() && display.getName().equals("ProjectionProcessFixture");
    }

    private void closeReader() {
        if (reader != null) {
            reader.setOnImageAvailableListener(null, null);
            reader.close();
            reader = null;
        }
    }

    private void check(boolean condition, String label) {
        checks++;
        if (!condition) {
            fail(label);
            throw new IllegalStateException(label);
        }
    }

    private void fail(String reason) {
        failed = true;
        closeReader();
        stopService(new Intent(this, ProjectionHostService.class));
        show("FAIL " + reason + "；结束验证并清理临时包。");
        Log.e("ProjectionProcessVerification", "FAIL check=" + reason + " checks=" + checks + " parentPid=" + parentPid);
    }

    private void show(String message) {
        status.setText(message);
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        closeReader();
        stopService(new Intent(this, ProjectionHostService.class));
        super.onDestroy();
    }
}
