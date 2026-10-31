package com.codextemp.wechatcaptureprobe;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Switch;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 测试专用正常授权导航，仅在操作者显式启用 system_ui 时运行。 */
final class NormalGrantUi {
    private final Instrumentation instrumentation;
    private final Activity home;
    private final Consumer<String> phase;
    NormalGrantUi(Instrumentation instrumentation, Activity home, Consumer<String> phase) {
        this.instrumentation = instrumentation; this.home = home; this.phase = phase;
    }
    void start() {
        await(() -> mainValue(this::homeReady), 10000, "own-home-window-ready");
        instrumentation.runOnMainSync(() -> {
            require(homeReady(), "own-home-current-at-click");
            ((Switch) read(home, "toggle")).performClick();
        });
        if (!ChatGateService.enabled()) restoreOwnGateThroughSettings();
        operateSharePrompt(false);
        await(AssistantCaptureService::running, 10000, "real-service-started");
        await(() -> mainValue(() -> {
            try {
                Field current = ProjectionSession.class.getDeclaredField("current");
                current.setAccessible(true);
                ProjectionSession session = (ProjectionSession) current.get(null);
                return AssistantCaptureService.running() && session != null && session.ready();
            } catch (ReflectiveOperationException failure) { throw new AssertionError("own-projection-state"); }
        }), 10000, "real-projection-ready-before-leaving-home");
    }
    private boolean homeReady() {
        return home != null && !home.isFinishing() && !home.isDestroyed()
                && home.getWindow().getDecorView().isAttachedToWindow() && home.hasWindowFocus();
    }
    /** 仅从微信列表中点击含既有T08固定样本预览的行，不猜测联系人、不输入或发送。 */
    void openFixedChatFromList() {
        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        navigationObservation("fixed-entry", automation);
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root != null) try {
                if ("com.tencent.mm".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())
                        && containsText(root, "通讯录") && containsText(root, "发现") && clickFixedPreview(root)) {
                    phase("KNOWN_T08_PREVIEW_OPENED");
                    return;
                }
            } finally { root.recycle(); }
            SystemClock.sleep(300);
        }
        navigationObservation("fixed-entry-after", automation);
    }
    /** 只从已确认的微信聊天页正常返回列表；不处理账号验证或未知页面。 */
    void returnToChatList() {
        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        boolean wechat = false;
        if (root != null) try {
            wechat = "com.tencent.mm".contentEquals(root.getPackageName() == null ? "" : root.getPackageName());
        } finally { root.recycle(); }
        require(wechat, "switch-wechat-foreground-before-back");
        navigationObservation("before", automation);
        require(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK),
                "switch-normal-back-dispatched");
        try { await(() -> isChatList(automation), 5000, "switch-chat-list-visible"); }
        finally { navigationObservation("after", automation); }
    }

    /** 只输出固定标签是否存在；不记录节点正文、描述、账号或层级。 */
    void navigationObservation(String stage, UiAutomation automation) {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        int flags = 0;
        int fixedUiFlags = 0;
        String rootOwner = "none";
        int[] counts = new int[5];
        if (root != null) try {
            rootOwner = String.valueOf(root.getPackageName());
            countNavigationMetadata(root, counts);
            flags |= 1;
            if ("com.tencent.mm".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) flags |= 2;
            if (containsText(root, "通讯录")) flags |= 4;
            if (containsText(root, "发现")) flags |= 8;
            if (containsDescription(root, "通讯录")) flags |= 16;
            if (containsDescription(root, "发现")) flags |= 32;
            if (containsText(root, "文件传输助手")) flags |= 64;
            if (containsText(root, "安全验证")) fixedUiFlags |= 1;
            if (containsText(root, "发消息")) fixedUiFlags |= 2;
            if (containsText(root, "按住") && containsText(root, "说话")) fixedUiFlags |= 4;
            if (containsText(root, "登录")) fixedUiFlags |= 8;
        } finally { root.recycle(); }
        android.os.Bundle status = new android.os.Bundle();
        status.putString("navigation", "stage=" + stage + " rootOwner=" + rootOwner + " knownLabelFlags=" + flags
                + " fixedUiFlags=" + fixedUiFlags
                + " nodes=" + counts[0] + " textNodes=" + counts[1] + " descriptionNodes=" + counts[2]
                + " editableNodes=" + counts[3] + " clickableNodes=" + counts[4]);
        instrumentation.sendStatus(2, status);
    }

    private void countNavigationMetadata(AccessibilityNodeInfo node, int[] counts) {
        counts[0]++;
        if (node.getText() != null && node.getText().length() > 0) counts[1]++;
        if (node.getContentDescription() != null && node.getContentDescription().length() > 0) counts[2]++;
        if (node.isEditable()) counts[3]++;
        if (node.isClickable()) counts[4]++;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { countNavigationMetadata(child, counts); } finally { child.recycle(); }
        }
    }

    private boolean containsDescription(AccessibilityNodeInfo node, String expected) {
        CharSequence description = node.getContentDescription();
        if (description != null && description.toString().contains(expected)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { if (containsDescription(child, expected)) return true; }
            finally { child.recycle(); }
        }
        return false;
    }

    private boolean isChatList(UiAutomation automation) {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root == null) return false;
        try { return "com.tencent.mm".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())
                && containsText(root, "通讯录") && containsText(root, "发现"); }
        finally { root.recycle(); }
    }

    /** 仅选择既有系统聊天的明确标签；找不到就失败，不猜联系人或搜索账号。 */
    void openFileTransferFromList() {
        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        require(isChatList(automation), "switch-list-before-file-transfer");
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        boolean clicked = false;
        if (root != null) try {
            if ("com.tencent.mm".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())
                    && containsText(root, "通讯录") && containsText(root, "发现"))
                clicked = clickText(root, "文件传输助手");
        } finally { root.recycle(); }
        require(clicked, "switch-existing-file-transfer-clicked");
    }

    private boolean clickFixedPreview(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        if (text != null && ConversationSamples.matchedCodes(text.toString()).contains("T08"))
            return clickText(node, text.toString());
        for (int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { if (clickFixedPreview(child)) return true; } finally { child.recycle(); }
        }
        return false;
    }

    private void phase(String value) { phase.accept(value); }
    private void require(boolean condition, String code) { if (!condition) throw new AssertionError(code); }
    private void await(BooleanSupplier condition, long limit, String code) {
        long end = SystemClock.elapsedRealtime() + limit;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50);
        require(condition.getAsBoolean(), code);
    }
    private boolean mainValue(BooleanSupplier condition) {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> result.set(condition.getAsBoolean())); return result.get();
    }
    private Object read(Object object, String name) {
        try { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
        catch (ReflectiveOperationException e) { throw new AssertionError("own-ui-field"); }
    }
    /** 跟随首页的系统设置引导，只匹配本应用服务；不写 secure settings 或操作其它服务。 */
    private void restoreOwnGateThroughSettings() {
        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
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
                            String enabled = android.provider.Settings.Secure.getString(instrumentation.getTargetContext().getContentResolver(), "enabled_accessibility_services");
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
        await(() -> mainValue(() -> ((Integer) read(home, "step")) != 1), 20000, "returned-to-own-home-after-gate");
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
        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
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
}
