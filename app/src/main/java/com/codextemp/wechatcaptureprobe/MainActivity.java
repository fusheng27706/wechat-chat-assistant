package com.codextemp.wechatcaptureprobe;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int CAPTURE_REQUEST = 100;
    private static final int NOTIFICATION_REQUEST = 101;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private TextView summaryView;
    private TextView outputView;
    private ImageView previewView;
    private ScrollView scrollView;
    private String displayedStatus;
    private ProbeState.Result displayedResult;
    private boolean continuousRequested;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        continuousRequested = savedInstanceState != null && savedInstanceState.getBoolean("continuousRequested");
        scrollView = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(24), dp(20), dp(32));
        content.setBackgroundColor(Color.rgb(247, 249, 252));
        scrollView.addView(content);
        setContentView(scrollView);

        content.addView(text("微信采集验证器", 25));
        content.addView(text("本机中文 OCR：单帧或限时连续测试。截图和文字只放在内存，不上传、不写入文件。", 15));
        content.addView(text("SDK 数据说明：ML Kit 在本机处理图片和文字；按 Google 说明，SDK 可能联网发送设备、应用及性能统计，但不向 Google 发送输入图片或识别文字。本机识别不等于完全不联网。", 13));
        content.addView(text(deviceInfo(), 13));

        Button assistantButton = new Button(this);
        assistantButton.setText("打开聊天助手（开发中）");
        assistantButton.setOnClickListener(view -> startActivity(new Intent(this, AssistantActivity.class)));
        content.addView(assistantButton);

        statusView = text("", 19);
        statusView.setPadding(dp(12), dp(12), dp(12), dp(12));
        content.addView(statusView);
        summaryView = text("", 13);
        summaryView.setTextIsSelectable(true);
        content.addView(summaryView);

        Button startButton = new Button(this);
        startButton.setText("开始单次验证（8 秒后取一帧）");
        startButton.setOnClickListener(view -> requestCapture(false));
        content.addView(startButton);

        Button continuousButton = new Button(this);
        continuousButton.setText("开始连续测试（3 分钟，每 5 秒）");
        continuousButton.setOnClickListener(view -> requestCapture(true));
        content.addView(continuousButton);

        Button clearButton = new Button(this);
        clearButton.setText("停止所有采集并清空结果");
        clearButton.setOnClickListener(view -> {
            CaptureControl.stopAll(this);
            render();
        });
        content.addView(clearButton);

        content.addView(text("连续测试请留在约定的微信测试聊天，使用 T01–T08 固定样本。最多 3 分钟，通知内可停止；黑图或识别异常即结束。匹配可能包含输入区，不能代替已发送消息和真实发言者判断。", 14));

        content.addView(text("步骤：先留在此页测试已知文字；再做一次测试，授权后打开微信聊天并停住。8 秒后自动取一帧并结束会话，返回本工具查看结果。系统支持时，选择只共享目标应用。", 15));
        content.addView(text("测试文字\n客户：预算只有八千，今天能定吗？\n我：我们先确认交付范围。\n群成员小李：周五下午三点开会。", 18));
        previewView = new ImageView(this);
        previewView.setAdjustViewBounds(true);
        previewView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        content.addView(previewView, new LinearLayout.LayoutParams(-1, -2));
        outputView = text("", 15);
        outputView.setTextIsSelectable(true);
        content.addView(outputView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        clearResultViews();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("continuousRequested", continuousRequested);
        super.onSaveInstanceState(state);
    }

    private void requestCapture(boolean continuous) {
        if (AssistantCaptureService.running()) {
            ProbeState.status("聊天辅助仍在运行，不能并行启动诊断；请先点击“停止所有采集并清空结果”");
            return;
        }
        if (CaptureService.running()) {
            ProbeState.status("已有采集会话，请先停止或等待本轮结束。");
            return;
        }
        if (!ProjectionSession.available()) {
            ProbeState.status("上次共享仍在清理，请稍候重新开始。");
            return;
        }
        continuousRequested = continuous;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
            return;
        }
        launchCapturePermission();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_REQUEST) {
            launchCapturePermission();
        }
    }

    private void launchCapturePermission() {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        startActivityForResult(manager.createScreenCaptureIntent(), CAPTURE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CAPTURE_REQUEST) {
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            ProbeState.fail("未授权屏幕采集，没有读取任何画面。");
            return;
        }
        if (AssistantCaptureService.running()) {
            ProbeState.status("聊天辅助已在等待授权期间开启，本次未启动诊断；请先停止所有采集");
            return;
        }
        try {
            Intent serviceIntent = new Intent(this, CaptureService.class);
            serviceIntent.putExtra("resultCode", resultCode);
            serviceIntent.putExtra("captureData", data);
            serviceIntent.putExtra("continuous", continuousRequested);
            startForegroundService(serviceIntent);
        } catch (RuntimeException exception) {
            ProbeState.fail("无法启动采集：" + exception.getClass().getSimpleName());
        }
    }

    private void render() {
        String currentStatus = ProbeState.status();
        if (!currentStatus.equals(displayedStatus)) {
            displayedStatus = currentStatus;
            statusView.setText(currentStatus);
            ProbeState.Result statusResult = ProbeState.result();
            boolean warning = statusResult != null && statusResult.contentUnavailable;
            boolean success = statusResult != null && statusResult.lineCount > 0 && !warning;
            statusView.setBackgroundColor(warning ? Color.rgb(255, 238, 211)
                    : success ? Color.rgb(222, 245, 230) : Color.rgb(232, 237, 246));
            statusView.setTextColor(warning ? Color.rgb(133, 76, 16)
                    : success ? Color.rgb(23, 100, 51) : Color.rgb(26, 36, 54));
        }
        ProbeState.Result current = ProbeState.result();
        if (current == null && displayedResult != null) {
            clearResultViews();
        } else if (current != null && current != displayedResult) {
            displayedResult = current;
            previewView.setVisibility(View.VISIBLE);
            previewView.setImageBitmap(current.image);
            summaryView.setText(current.summary);
            outputView.setText(current.lines);
            scrollView.post(() -> scrollView.smoothScrollTo(0, 0));
        }
        if (current == null) {
            // 不再依据诊断结果开关截图保护；所有界面均允许系统截图。
        }
    }

    private void clearResultViews() {
        displayedResult = null;
        previewView.setImageDrawable(null);
        previewView.setVisibility(View.GONE);
        summaryView.setText("");
        outputView.setText("");
    }

    private String deviceInfo() {
        String wechatVersion = "未检测到";
        try {
            PackageInfo info = getPackageManager().getPackageInfo("com.tencent.mm", 0);
            wechatVersion = info.versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE
                + " / API " + Build.VERSION.SDK_INT + "\n微信：" + wechatVersion;
    }

    private TextView text(String value, int size) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(size);
        textView.setTextColor(Color.rgb(26, 36, 54));
        textView.setPadding(0, dp(8), 0, dp(8));
        return textView;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
