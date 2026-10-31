package com.codextemp.wechatcaptureprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** 只检查自有 View 和数值状态，不枚举微信节点，不读取图像或模型密钥。 */
public final class UiFlowSmoke extends Instrumentation {
    private String mode;
    private boolean operateSystemUi;
    private int checks;
    private Activity home;
    private Activity secondary;
    private boolean startedByTest;
    private volatile boolean inspected;
    private boolean registered;
    private final BroadcastReceiver observer = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent.getIntExtra("parentPid", -1) == android.os.Process.myPid()) inspected = true;
        }
    };
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        mode = args == null ? "pages" : args.getString("mode", "pages");
        operateSystemUi = args != null && "true".equals(args.getString("system_ui", "false"));
        start();
    }
    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            if (mode.equals("cleanup")) {
                closeOwnGateThroughSettings();
                output.putString("result", "PASS mode=cleanup own-service-disabled");
                return;
            }
            require(mode.equals("pages") || mode.equals("cancel") || mode.equals("startstop"), "known-mode");
            ModelSettingsStore store = new ModelSettingsStore(getTargetContext());
            require(!store.hasKey() && !store.cloudAllowed() && !store.partialAnalysisAllowed(), "no-key-cloud-or-partial");
            require(!AssistantCaptureService.running() && !CaptureService.running() && ProjectionSession.available(), "no-existing-capture");
            long revision = store.revision();
            if (mode.equals("startstop")) {
                IntentFilter filter = new IntentFilter("com.codextemp.wechatcaptureprobe.UI_STOP_INSPECTED");
                if (Build.VERSION.SDK_INT >= 33) getTargetContext().registerReceiver(observer, filter, Context.RECEIVER_EXPORTED);
                else getTargetContext().registerReceiver(observer, filter);
                registered = true;
            }
            Intent launcher = getTargetContext().getPackageManager().getLaunchIntentForPackage(getTargetContext().getPackageName());
            require(launcher != null && launcher.getComponent() != null
                    && launcher.getComponent().getClassName().equals(AssistantActivity.class.getName()), "product-launcher");
            phase("OPEN_OWN_HOME");
            home = startActivitySync(new Intent(getTargetContext(), AssistantActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            waitForIdleSync();
            onMain(() -> {
                require(!toggle().isChecked() && toggle().isEnabled(), "home-switch-off");
                require(!text(home, "去微信聊天").isShown(), "wechat-action-hidden-before-start");
                require(text(home, "设置与帮助").isShown(), "settings-visible");
                require(!AssistantCaptureService.running(), "opening-home-does-not-capture");
            });
            if (mode.equals("pages")) {
                Activity settings = navigate(home, "设置与帮助", AssistantSettingsActivity.class);
                secondary = settings;
                onMain(() -> {
                    require(text(settings, "AI 服务").isShown(), "ai-entry");
                    require(text(settings, "不辅助的聊天").isShown(), "rules-entry");
                    require(text(settings, "我的记忆").isShown(), "memory-entry");
                });
                Activity ai = navigate(settings, "AI 服务", ModelSettingsActivity.class);
                secondary = ai;
                onMain(() -> {
                    require(text(ai, "保存配置").isShown(), "save-config-visible");
                    require(!text(ai, "检测连接（发送虚构对话）").isShown(), "test-hidden-by-default");
                    require(!text(ai, "请求 JSON 模式（接口不支持时可关闭）").isShown(), "advanced-hidden-by-default");
                    click(ai, "高级选项与连接检测");
                    require(text(ai, "检测连接（发送虚构对话）").isShown(), "test-shown-only-after-expand");
                    click(ai, "收起高级选项");
                    require(!text(ai, "检测连接（发送虚构对话）").isShown(), "advanced-folds-again");
                    ai.finish();
                    settings.finish();
                });
                secondary = null;
                ActivityMonitor recreated = addMonitor(AssistantActivity.class.getName(), null, false);
                onMain(() -> home.recreate());
                Activity replacement = waitForMonitorWithTimeout(recreated, 8000);
                removeMonitor(recreated);
                require(replacement != null, "home-recreated");
                home = replacement;
                waitForIdleSync();
                onMain(() -> require(!toggle().isChecked() && toggle().isEnabled(), "recreated-home-still-off"));
            } else {
                require(!AssistantState.status.contains("未授权共享"), "cancel-evidence-not-stale");
                phase(mode.equals("cancel") ? "CANCEL_SYSTEM_SHARE_ON_PHONE" : "ALLOW_SYSTEM_SHARE_ON_PHONE");
                startedByTest = true;
                onMain(() -> toggle().performClick());
                if (operateSystemUi && !ChatGateService.enabled()) restoreOwnGateThroughSettings();
                if (operateSystemUi) operateSharePrompt(mode.equals("cancel"));
                if (mode.equals("cancel")) {
                    await(() -> AssistantState.status.contains("未授权共享"), 120000, "explicit-system-cancel-observed");
                    require(!AssistantCaptureService.running() && ProjectionSession.available(), "cancel-no-capture");
                    onMain(() -> require(!toggle().isChecked() && toggle().isEnabled(), "cancel-switch-off"));
                } else {
                    await(AssistantCaptureService::running, 120000, "system-grant-started-real-service");
                    await(() -> mainValue(() -> {
                        Object instance = staticField(AssistantCaptureService.class, "instance");
                        if (instance == null) return false;
                        ProjectionSession owner = (ProjectionSession) field(instance, "projectionSession");
                        return owner != null && owner.ready();
                    }), 10000, "real-projection-owner-ready");
                    await(() -> mainValue(() -> toggle().isChecked()), 5000, "running-switch-on");
                    require(AssistantState.messages.isEmpty(), "own-page-has-no-chat-content");
                    onMain(() -> {
                        toggle().performClick();
                        require(!toggle().isChecked(), "stop-intent-never-rechecks-switch");
                        require(!text(home, "去微信聊天").isShown(), "stop-hides-wechat-action");
                    });
                    await(() -> !AssistantCaptureService.running() && ProjectionSession.available(), 10000, "normal-stop-releases-session");
                    await(() -> mainValue(() -> !toggle().isChecked() && toggle().isEnabled()), 5000, "stopped-switch-ready");
                    require(AssistantState.messages.isEmpty() && AssistantState.token.isEmpty(), "stop-clears-private-state");
                    phase("STOPPED_PARENT_ALIVE_EXTERNAL_CHECK");
                    // 保持原主供电脑核对，不能用 instrumentation 退出后的清空当作停止证据。
                    await(() -> inspected, 30000, "external-original-parent-and-resource-check-acknowledged");
                }
            }
            require(!store.hasKey() && !store.cloudAllowed() && !store.partialAnalysisAllowed()
                    && store.revision() == revision, "settings-consent-and-revision-unchanged");
            require(!AssistantCaptureService.running() && !CaptureService.running(), "no-capture-at-end");
            output.putString("result", "PASS mode=" + mode + " checks=" + checks);
        } catch (Throwable failure) {
            output.putString("result", "FAIL mode=" + mode + " checks=" + checks + " reason="
                    + (failure instanceof AssertionError ? failure.getMessage() : failure.getClass().getSimpleName()));
        } finally {
            if (startedByTest) onMain(() -> CaptureControl.stopAll(getTargetContext()));
            if (registered) getTargetContext().unregisterReceiver(observer);
            onMain(() -> { if (secondary != null) secondary.finish(); if (home != null) home.finish(); });
            finish(Activity.RESULT_OK, output);
        }
    }
    private Activity navigate(Activity origin, String label, Class<?> target) {
        ActivityMonitor monitor = addMonitor(target.getName(), null, false);
        onMain(() -> click(origin, label));
        Activity result = waitForMonitorWithTimeout(monitor, 8000);
        removeMonitor(monitor);
        require(result != null, "navigation-" + target.getSimpleName());
        waitForIdleSync();
        return result;
    }
    /** 收尾只关闭本助手服务，保留其它无障碍服务和系统设置。 */
    private void closeOwnGateThroughSettings() {
        UiAutomation automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        long deadline = SystemClock.elapsedRealtime() + 45000;
        boolean touched = false;
        while (SystemClock.elapsedRealtime() < deadline) {
            String enabled = android.provider.Settings.Secure.getString(getTargetContext().getContentResolver(), "enabled_accessibility_services");
            if (enabled == null || !enabled.contains("com.codextemp.wechatcaptureprobe")) return;
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root != null) try {
                if ("com.android.settings".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
                    if (containsText(root, "微信聊天辅助范围服务")) {
                        if (containsText(root, "使用微信聊天辅助范围服务")) {
                            if (!touched) touched = tapServiceRow(automation, root);
                        } else clickText(root, "微信聊天辅助范围服务");
                        clickText(root, "停止");
                        clickText(root, "关闭");
                        clickText(root, "确定");
                    } else for (String label : new String[]{"已下载的应用", "已下载的服务", "下载的应用", "下载的服务"})
                        if (clickText(root, label)) break;
                }
            } finally { root.recycle(); }
            SystemClock.sleep(250);
        }
        throw new AssertionError("own-service-cleanup-not-confirmed");
    }
    private Switch toggle() { return (Switch) field(home, "toggle"); }
    /** 跟随首页的系统设置引导，只匹配本应用服务；不写 secure settings 或操作其它服务。 */
    private void restoreOwnGateThroughSettings() {
        UiAutomation automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        boolean opened = false;
        boolean resetRequested = false;
        long deadline = SystemClock.elapsedRealtime() + 45000;
        while (!ChatGateService.enabled() && SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root != null) try {
                String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
                if (pkg.equals("com.codextemp.wechatcaptureprobe")) {
                    opened |= clickText(root, "去系统设置");
                } else if (opened && pkg.equals("com.android.settings")) {
                    if (containsText(root, "微信聊天辅助范围服务")) {
                        boolean detail = containsText(root, "使用微信聊天辅助范围服务");
                        if (detail) {
                            String enabled = android.provider.Settings.Secure.getString(getTargetContext().getContentResolver(), "enabled_accessibility_services");
                            boolean marked = enabled != null && enabled.contains("com.codextemp.wechatcaptureprobe");
                            if (!marked || !resetRequested) {
                                resetRequested = true;
                                require(tapServiceRow(automation, root), "own-service-row-touch-injected");
                                SystemClock.sleep(800);
                            }
                        } else clickText(root, "微信聊天辅助范围服务");
                        clickText(root, "允许");
                        clickText(root, "确定");
                        clickText(root, "停止");
                    } else {
                        for (String label : new String[]{"已下载的应用", "已下载的服务", "下载的应用", "下载的服务"}) {
                            if (clickText(root, label)) break;
                        }
                    }
                }
            } finally { root.recycle(); }
            SystemClock.sleep(350);
        }
        require(ChatGateService.enabled(), "own-gate-restored-through-system-settings");
        phase("RETURN_TO_HOME_AFTER_GATE");
        for (int i = 0; i < 5; i++) {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            boolean settings = false;
            if (root != null) {
                settings = "com.android.settings".contentEquals(root.getPackageName() == null ? "" : root.getPackageName());
                root.recycle();
            }
            if (!settings) break;
            automation.injectInputEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK), true);
            automation.injectInputEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK), true);
            SystemClock.sleep(350);
        }
        await(() -> mainValue(() -> ((Integer) field(home, "step")) != 1), 20000, "returned-to-own-home-after-gate");
    }
    // 系统服务页右侧开关可能被无障碍快捷悬浮按钮遮挡；只触碰本服务明确命名的文字行。
    private boolean tapServiceRow(UiAutomation automation, AccessibilityNodeInfo node) {
        if ("使用微信聊天辅助范围服务".contentEquals(node.getText() == null ? "" : node.getText()))
            return tapNode(automation, node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { if (tapServiceRow(automation, child)) return true; }
            finally { child.recycle(); }
        }
        return false;
    }
    private AccessibilityNodeInfo findSwitch(AccessibilityNodeInfo node) {
        if ("android:id/switch_widget".equals(node.getViewIdResourceName())
                && "android.widget.Switch".contentEquals(node.getClassName() == null ? "" : node.getClassName()) && node.isEnabled())
            return AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { AccessibilityNodeInfo found = findSwitch(child); if (found != null) return found; }
            finally { child.recycle(); }
        }
        return null;
    }
    private boolean tapNode(UiAutomation automation, AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!node.isVisibleToUser() || !node.isEnabled() || bounds.isEmpty()) return false;
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY(), 0);
        MotionEvent up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY(), 0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try { return automation.injectInputEvent(down, true) && automation.injectInputEvent(up, true); }
        finally { down.recycle(); up.recycle(); }
    }
    private boolean clickText(AccessibilityNodeInfo node, String label) {
        if (label.contentEquals(node.getText() == null ? "" : node.getText())) {
            AccessibilityNodeInfo action = AccessibilityNodeInfo.obtain(node);
            try {
                for (int depth = 0; depth < 4 && action != null; depth++) {
                    if (action.isClickable() && action.isEnabled()) return action.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    AccessibilityNodeInfo parent = action.getParent();
                    action.recycle();
                    action = parent;
                }
            } finally { if (action != null) action.recycle(); }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { if (clickText(child, label)) return true; }
            finally { child.recycle(); }
        }
        return false;
    }
    /** 只在用户明确委托手机操作后启用，保留其它无障碍服务；只读系统共享弹窗。 */
    private void operateSharePrompt(boolean cancel) {
        UiAutomation automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        long deadline = SystemClock.elapsedRealtime() + 10000;
        boolean clicked = false;
        while (!clicked && SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root != null) {
                try {
                    if ("com.android.systemui".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())
                            && containsText(root, "微信聊天助手")
                            && (containsText(root, "录制") || containsText(root, "投射"))) {
                        AccessibilityNodeInfo button = findSystemButton(root, cancel);
                        if (button != null) {
                            try { clicked = button.performAction(AccessibilityNodeInfo.ACTION_CLICK); }
                            finally { button.recycle(); }
                        }
                    }
                } finally { root.recycle(); }
            }
            if (!clicked) SystemClock.sleep(100);
        }
        require(clicked, cancel ? "system-cancel-button-clicked" : "system-share-button-clicked");
        require(ChatGateService.enabled(), "range-service-not-suppressed-by-automation");
    }
    private boolean containsText(AccessibilityNodeInfo node, String expected) {
        if (node.getText() != null && node.getText().toString().contains(expected)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { if (containsText(child, expected)) return true; }
            finally { child.recycle(); }
        }
        return false;
    }
    private AccessibilityNodeInfo findSystemButton(AccessibilityNodeInfo node, boolean cancel) {
        String id = cancel ? "android:id/button2" : "android:id/button1";
        String label = node.getText() == null ? "" : node.getText().toString();
        boolean expected = cancel ? label.equals("取消") : label.equals("立即开始") || label.equals("现在开始") || label.equals("开始");
        if (id.equals(node.getViewIdResourceName()) && expected && node.isEnabled() && node.isClickable())
            return AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                AccessibilityNodeInfo result = findSystemButton(child, cancel);
                if (result != null) return result;
            } finally { child.recycle(); }
        }
        return null;
    }
    private Object staticField(Class<?> type, String name) {
        try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(null); }
        catch (ReflectiveOperationException exception) { throw new AssertionError("own-static-field"); }
    }
    private Object field(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (ReflectiveOperationException exception) { throw new AssertionError("own-view-field"); }
    }
    private TextView text(Activity activity, String expected) {
        TextView result = find(activity.getWindow().getDecorView(), expected);
        require(result != null, "own-control-present");
        return result;
    }
    private TextView find(View view, String expected) {
        if (view instanceof TextView && expected.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView result = find(((ViewGroup) view).getChildAt(i), expected);
            if (result != null) return result;
        }
        return null;
    }
    private void click(Activity activity, String label) {
        TextView view = text(activity, label);
        require(view instanceof Button && view.isShown() && view.isEnabled(), "clickable-own-control");
        require(view.performClick(), "own-click-dispatched");
    }
    private boolean mainValue(BooleanSupplier action) {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        onMain(() -> result.set(action.getAsBoolean()));
        return result.get();
    }
    private void onMain(Runnable action) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new AssertionError(error.get().getMessage());
    }
    private void await(BooleanSupplier condition, long timeout, String code) {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50);
        require(condition.getAsBoolean(), code);
    }
    private void require(boolean condition, String code) {
        if (!condition) throw new AssertionError(code);
        checks++;
    }
    private void phase(String code) {
        Bundle status = new Bundle();
        status.putString("phase", code);
        status.putInt("parentPid", android.os.Process.myPid());
        sendStatus(1, status);
    }
}
