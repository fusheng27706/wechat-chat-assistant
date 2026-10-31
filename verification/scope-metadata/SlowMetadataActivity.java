package com.codextemp.wechatcaptureprobe;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

public final class SlowMetadataActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(0xffeeeeee);
        String nonce = getIntent().getStringExtra("nonce");
        TextView disclosure = new SlowView(this, nonce);
        disclosure.setText("虚构窗口元数据测试\n仅延迟本测试页面的节点响应\n不采集屏幕，不输入或发送消息\n测试结束自动关闭");
        disclosure.setTextSize(22);
        disclosure.setPadding(30, 100, 30, 30);
        container.addView(disclosure);
        setContentView(container);
        sendBroadcast(new Intent("com.codextemp.wechatcaptureprobe.SCOPE_FIXTURE_LIVE")
                .setPackage("com.codextemp.wechatcaptureprobe").putExtra("nonce", nonce));
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 10000);
    }

    private static final class SlowView extends TextView {
        private final long createdAt = SystemClock.elapsedRealtime();
        private int requests;
        private final String nonce;

        SlowView(Context context, String nonce) {
            super(context);
            this.nonce = nonce;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo node) {
            super.onInitializeAccessibilityNodeInfo(node);
            if (requests < 1 && SystemClock.elapsedRealtime() - createdAt < 7000) {
                requests++;
                getContext().sendBroadcast(new Intent("com.codextemp.wechatcaptureprobe.SCOPE_FIXTURE_DELAY")
                        .setPackage("com.codextemp.wechatcaptureprobe").putExtra("nonce", nonce));
                Log.i("SlowScopeFixture", "ROOT_DELAY_BEGIN pid=" + Process.myPid() + " request=" + requests + " delayMs=2400");
                SystemClock.sleep(2400);
                Log.i("SlowScopeFixture", "ROOT_DELAY_END pid=" + Process.myPid() + " request=" + requests);
            }
        }
    }
}
