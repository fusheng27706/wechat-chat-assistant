package com.codextemp.wechatcaptureprobe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** 首页开关只控制共享会话，不开启云端分析，也不替代系统授权。 */
public final class AssistantActivity extends Activity {
    private static final int NOTIFICATIONS = 30, PROJECTION = 31;
    private static final int IDLE = 0, GATE = 1, NOTIFY = 2, SHARE = 3, STARTING = 4, STOPPING = 5;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Switch toggle;
    private TextView status, hint, modelStatus, statusCapsule;
    private Button openWechat, resume;
    private ModelSettingsStore modelStore;
    private int step;
    private boolean leftForGate, rendering, lastRunning;
    private long startingAt;
    private String notice = "";
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            step = state.getInt("startStep", IDLE);
            leftForGate = state.getBoolean("leftForGate");
            startingAt = state.getLong("startingAt");
            notice = state.getString("notice", "");
        }
        modelStore = new ModelSettingsStore(this);
        ProductUi ui = new ProductUi(this);
        LinearLayout page = ui.page();
        // 顶栏只留品牌图标 + 应用名；设置入口下沉到下方入口卡，避免首屏出现两个并列按钮。
        ui.brandBar(page, "微信聊天助手", "");
        ui.heading(page, "聊天时，多一个帮手", 26);
        ui.text(page, "读懂当前对话，斟酌怎样回复。", 15, ProductUi.MUTED);
        LinearLayout hero = ui.card(page);
        LinearLayout heroHead = ui.column();
        heroHead.setOrientation(LinearLayout.HORIZONTAL);
        heroHead.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView heroLabel = new TextView(this);
        heroLabel.setText("微信聊天助手");
        heroLabel.setTextSize(13);
        heroLabel.setTextColor(ProductUi.MUTED);
        heroHead.addView(heroLabel, new LinearLayout.LayoutParams(0, -2, 1));
        // 状态胶囊：色点 + 状态词；由 render() 按运行/暂停/关闭刷新文案。
        statusCapsule = ui.capsule("未开启", true, false);
        heroHead.addView(statusCapsule, new LinearLayout.LayoutParams(-2, -2));
        hero.addView(heroHead, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout statusRow = ui.column();
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        status = ui.heading(statusRow, "助手未开启", 24);
        ((LinearLayout.LayoutParams) status.getLayoutParams()).weight = 1;
        ((LinearLayout.LayoutParams) status.getLayoutParams()).width = 0;
        toggle = new Switch(this);
        ui.styleSwitch(toggle);
        toggle.setContentDescription("助手开关");
        toggle.setSaveEnabled(false);
        statusRow.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
        hero.addView(statusRow, new LinearLayout.LayoutParams(-1, -2));
        hint = ui.text(hero, "", 13, ProductUi.MUTED);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (rendering) return;
            if (checked) begin();
            else {
                step = STOPPING;
                leftForGate = false;
                CaptureControl.stopAll(this);
                notice = "已关闭，当前识别内容已清空。";
                render();
            }
        });
        resume = ui.tintButton(hero, "继续辅助", () -> {
            AssistantState.paused = false;
            render();
        });
        // 主按钮独立于卡片：定稿 02 屏的渐变主按钮位于 hero 卡与步骤卡之间。
        openWechat = ui.primaryButton(page, "去微信聊天", this::openWechat);
        LinearLayout guide = ui.card(page);
        ui.heading(guide, "用起来，很简单", 17);
        ui.stepRow(guide, "01", "打开助手，按提示完成授权");
        ui.stepRow(guide, "02", "回到微信，自动生成回复建议");
        ui.stepRow(guide, "03", "点一条建议即复制，粘贴发送");
        ui.text(guide, "未配置模型时显示明确标记的本地固定示例，不是 AI 生成。", 12, ProductUi.WEAK);
        // 入口卡：AI 服务 + 设置与帮助（原顶栏按钮下沉，保证设置页仍可达）。
        LinearLayout entry = ui.card(page);
        ui.listRow(entry, "✦", ProductUi.BRAND, ProductUi.TINT, "AI 服务",
                "未配置模型时显示本地示例。", () -> startActivity(new Intent(this, ModelSettingsActivity.class)));
        ui.listRow(entry, "⚙", ProductUi.MUTED, ProductUi.TINT_NEUTRAL, "设置与帮助",
                "使用说明、隐私说明与开发诊断。", () -> startActivity(new Intent(this, AssistantSettingsActivity.class)));
        TextView footer = ui.text(page, "回复由你决定，助手不会自动发送。", 12, ProductUi.WEAK);
        footer.setGravity(android.view.Gravity.CENTER);
        modelStatus = ui.text(page, "", 12, ProductUi.WEAK);
        modelStatus.setGravity(android.view.Gravity.CENTER);
        ui.text(page, "连续使用稳定性仍在完善", 12, ProductUi.WEAK).setGravity(android.view.Gravity.CENTER);
        render();
    }

    /** 仅主动打开开关进入授权链，取消或返回未授权均回到关闭态。 */
    private void begin() {
        notice = "";
        if (AssistantCaptureService.running()) { render(); return; }
        if (CaptureService.running()) {
            fail("检测工具正在运行，请先在“设置与帮助 → 开发诊断”中停止检测。"); return;
        }
        if (!ProjectionSession.available()) { fail("上次共享正在结束，请稍候再开启。"); return; }
        if (!ChatGateService.enabled()) {
            new AlertDialog.Builder(this).setTitle("先允许助手在微信中显示")
                    .setMessage("请在系统的无障碍设置中，开启“微信聊天辅助范围服务”。它用于确认微信是否在前台并显示悬浮球；不会自动发送消息。\n\n返回后还需要系统确认屏幕共享。聊天图片在本机识别，云端分析默认关闭，可在设置中单独选择。")
                    .setNegativeButton("暂不开启", (dialog, which) -> fail("尚未开启，可随时再试。"))
                    .setOnCancelListener(dialog -> fail("尚未开启，可随时再试。"))
                    .setPositiveButton("去系统设置", (dialog, which) -> {
                        step = GATE;
                        try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                        catch (RuntimeException exception) { fail("无法打开系统设置，请在手机设置中开启辅助服务后重试。"); }
                        render();
                    }).show();
            render();
            return;
        }
        requestNotification();
    }

    private void requestNotification() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            step = NOTIFY;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
        } else requestSharing();
        render();
    }

    private void requestSharing() {
        if (!ChatGateService.enabled()) { fail("辅助服务尚未连接，请稍候打开开关重试。"); return; }
        if (CaptureService.running() || AssistantCaptureService.running() || !ProjectionSession.available()) {
            fail("共享状态已变化，请检查当前状态后重试。"); return;
        }
        try {
            step = SHARE;
            startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(), PROJECTION);
        } catch (RuntimeException exception) { fail("暂时无法申请屏幕共享，请稍后重试。"); }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        // 通知许可与共享许可独立；拒绝通知后仍由系统单独询问屏幕共享。
        if (request == NOTIFICATIONS && step == NOTIFY) requestSharing();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PROJECTION || step != SHARE) return;
        if (result != RESULT_OK || data == null) {
            // 保留原生生命周期验证依赖的取消证据，用户文案独立简化。
            AssistantState.status = "未授权共享，没有开启采集";
            fail("没有同意屏幕共享，助手未开启。");
            return;
        }
        // 系统确认期间可能切换状态，旧授权不能越过当前互斥与范围检查。
        if (!ChatGateService.enabled() || CaptureService.running() || AssistantCaptureService.running()
                || !ProjectionSession.available()) {
            fail("授权期间状态发生变化，请重新开启助手。"); return;
        }
        try {
            step = STARTING;
            startingAt = SystemClock.elapsedRealtime();
            startForegroundService(new Intent(this, AssistantCaptureService.class)
                    .putExtra("resultCode", result).putExtra("captureData", data));
        } catch (RuntimeException exception) { fail("助手未能启动，请稍后重试。"); }
        render();
    }

    private void fail(String message) {
        step = IDLE;
        leftForGate = false;
        notice = message;
        if (toggle != null) render();
    }

    private void render() {
        boolean running = AssistantCaptureService.running();
        if (step == STOPPING) {
            // stopSelf 到 onDestroy 有间隔；清理完成前不把旧 active 当成重新开启。
            if (!running && !CaptureService.running() && ProjectionSession.available()) step = IDLE;
        } else if (running) { step = IDLE; notice = ""; }
        else if (step == STARTING && SystemClock.elapsedRealtime() - startingAt >= 8000) {
            // UI 超时结束本次启动，避免显示关闭后出现迟到的共享会话。
            step = STOPPING;
            CaptureControl.stopAll(this);
            notice = "启动未完成，请重新开启；若反复出现，可查看帮助。";
        } else if (lastRunning) notice = "共享已结束，重新开启需要系统确认。";
        lastRunning = running;
        boolean stopping = step == STOPPING;
        boolean pending = step != IDLE && !stopping;
        rendering = true;
        toggle.setChecked(!stopping && (running || pending));
        toggle.setEnabled(!stopping);
        rendering = false;
        // 开关不再承载状态文字（定稿为纯开关）；状态由标题、提示与状态胶囊表达。
        String capsule = stopping ? "正在关闭" : running ? (AssistantState.paused ? "已暂停" : "运行中")
                : pending ? "准备中" : "未开启";
        if (!capsule.equals(statusCapsule.getTag())) {
            statusCapsule.setTag(capsule);
            statusCapsule.setText("●  " + capsule);
        }
        setText(status, stopping ? "正在关闭…" : running ? (AssistantState.paused ? "助手已暂停" : !ChatGateService.enabled() ? "需要恢复辅助权限" : "助手已开启")
                : pending ? "正在准备…" : "助手未开启");
        setText(hint, stopping ? "正在结束共享并清空当前内容，请稍候。" : running ? (AssistantState.paused ? "点击继续后，回到微信使用。也可以关闭开关结束共享。"
                : !ChatGateService.enabled() ? "请关闭开关后重新开启，按提示恢复辅助服务。"
                : "回到微信聊天，点击悬浮球查看。离开微信时暂停识别。")
                : !notice.isEmpty() ? notice : pending ? "请完成系统授权；取消不会开始识别。"
                : "打开右上开关，按提示授权后即可在微信中使用。");
        resume.setVisibility(!stopping && running && AssistantState.paused ? View.VISIBLE : View.GONE);
        openWechat.setVisibility(!stopping && running && ChatGateService.enabled() && !AssistantState.paused ? View.VISIBLE : View.GONE);
        setText(modelStatus, modelStore.cloudAllowed() ? "AI 分析已允许 · 使用你配置的服务，可能产生费用"
                : "当前仅本机识别，暂不能生成回复。AI 服务可稍后在设置中配置。");
    }

    private void setText(TextView view, String text) {
        if (!text.contentEquals(view.getText())) view.setText(text);
    }

    private void openWechat() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("com.tencent.mm");
        if (intent == null) { message("未找到微信，请安装后再使用。"); return; }
        try { startActivity(intent); }
        catch (RuntimeException exception) { message("暂时无法打开微信，请手动打开。"); }
    }

    private void message(String text) {
        new AlertDialog.Builder(this).setMessage(text).setPositiveButton("知道了", null).show();
    }

    @Override protected void onResume() {
        super.onResume();
        if (step == GATE && leftForGate) {
            leftForGate = false;
            if (ChatGateService.enabled()) requestNotification();
            else fail("辅助服务尚未连接。请确认已开启，稍候再打开开关。");
        }
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override protected void onPause() {
        if (step == GATE) leftForGate = true;
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("startStep", step);
        state.putBoolean("leftForGate", leftForGate);
        state.putLong("startingAt", startingAt);
        state.putString("notice", notice);
        super.onSaveInstanceState(state);
    }
}
