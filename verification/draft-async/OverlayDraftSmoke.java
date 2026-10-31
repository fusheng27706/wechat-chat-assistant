package com.codextemp.wechatcaptureprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;

public final class OverlayDraftSmoke extends Instrumentation {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean source = new AtomicBoolean(true);
    private final AtomicInteger sets = new AtomicInteger();
    private final AtomicInteger sends = new AtomicInteger();
    private final AtomicInteger wrongThread = new AtomicInteger();
    private final AtomicInteger factories = new AtomicInteger();
    private final AtomicInteger inspections = new AtomicInteger();
    private final AtomicReference<CountDownLatch> hold = new AtomicReference<>();
    private final AtomicBoolean falseAfterWrite = new AtomicBoolean();
    private final AtomicBoolean delayAfterWrite = new AtomicBoolean();
    private final AtomicBoolean wroteBeforeDelay = new AtomicBoolean();
    private int checks;
    private Activity activity;
    private EditText input;
    private UiAutomation automation;
    private Rect bounds;
    private int window;
    private ExecutorService executor;
    private DraftWorker worker;
    private DraftOperations operations;
    private AssistOverlay overlay;
    private AnalysisResult analysis;
    private ChatGateService.Snapshot originalGate;
    private boolean renewing;
    private final Runnable renew = new Runnable() {
        @Override public void run() {
            if (renewing) {
                setStatic(ChatGateService.class, "current", new ChatGateService.Snapshot(true, -1, 991, 1000,
                        SystemClock.elapsedRealtime(), ChatViewport.create(new ScreenGeometry(1080, 2400, 440, 0,
                        0, 69, 0, 130, true), new ChatViewport.Bounds(0, 69, 1080, 2270), -1, 0, 0, Collections.emptyList())));
                handler.postDelayed(this, 60);
            }
        }
    };

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            ModelSettingsStore settings = new ModelSettingsStore(getTargetContext());
            require(!settings.hasKey() && !settings.cloudAllowed() && !AssistantCaptureService.running()
                    && !CaptureService.running() && !ChatGateService.enabled(), "isolated-no-capture-model-or-gate");
            originalGate = ChatGateService.snapshot();
            android.util.Log.i("OverlayDraftSmoke", "WAITING_FOR_OWN_FIXTURE_ACTIVITY");
            activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            fixture();
            automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo info = automation.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            automation.setServiceInfo(info);
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            long rootDeadline = SystemClock.elapsedRealtime() + 3000;
            while (root == null && SystemClock.elapsedRealtime() < rootDeadline) {
                SystemClock.sleep(100);
                root = automation.getRootInActiveWindow();
            }
            require(root != null, "own-fixture-root");
            window = root.getWindowId();
            root.recycle();
            bounds = read(() -> { Rect value = new Rect(); input.getGlobalVisibleRect(value); return value; });
            executor = Executors.newSingleThreadExecutor(action -> {
                Thread thread = new Thread(action, "draft-fixture-nodes"); thread.setDaemon(true); return thread;
            });
            worker = new DraftWorker(executor, action -> handler.post(action),
                    (action, delay) -> handler.postDelayed(action, delay), SystemClock::elapsedRealtime, 800);
            operations = new DraftOperations(worker, proof -> source::get, (proof, current) -> editor(current));
            onMain(() -> {
                overlay = new AssistOverlay(activity, operations);
                set(overlay, "attached", true);
                renewing = true;
                renew.run();
                publish();
            });
            exercise();
            require(!settings.hasKey() && !settings.cloudAllowed() && wrongThread.get() == 0 && sends.get() == 0, "no-cloud-send-or-main-node-work");
            output.putString("result", "PASS overlayDraftChecks=" + checks + " fixtureSetTextActions=" + sets.get()
                    + " fixtureSendActions=" + sends.get() + " mainNodeCalls=" + wrongThread.get()
                    + " injectedSource=true offscreenController=true projectionGrants=0 modelRequests=0 wechatWrites=0 chatFiles=0");
        } catch (Throwable failure) {
            output.putString("result", "FAIL overlayDraftChecks=" + checks + " type=" + failure.getClass().getSimpleName()
                    + (failure instanceof AssertionError ? " check=" + failure.getMessage() : ""));
        } finally {
            CountDownLatch blocked = hold.getAndSet(null);
            if (blocked != null) blocked.countDown();
            onMain(() -> {
                renewing = false;
                handler.removeCallbacks(renew);
                if (overlay != null) { set(overlay, "attached", false); overlay.destroy(); }
                CaptureControl.stopAll(getTargetContext());
                if (originalGate != null) setStatic(ChatGateService.class, "current", originalGate);
                if (activity != null) activity.finish();
            });
            if (executor != null) executor.shutdownNow();
        }
        android.util.Log.i("OverlayDraftSmoke", output.getString("result"));
        finish(output.getString("result").startsWith("PASS") ? -1 : 0, output);
    }

    private void fixture() {
        onMain(() -> {
            LinearLayout layout = new LinearLayout(activity);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setFocusableInTouchMode(true);
            TextView disclosure = new TextView(activity);
            disclosure.setText("虚构回复控制器检查，不是微信；只修改本测试输入框，不发送");
            layout.addView(disclosure);
            input = new EditText(activity);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            input.setHint("FICTIONAL_HINT_NOT_DRAFT");
            input.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public boolean performAccessibilityAction(View host, int action, Bundle args) {
                    if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                        sets.incrementAndGet();
                        boolean changed = super.performAccessibilityAction(host, action, args);
                        return !falseAfterWrite.get() && changed;
                    }
                    return super.performAccessibilityAction(host, action, args);
                }
            });
            layout.addView(input, new LinearLayout.LayoutParams(-1, 220));
            Button send = new Button(activity); send.setText("虚构发送，测试不得点击");
            send.setOnClickListener(view -> sends.incrementAndGet()); layout.addView(send);
            activity.setContentView(layout); layout.requestFocus();
        });
        SystemClock.sleep(400);
    }

    private DraftPlan.Editor editor(BooleanSupplier current) {
        if (Looper.myLooper() == Looper.getMainLooper()) wrongThread.incrementAndGet();
        factories.incrementAndGet();
        CountDownLatch blocked = hold.get();
        if (blocked != null) {
            try { blocked.await(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        }
        DraftPlan.Editor actual = new AccessibilityDraftEditor(automation::getRootInActiveWindow,
                getTargetContext().getPackageName(), window, bounds, current);
        return new DraftPlan.Editor() {
            @Override public DraftPlan.Field inspect() { inspections.incrementAndGet(); return actual.inspect(); }
            @Override public boolean requestSetText(DraftPlan.Field expected, String replacement) {
                boolean result = actual.requestSetText(expected, replacement);
                if (delayAfterWrite.get()) {
                    wroteBeforeDelay.set(true);
                    SystemClock.sleep(1400);
                }
                return result;
            }
        };
    }

    private void publish() {
        try {
            AssistantState.clear(); AssistantState.paused = false; AssistantState.expanded = true;
            java.util.List<ChatMessage> messages = Collections.singletonList(new ChatMessage(false, "虚构范围确认", 0));
            AssistantState.publish(991, "FICTIONAL_DRAFT", "FICTIONAL_TOKEN", messages, 1,
                    new MessageTracker.Update(0, MessageTracker.Kind.BASELINE));
            JSONObject result = new JSONObject().put("summary", "虚构测试，不是真实AI")
                    .put("intents", new JSONArray().put(new JSONObject().put("explanation", "可能需要澄清")
                            .put("level", "低").put("evidence", new JSONArray().put(messages.get(0).text))))
                    .put("replies", new JSONArray().put(new JSONObject().put("objective", "澄清").put("text", "FICTIONAL_REPLY_ONE"))
                            .put(new JSONObject().put("objective", "边界").put("text", "FICTIONAL_REPLY_TWO")))
                    .put("limitations", new JSONArray().put("仅供测试"));
            analysis = AnalysisResult.parse(result.toString(), messages);
            require(AssistantState.analyzed(AssistantState.snapshot(), analysis), "fictional-analysis-state-only");
            invoke(overlay, "renderReplies", new Class<?>[]{AnalysisResult.class}, analysis);
        } catch (org.json.JSONException failure) { throw new AssertionError("fictional-analysis-setup", failure); }
    }

    private void exercise() {
        onMain(() -> invoke(overlay, "checkCapability", new Class<?>[0]));
        await(() -> status().contains("唯一可写"), "async-capability");
        require(sets.get() == 0, "capability-read-only");
        prepare(0);
        require(sets.get() == 0, "prepare-read-only");
        commit(DraftPlan.Mode.REPLACE);
        await(() -> status().contains("已核对当前草稿"), "replace-automatic-readback");
        require(value().equals("FICTIONAL_REPLY_ONE") && sets.get() == 1, "one-native-replace-no-retry");

        setValue("EXISTING_DRAFT"); prepare(1); commit(DraftPlan.Mode.KEEP);
        require(value().equals("EXISTING_DRAFT") && sets.get() == 1, "keep-does-not-write");
        prepare(1); commit(DraftPlan.Mode.APPEND);
        await(() -> status().contains("已核对当前草稿"), "append-readback");
        require(value().equals("EXISTING_DRAFT\nFICTIONAL_REPLY_TWO") && sets.get() == 2, "append-preserves-original");

        prepare(0); setValue("USER_CHANGED_DRAFT"); commit(DraftPlan.Mode.REPLACE);
        await(() -> status().contains("原草稿或输入框已变化"), "changed-draft-refused");
        require(value().equals("USER_CHANGED_DRAFT") && sets.get() == 2, "no-overwrite-user-edit");

        setValue(""); falseAfterWrite.set(true); prepare(0); commit(DraftPlan.Mode.REPLACE);
        await(() -> status().contains("已核对当前草稿"), "false-return-does-not-assume-no-write");
        require(value().equals("FICTIONAL_REPLY_ONE") && sets.get() == 3, "false-return-readonly-verification");
        falseAfterWrite.set(false);

        setValue("CANCEL_ORIGINAL"); prepare(1);
        CountDownLatch blocked = new CountDownLatch(1); hold.set(blocked);
        int beforeFactory = factories.get(); commit(DraftPlan.Mode.APPEND);
        final int commitFactory = beforeFactory;
        await(() -> factories.get() > commitFactory, "commit-entered-background");
        onMain(() -> {
            boolean possible = (boolean) field(overlay, "writeMayHaveOccurred");
            require(possible, "possible-write-set-before-worker-return");
            invoke(overlay, "clearSelection", new Class<?>[0]);
        });
        require(worker.busy(), "cancel-keeps-actual-slot");
        blocked.countDown(); hold.set(null); await(() -> !worker.busy(), "cancelled-worker-exits");
        SystemClock.sleep(150);
        require(sets.get() == 3 && value().equals("CANCEL_ORIGINAL") && plan() == null, "cancel-before-set-no-late-plan-or-write");

        CountDownLatch old = new CountDownLatch(1); hold.set(old); beforeFactory = factories.get();
        choose(0); onMain(() -> invoke(overlay, "prepareDraft", new Class<?>[0]));
        final int pendingFactory = beforeFactory;
        await(() -> factories.get() > pendingFactory, "old-prepare-pending");
        choose(1); old.countDown(); hold.set(null); await(() -> !worker.busy(), "old-prepare-drained");
        SystemClock.sleep(150);
        require(plan() == null && read(() -> ((TextView) field(overlay, "replyPreview")).getText().toString()).contains("FICTIONAL_REPLY_TWO"), "new-choice-revokes-old-prepare");

        CountDownLatch revoked = new CountDownLatch(1); hold.set(revoked); int inspectBefore = inspections.get();
        beforeFactory = factories.get(); onMain(() -> invoke(overlay, "prepareDraft", new Class<?>[0]));
        final int revokeFactory = beforeFactory;
        await(() -> factories.get() > revokeFactory, "source-revoke-pending"); source.set(false); revoked.countDown(); hold.set(null);
        await(() -> !worker.busy(), "revoked-worker-drained");
        await(() -> status().contains("检查超时"), "revoked-status");
        require(plan() == null && inspections.get() == inspectBefore && sets.get() == 3, "revoked-source-does-not-read-or-write");
        source.set(true);

        setValue(""); prepare(0); delayAfterWrite.set(true); commit(DraftPlan.Mode.REPLACE);
        await(wroteBeforeDelay::get, "actual-write-before-delayed-return");
        await(() -> status().contains("可能已经改变草稿"), "timeout-warns-about-possible-write");
        require(worker.busy() && sets.get() == 4, "timeout-after-write-keeps-slot");
        await(() -> !worker.busy(), "delayed-write-returns"); SystemClock.sleep(700);
        require(sets.get() == 4 && value().equals("FICTIONAL_REPLY_ONE") && !status().contains("已核对当前草稿"), "no-late-success-or-automatic-retry");
        delayAfterWrite.set(false);

        AtomicReference<DraftWorker.Result<String>> denied = new AtomicReference<>(); AtomicInteger forbidden = new AtomicInteger();
        onMain(() -> new DraftOperations().submit(null, () -> true, (editor, current) -> { forbidden.incrementAndGet(); return "NEVER"; }, denied::set));
        await(() -> denied.get() != null, "default-source-null-proof-completes");
        require(denied.get().status == DraftWorker.Status.EXPIRED && forbidden.get() == 0, "production-default-does-not-use-injected-permission");
        onMain(() -> require(field(new DraftOperations(), "worker") == field(new DraftOperations(), "worker"), "default-operations-share-one-process-worker"));

        CountDownLatch closing = new CountDownLatch(1); hold.set(closing); int closingFactory = factories.get();
        choose(0); onMain(() -> invoke(overlay, "prepareDraft", new Class<?>[0]));
        await(() -> factories.get() > closingFactory, "close-with-actual-worker-pending");
        AssistOverlay previous = overlay;
        onMain(() -> {
            set(previous, "attached", false); previous.destroy();
            overlay = new AssistOverlay(activity, operations); set(overlay, "attached", true); publish();
        });
        require(worker.busy() && field(previous, "draftTask") == null, "destroy-cancels-ticket-but-does-not-release-slot");
        choose(1); onMain(() -> invoke(overlay, "prepareDraft", new Class<?>[0]));
        require(status().contains("前次输入框查询仍在结束") && factories.get() == closingFactory + 1, "new-overlay-cannot-start-second-node-query");
        closing.countDown(); hold.set(null); await(() -> !worker.busy(), "destroyed-owner-worker-exits"); SystemClock.sleep(150);
        require(plan() == null && status().contains("前次输入框查询仍在结束") && sets.get() == 4, "old-owner-does-not-repopulate-new-ui");
    }

    private void choose(int index) { onMain(() -> ((Button) ((LinearLayout) field(overlay, "replyChoices")).getChildAt(index)).performClick()); }
    private void prepare(int choice) { choose(choice); onMain(() -> invoke(overlay, "prepareDraft", new Class<?>[0])); await(() -> plan() != null, "plan-ready"); }
    private void commit(DraftPlan.Mode mode) { onMain(() -> invoke(overlay, "commitDraft", new Class<?>[]{DraftPlan.Mode.class}, mode)); }
    private Object plan() { return read(() -> field(overlay, "draftPlan")); }
    private String status() { return read(() -> ((TextView) field(overlay, "operationStatus")).getText().toString()); }
    private String value() { return read(() -> input.getText().toString()); }
    private void setValue(String value) { onMain(() -> input.setText(value)); SystemClock.sleep(100); }
    private <Value> Value read(Supplier<Value> source) { AtomicReference<Value> result = new AtomicReference<>(); onMain(() -> result.set(source.get())); return result.get(); }
    private void onMain(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>(); runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError(failure.get().getMessage(), failure.get());
    }
    private static Object field(Object target, String name) {
        try { Field value = target.getClass().getDeclaredField(name); value.setAccessible(true); return value.get(target); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("fixture-field-" + name, failure); }
    }
    private static void set(Object target, String name, Object value) {
        try { Field member = target.getClass().getDeclaredField(name); member.setAccessible(true); member.set(target, value); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("fixture-set-" + name, failure); }
    }
    private static void setStatic(Class<?> type, String name, Object value) {
        try { Field member = type.getDeclaredField(name); member.setAccessible(true); member.set(null, value); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("fixture-static-" + name, failure); }
    }
    private static void invoke(Object target, String name, Class<?>[] types, Object... values) {
        try { Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(target, values); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("fixture-method-" + name, failure); }
    }
    private void await(BooleanSupplier condition, String label) { long limit = SystemClock.elapsedRealtime() + 4000; while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < limit) SystemClock.sleep(30); require(condition.getAsBoolean(), label); }
    private void require(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
}
