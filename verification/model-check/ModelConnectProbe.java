package com.codextemp.wechatcaptureprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 测试专用：驱动模型设置页的「检测连接（发送虚构对话）」并只读回页面文本。
 *
 * 边界：
 * - 只使用页面内置的两条固定虚构文本（预算只有八千…／可以，我们先列出范围再核价），不读取微信；
 * - 只读取闸门状态用于记录，不修改、不清除已保存的密钥与云端开关；
 * - 页面设置 FLAG_SECURE，无法截图，因此只走视图对象与可访问性树，不依赖像素；
 * - 确认对话框优先用可访问性真实点击；若不可用则退回同一入口方法的反射调用，并在结论中标注；
 * - 每一步立即写日志，便于外部驱动在挂起时定位。
 */
public final class ModelConnectProbe extends Instrumentation {
    private static final String TAG = "ChatAssistant";
    private static final String PASS = "接口与结构化分析格式通过";
    private static final String RUNNING = "正在测试固定虚构对话";
    private static final String[] FAILURES = {
            "接口测试未完成", "无法解密", "配置无效", "响应不可用", "未完成"
    };

    private long waitMs = 90000;
    private Activity page;
    private UiAutomation automation;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        waitMs = Long.parseLong(arguments.getString("wait_ms", "90000"));
        start();
    }

    @Override public void onStart() {
        String verdict;
        try {
            verdict = run();
        } catch (Throwable failure) {
            verdict = "ERROR " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
        }
        Log.i(TAG, "MODEL_CONNECT " + verdict);
        Bundle result = new Bundle();
        result.putString("model_connect", verdict);
        finish(-1, result);
    }

    private String run() {
        ModelSettingsStore store = new ModelSettingsStore(getTargetContext());
        note("gate hasKey=" + store.hasKey() + " cloudAllowed=" + store.cloudAllowed()
                + " revision=" + store.revision());
        if (!store.hasKey()) return "PRECONDITION no-saved-key";
        if (!store.cloudAllowed()) return "PRECONDITION cloud-disabled";

        automation = uiAutomation();
        note("automation=" + (automation != null));

        SystemClock.sleep(2000);
        note("settle=done");
        if (!openPage()) return "PRECONDITION page-not-shown";
        note("page=shown rev=" + store.revision());
        main(() -> {
            page.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            return null;
        });

        note("advanced=" + clickButton("高级选项与连接检测") + " rev=" + store.revision());
        SystemClock.sleep(600);
        boolean connect = clickButton("检测连接");
        note("connect=" + connect + " rev=" + store.revision());
        if (!connect) {
            note("advanced-retry=" + clickButton("高级选项与连接检测"));
            SystemClock.sleep(600);
            note("connect-retry=" + clickButton("检测连接") + " rev=" + store.revision());
        }
        SystemClock.sleep(1200);

        String confirm = "none";
        if (clickDialog("发送测试")) confirm = "a11y";
        else {
            dismissDialog();
            confirm = beginTestDirectly(store) ? "reflection" : "failed";
        }
        note("confirm=" + confirm + " rev=" + store.revision());
        note("after-confirm=" + abbreviate(String.valueOf(texts()), 400));
        if ("failed".equals(confirm)) return "PRECONDITION confirm-unavailable";

        long started = SystemClock.elapsedRealtime();
        long deadline = started + waitMs;
        String status = "";
        String longest = "";
        while (SystemClock.elapsedRealtime() < deadline) {
            List<String> texts = texts();
            status = statusOf(texts);
            longest = longestOf(texts);
            if (status.contains(PASS)) {
                note("texts=" + texts);
                note("output=" + abbreviate(longest, 1500));
                return "PASS elapsedMs=" + (SystemClock.elapsedRealtime() - started)
                        + " confirm=" + confirm + " status=" + status
                        + " outputChars=" + longest.length();
            }
            for (String failure : FAILURES) {
                if (status.contains(failure)) {
                    note("texts=" + texts);
                    return "FAIL elapsedMs=" + (SystemClock.elapsedRealtime() - started)
                            + " confirm=" + confirm + " status=" + status;
                }
            }
            SystemClock.sleep(500);
        }
        note("texts=" + texts());
        return "TIMEOUT " + waitMs + "ms confirm=" + confirm
                + " status=" + (status.isEmpty() ? "<none>" : status);
    }

    /** startActivitySync 在受限启动上会一直等，放到工作线程并限时，避免整体挂起。 */
    private boolean openPage() {
        final Activity[] out = new Activity[1];
        final Throwable[] failure = new Throwable[1];
        Thread worker = new Thread(() -> {
            try {
                out[0] = startActivitySync(new Intent(getTargetContext(), ModelSettingsActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable error) {
                failure[0] = error;
            }
        }, "model-page-open");
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(30000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (out[0] == null) {
            note("page-open-failed " + (failure[0] == null ? "timeout" : failure[0].getClass().getSimpleName()));
            return false;
        }
        page = out[0];
        return true;
    }

    private boolean clickButton(String prefix) {
        return Boolean.TRUE.equals(main(() -> {
            View button = findButton(page.getWindow().getDecorView(), prefix);
            if (button == null) return false;
            ((Button) button).performClick();
            return true;
        }));
    }

    private boolean clickDialog(String label) {
        if (automation == null) return false;
        for (int attempt = 0; attempt < 12; attempt++) {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root != null) {
                try {
                    List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
                    for (AccessibilityNodeInfo node : nodes) {
                        if (!label.contentEquals(String.valueOf(node.getText()))) continue;
                        AccessibilityNodeInfo clickable = node;
                        for (int step = 0; clickable != null && !clickable.isClickable() && step < 8; step++) {
                            clickable = clickable.getParent();
                        }
                        if (clickable != null
                                && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            return true;
                        }
                    }
                } finally {
                    root.recycle();
                }
            }
            SystemClock.sleep(250);
        }
        return false;
    }

    private void dismissDialog() {
        try {
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            SystemClock.sleep(400);
        } catch (RuntimeException failure) {
            note("back-failed " + failure.getClass().getSimpleName());
        }
    }

    /** 与确认对话框同一入口：私有 beginTest(ModelConfig)，只发送页面内置虚构文本。 */
    private boolean beginTestDirectly(ModelSettingsStore store) {
        try {
            ModelConfig config = store.load();
            Method begin = ModelSettingsActivity.class.getDeclaredMethod("beginTest", ModelConfig.class);
            begin.setAccessible(true);
            main(() -> {
                try {
                    begin.invoke(page, config);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException(failure);
                }
                return null;
            });
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            note("reflection-failed " + failure.getClass().getSimpleName());
            return false;
        }
    }

    private List<String> texts() {
        List<String> texts = main(() -> {
            List<String> found = new ArrayList<>();
            collect(page.getWindow().getDecorView(), found);
            return found;
        });
        return texts == null ? new ArrayList<>() : texts;
    }

    private UiAutomation uiAutomation() {
        try {
            UiAutomation withFlag =
                    getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            if (withFlag != null) return withFlag;
        } catch (RuntimeException failure) {
            note("automation-flag-error " + failure.getClass().getSimpleName());
        }
        try {
            return getUiAutomation();
        } catch (RuntimeException failure) {
            note("automation-default-error " + failure.getClass().getSimpleName());
            return null;
        }
    }

    private static View findButton(View root, String prefix) {
        if (root == null || root.getVisibility() != View.VISIBLE) return null;
        if (root instanceof Button) {
            CharSequence text = ((Button) root).getText();
            if (text != null && text.toString().startsWith(prefix)) return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = findButton(group.getChildAt(index), prefix);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void collect(View root, List<String> texts) {
        if (root == null || root.getVisibility() != View.VISIBLE) return;
        if (root instanceof TextView) {
            CharSequence text = ((TextView) root).getText();
            if (text != null) {
                String value = text.toString().trim();
                if (!value.isEmpty()) texts.add(value);
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                collect(group.getChildAt(index), texts);
            }
        }
    }

    private String statusOf(List<String> texts) {
        String found = "";
        for (String text : texts) {
            if (text.contains(PASS) || text.contains(RUNNING) || text.contains("接口测试")
                    || text.contains("无法解密") || text.contains("配置无效")) {
                found = text;
            }
        }
        return found;
    }

    private String longestOf(List<String> texts) {
        String longest = "";
        for (String text : texts) {
            if (text.length() > longest.length()) longest = text;
        }
        return longest;
    }

    private <T> T main(Supplier<T> action) {
        final Object[] holder = new Object[1];
        runOnMainSync(() -> holder[0] = action.get());
        @SuppressWarnings("unchecked") T value = (T) holder[0];
        return value;
    }

    private void note(String line) {
        Log.i(TAG, "MODEL_CONNECT_STEP " + line);
    }

    private static String abbreviate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }
}
