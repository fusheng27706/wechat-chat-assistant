package com.codextemp.wechatcaptureprobe;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public final class AnalysisEventSmoke extends Instrumentation {
    private static final String PREFIX = "analysis_event_verify_";
    private static final String TITLE = "虚构触发测试，不是微信";
    private static final List<ChatMessage> BASE = Arrays.asList(
            new ChatMessage(false, "请先确认范围", 0), new ChatMessage(true, "好的", 1));
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger network = new AtomicInteger();
    private final AtomicBoolean scope = new AtomicBoolean(true);
    private volatile List<ChatMessage> supplied;
    private volatile long lastStarted;
    private volatile boolean fakeFailure;
    private int checks;
    private Context isolated;
    private AnalysisCoordinator coordinator;
    private boolean fixtureActive;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle output = new Bundle();
        boolean originalPaused = AssistantState.paused;
        try {
            ModelSettingsStore real = new ModelSettingsStore(getTargetContext());
            require(!real.hasKey() && !real.cloudAllowed() && !AssistantCaptureService.running()
                    && !CaptureService.running() && !ChatGateService.enabled(), "real-state-off-precondition");
            fixtureActive = true;
            isolated = new ContextWrapper(getTargetContext()) {
                @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                    return super.getSharedPreferences(PREFIX + name, mode);
                }
            };
            clearIsolated();
            URL.setURLStreamHandlerFactory(protocol -> new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL address) throws IOException {
                    network.incrementAndGet();
                    throw new IOException("All network blocked by fictional test");
                }
            });
            ModelSettingsStore settings = new ModelSettingsStore(isolated);
            settings.save(new ModelConfig("https://analysis-event-test.invalid/v1/chat/completions",
                    "fictional-only", 5, 1024, true, false), "FICTIONAL-KEY-NOT-REAL");
            require(settings.setCloudAllowed(true, settings.revision()), "isolated-fictional-consent");
            exercise();
            require(network.get() == 0 && !real.hasKey() && !real.cloudAllowed(), "real-settings-and-network-unchanged");
            output.putString("result", "PASS analysisEventChecks=" + checks + " fakeAnalyses=" + calls.get()
                    + " realNetworkRequests=" + network.get() + " wechatActions=0");
        } catch (Throwable failure) {
            output.putString("result", "FAIL analysisEventChecks=" + checks + " type=" + failure.getClass().getSimpleName()
                    + " check=" + failure.getMessage() + " fakeAnalyses=" + calls.get() + " realNetworkRequests=" + network.get());
        } finally {
            runOnMainSync(() -> {
                if (coordinator != null) coordinator.close();
                if (fixtureActive) {
                    AssistantState.clear();
                    AssistantState.paused = originalPaused;
                }
            });
            clearIsolated();
        }
        finish(output.getString("result").startsWith("PASS") ? -1 : 0, output);
    }

    private void exercise() {
        fresh();
        runOnMainSync(() -> {
            publish(BASE, MessageTracker.Kind.BASELINE);
            publish(BASE, MessageTracker.Kind.KNOWN);
            require(AssistantState.snapshot().kind == MessageTracker.Kind.KNOWN, "latest-increment-description-remains-honest");
            coordinator.poll();
        });
        await(() -> calls.get() == 1, 2400, "baseline-survives-known-before-first-poll");
        await(() -> AssistantState.analysis != null, 1200, "baseline-result-delivered");
        runOnMainSync(() -> coordinator.poll());
        SystemClock.sleep(1100);
        require(calls.get() == 1, "same-snapshot-no-repeat");

        fresh();
        List<ChatMessage> appended = appended("请再核对时间");
        int before = calls.get();
        runOnMainSync(() -> {
            publish(BASE, MessageTracker.Kind.GAP);
            publish(appended, MessageTracker.Kind.APPEND);
            publish(appended, MessageTracker.Kind.KNOWN);
            coordinator.poll();
        });
        await(() -> calls.get() == before + 1, 2400, "append-survives-known-before-first-poll");
        await(() -> AssistantState.analysis != null, 1200, "append-result-delivered");
        require(supplied.size() == appended.size(), "latest-complete-visible-input");

        fresh();
        int beforeGap = calls.get();
        runOnMainSync(() -> {
            publish(BASE, MessageTracker.Kind.BASELINE);
            publish(appended, MessageTracker.Kind.GAP);
            publish(appended, MessageTracker.Kind.KNOWN);
            coordinator.poll();
        });
        SystemClock.sleep(1100);
        require(calls.get() == beforeGap, "different-gap-does-not-inherit-baseline");

        for (MessageTracker.Kind uncertain : Arrays.asList(MessageTracker.Kind.GAP, MessageTracker.Kind.AMBIGUOUS,
                MessageTracker.Kind.EARLIER)) {
            fresh();
            int beforeUncertain = calls.get();
            runOnMainSync(() -> {
                publish(BASE, MessageTracker.Kind.BASELINE);
                publish(BASE, uncertain);
                publish(BASE, MessageTracker.Kind.KNOWN);
                coordinator.poll();
            });
            SystemClock.sleep(1100);
            require(calls.get() == beforeUncertain, "explicit-" + uncertain + "-revokes-unobserved-event");
        }

        fresh();
        int beforeGeneration = calls.get();
        runOnMainSync(() -> {
            publish(BASE, MessageTracker.Kind.BASELINE);
            AssistantState.publish(78, TITLE, "fictional-one-chat", BASE, 1,
                    new MessageTracker.Update(0, MessageTracker.Kind.KNOWN));
            coordinator.poll();
        });
        SystemClock.sleep(1100);
        require(calls.get() == beforeGeneration, "different-generation-does-not-inherit-event");

        fresh();
        int beforeWarning = calls.get();
        List<ChatMessage> warningChanged = Arrays.asList(
                new ChatMessage(false, "请先确认范围", 0, "虚构风险变化", 0), BASE.get(1));
        runOnMainSync(() -> {
            publish(BASE, MessageTracker.Kind.BASELINE);
            publish(warningChanged, MessageTracker.Kind.KNOWN);
            coordinator.poll();
        });
        SystemClock.sleep(1100);
        require(calls.get() == beforeWarning, "different-warning-does-not-inherit-event");

        fresh();
        int beforeAmbiguous = calls.get();
        MessageTracker tracker = new MessageTracker();
        List<ChatMessage> repeated = Arrays.asList(new ChatMessage(false, "重复的虚构文字", 0));
        List<ChatMessage> repeatedTwice = Arrays.asList(repeated.get(0), repeated.get(0));
        runOnMainSync(() -> {
            publish(repeated, tracker.update("fictional-one-chat", repeated).kind);
            MessageTracker.Update ambiguous = tracker.update("fictional-one-chat", repeatedTwice);
            require(ambiguous.kind == MessageTracker.Kind.AMBIGUOUS, "actual-tracker-repeat-remains-ambiguous");
            publish(repeatedTwice, ambiguous.kind);
            publish(repeatedTwice, tracker.update("fictional-one-chat", repeatedTwice).kind);
            coordinator.poll();
        });
        SystemClock.sleep(1100);
        require(calls.get() == beforeAmbiguous, "ambiguous-repeat-does-not-inherit-baseline");

        fresh();
        int beforeClear = calls.get();
        runOnMainSync(() -> {
            publish(BASE, MessageTracker.Kind.BASELINE);
            AssistantState.clear();
            publish(BASE, MessageTracker.Kind.KNOWN);
            coordinator.poll();
        });
        SystemClock.sleep(1100);
        require(calls.get() == beforeClear, "clear-revokes-unobserved-event");

        fresh();
        int beforeLeave = calls.get();
        runOnMainSync(() -> {
            publish(BASE, MessageTracker.Kind.BASELINE);
            AssistantState.leave(78);
            publish(BASE, MessageTracker.Kind.KNOWN);
            coordinator.poll();
        });
        SystemClock.sleep(1100);
        require(calls.get() == beforeLeave, "leave-revokes-unobserved-event");

        fresh();
        int beforeScope = calls.get();
        runOnMainSync(() -> {
            publish(BASE, MessageTracker.Kind.APPEND);
            scope.set(false);
            coordinator.poll();
            publish(BASE, MessageTracker.Kind.KNOWN);
            scope.set(true);
            coordinator.poll();
        });
        await(() -> calls.get() == beforeScope + 1, 2400, "temporary-source-unavailable-does-not-lose-event");

        fresh();
        int beforeBurst = calls.get();
        runOnMainSync(() -> {
            publish(BASE, MessageTracker.Kind.BASELINE);
            coordinator.poll();
        });
        SystemClock.sleep(300);
        runOnMainSync(() -> {
            publish(appended, MessageTracker.Kind.APPEND);
            publish(appended, MessageTracker.Kind.KNOWN);
            coordinator.poll();
        });
        SystemClock.sleep(650);
        require(calls.get() == beforeBurst, "burst-restarts-stability-delay");
        await(() -> calls.get() == beforeBurst + 1, 1400, "burst-coalesces-latest-event");
        require(supplied.size() == 3, "burst-does-not-send-obsolete-window");

        fresh();
        int beforeInvalidate = calls.get();
        runOnMainSync(() -> {
            publish(BASE, MessageTracker.Kind.BASELINE);
            AssistantState.invalidateScope("Fictional source loss before polling");
            publish(BASE, MessageTracker.Kind.KNOWN);
            coordinator.poll();
        });
        SystemClock.sleep(1100);
        require(calls.get() == beforeInvalidate, "source-invalidation-clears-unobserved-eligibility");

        fresh();
        int beforeToken = calls.get();
        runOnMainSync(() -> {
            publish(BASE, MessageTracker.Kind.BASELINE);
            AssistantState.publish(88, TITLE, "fictional-other-chat", BASE, 1,
                    new MessageTracker.Update(0, MessageTracker.Kind.KNOWN));
            coordinator.poll();
        });
        SystemClock.sleep(1100);
        require(calls.get() == beforeToken, "different-chat-does-not-inherit-event");

        fresh();
        fakeFailure = true;
        int beforeFailure = calls.get();
        runOnMainSync(() -> {
            publish(BASE, MessageTracker.Kind.BASELINE);
            publish(BASE, MessageTracker.Kind.KNOWN);
            coordinator.poll();
        });
        await(() -> calls.get() == beforeFailure + 1 && AssistantState.analysisStatus.contains("未自动重试"),
                2400, "failure-still-completes-attempt");
        runOnMainSync(() -> coordinator.poll());
        SystemClock.sleep(1100);
        require(calls.get() == beforeFailure + 1, "eligible-failed-window-does-not-auto-retry");
        fakeFailure = false;

        fresh();
        int beforeInterval = calls.get();
        runOnMainSync(() -> {
            publish(BASE, MessageTracker.Kind.BASELINE);
            coordinator.poll();
        });
        await(() -> calls.get() == beforeInterval + 1, 2400, "interval-first-started");
        await(() -> AssistantState.analysis != null, 1200, "interval-first-delivered");
        long firstStart = lastStarted;
        runOnMainSync(() -> {
            publish(appended, MessageTracker.Kind.APPEND);
            publish(appended, MessageTracker.Kind.KNOWN);
            coordinator.poll();
        });
        SystemClock.sleep(1200);
        require(calls.get() == beforeInterval + 1, "five-second-request-spacing-retained");
        await(() -> calls.get() == beforeInterval + 2, 4500, "later-event-eventually-starts");
        require(lastStarted - firstStart >= 5000, "actual-start-spacing-at-least-five-seconds");
    }

    private void fresh() {
        runOnMainSync(() -> {
            if (coordinator != null) coordinator.close();
            AssistantState.clear();
            AssistantState.paused = false;
            scope.set(true);
            coordinator = new AnalysisCoordinator(isolated, permission -> new AnalysisProvider() {
                @Override public AnalysisResult analyze(ModelConfig config, String credential, List<ChatMessage> messages) throws IOException {
                    supplied = messages;
                    lastStarted = SystemClock.elapsedRealtime();
                    calls.incrementAndGet();
                    if (fakeFailure) throw new IOException("Fictional failure");
                    try {
                        return AnalysisResult.parse("{\"summary\":\"虚构触发测试，非真实模型\",\"intents\":[{\"explanation\":\"可能需要澄清\",\"level\":\"低\",\"evidence\":[\"请先确认范围\"]}],"
                                + "\"replies\":[{\"objective\":\"澄清\",\"text\":\"先核对哪些部分？\"},{\"objective\":\"边界\",\"text\":\"确认后再安排。\"}],\"limitations\":[\"虚构测试\"]}", messages);
                    } catch (org.json.JSONException failure) {
                        throw new IOException("Fictional parsing failed");
                    }
                }
                @Override public void cancel() {
                }
            }, snapshot -> scope.get());
        });
    }

    private List<ChatMessage> appended(String text) {
        List<ChatMessage> messages = new ArrayList<>(BASE);
        messages.add(new ChatMessage(false, text, 2));
        return messages;
    }

    private void publish(List<ChatMessage> messages, MessageTracker.Kind kind) {
        AssistantState.publish(77, TITLE, "fictional-one-chat", messages, 1, new MessageTracker.Update(0, kind));
    }

    private void await(BooleanSupplier condition, long timeout, String label) {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(25);
        require(condition.getAsBoolean(), label);
    }

    private void clearIsolated() {
        for (String name : Arrays.asList("model_settings", "assistant", "confirmed_memory")) {
            getTargetContext().deleteSharedPreferences(PREFIX + name);
        }
    }

    private void require(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
}
