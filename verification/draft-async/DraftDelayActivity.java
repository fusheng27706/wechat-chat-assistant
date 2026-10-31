package com.codextemp.wechatcaptureprobe;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

public final class DraftDelayActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String nonce;
    private boolean announced;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        nonce = getIntent().getStringExtra("nonce");
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(0xffeeeeee);
        TextView disclosure = new SlowView(this, nonce);
        disclosure.setText("虚构输入框查询等待测试\n仅延迟此页面的一次节点响应\n不共享屏幕、不读取聊天、不输入或发送\n十秒后自动关闭");
        disclosure.setTextSize(22);
        disclosure.setPadding(30, 100, 30, 30);
        container.addView(disclosure);
        setContentView(container);
        handler.postDelayed(this::finish, 10000);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && !announced) {
            announced = true;
            handler.postDelayed(() -> {
                if (hasWindowFocus()) sendBroadcast(new Intent("com.codextemp.wechatcaptureprobe.DRAFT_FIXTURE_LIVE")
                        .setPackage("com.codextemp.wechatcaptureprobe").putExtra("nonce", nonce));
            }, 500);
        }
    }

    private static final class SlowView extends TextView {
        private final String nonce;
        private boolean delayed;

        SlowView(Context context, String nonce) {
            super(context);
            this.nonce = nonce;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo node) {
            super.onInitializeAccessibilityNodeInfo(node);
            if (!delayed) {
                delayed = true;
                getContext().sendBroadcast(new Intent("com.codextemp.wechatcaptureprobe.DRAFT_FIXTURE_DELAY")
                        .setPackage("com.codextemp.wechatcaptureprobe").putExtra("nonce", nonce));
                android.util.Log.i("DraftDelayFixture", "BEGIN pid=" + Process.myPid() + " delayMs=2400");
                SystemClock.sleep(2400);
                android.util.Log.i("DraftDelayFixture", "END pid=" + Process.myPid());
            }
        }
    }
}
