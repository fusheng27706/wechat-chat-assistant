package com.codextemp.wechatcaptureprobe;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

final class AnalysisCoordinator {
    private static final String TAG = "ChatAssistant";
    private static AnalysisCoordinator active;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ModelSettingsStore settings;
    private final ConversationRulesStore rules;
    private final ConfirmedMemoryStore memory;
    private final Function<BooleanSupplier, AnalysisProvider> factory;
    private final Predicate<AssistantState.Snapshot> scope;
    private volatile long sequence;
    private volatile boolean closed;
    private volatile boolean demoMode;
    private AnalysisProvider provider;
    private Future<?> task;
    private Runnable pending;
    private AssistantState.Snapshot requested;
    private long configRevision = -1;
    private String attempted = "";
    private long lastStarted = -5000;
    // 仅临时原生验证启用；记录数字时序，不持有聊天或额外查询来源。
    java.util.function.Consumer<long[]> timingObserver;
    private long quietWakeAt = -1;
    // 诊断：只在门控结论或跳过原因变化时写日志，避免逐帧刷屏。
    private volatile String lastTrace = "";
    private volatile long lastTraceAt;
    private final Runnable quietWake = () -> {
        quietWakeAt = -1;
        consider(false);
    };

    /** 到期只重试原门控；新交互会在重试时延后，不能凭定时器授予来源许可。 */
    private void scheduleQuietWake() {
        long now = SystemClock.elapsedRealtime();
        long due = now + ChatInteraction.quietRemaining(now);
        if (quietWakeAt == due) return;
        handler.removeCallbacks(quietWake);
        quietWakeAt = due;
        handler.postDelayed(quietWake, Math.max(0, due - now));
    }

    private void cancelQuietWake() {
        handler.removeCallbacks(quietWake);
        quietWakeAt = -1;
    }

    AnalysisCoordinator(Context context) {
        this(context, ChatCompletionsClient::new, AssistantCaptureService::analysisScopeCurrent);
    }

    AnalysisCoordinator(Context context, Function<BooleanSupplier, AnalysisProvider> factory,
                        Predicate<AssistantState.Snapshot> scope) {
        settings = new ModelSettingsStore(context);
        rules = new ConversationRulesStore(context);
        memory = new ConfirmedMemoryStore(context);
        this.factory = factory;
        this.scope = scope;
        // 未配置模型时自动提供明确标记的固定示例，不读取聊天来构造模拟回复。
        demoMode = !settings.hasKey();
        active = this;
    }

    static boolean demoEnabled() { return active != null && active.demoMode; }

    /** 只在当前助手运行期启用；取消真实请求，不修改任何云端许可或配置。 */
    static void toggleDemo() {
        if (Looper.myLooper() != Looper.getMainLooper() || active == null || active.closed) return;
        active.cancel();
        active.demoMode = !active.demoMode;
        AssistantState.clearAnalysis(active.demoMode ? "本地模拟已开启，等待有效识别" : "已退出本地模拟");
        active.consider(false);
    }

    static void retryCurrent() {
        if (active != null) {
            active.consider(true);
        } else {
            AssistantState.clearAnalysis("请先开启聊天辅助");
        }
    }

    void poll() {
        consider(false);
    }

    /**
     * 会话级门控：只判断「还是同一个聊天、还在辅助范围内、配置未变」，
     * 不比较可见集合。请求进行中画面必然会有变化，用它做复核才不会误杀在途请求。
     */
    private String sessionGate(AssistantState.Snapshot snapshot, long revision, long requestSequence) {
        if (closed) return "closed";
        if (demoMode) return "demo";
        if (sequence != requestSequence) return "sequence";
        if (settings.revision() != revision) return "revision";
        if (!settings.cloudAllowed()) return "cloud";
        if (!scope.test(snapshot)) return "scope";
        if (rules.excludes(snapshot.title)) return "excluded";
        if (snapshot.context.memory != null && snapshot.context.memoryRevision != memory.revision()) return "memory";
        return "";
    }

    /** 发送前复核：会话级 + 快照级，逐项返回第一个未通过的门控名。 */
    private String gate(AssistantState.Snapshot snapshot, long revision, long requestSequence) {
        String session = sessionGate(snapshot, revision, requestSequence);
        if (!session.isEmpty()) return session;
        if (!AssistantState.matches(snapshot)) return "matches";
        if (snapshot.coverage.obscured() && !settings.partialAnalysisAllowed()) return "coverage";
        return "";
    }

    private boolean permitted(AssistantState.Snapshot snapshot, long revision, long requestSequence) {
        String failed = gate(snapshot, revision, requestSequence);
        if (!failed.isEmpty()) {
            trace("DROP gate=" + failed + " frame=" + snapshot.frame + " kind=" + snapshot.kind);
        }
        return failed.isEmpty();
    }

    /** 同一条诊断只在结论变化或超过 5 秒时落一条，避免日志洪泛。 */
    private void trace(String line) {
        long now = SystemClock.elapsedRealtime();
        if (line.equals(lastTrace) && now - lastTraceAt < 5000) {
            return;
        }
        lastTrace = line;
        lastTraceAt = now;
        Log.i(TAG, "ANALYSIS " + line);
    }

    private void consider(boolean manual) {
        if (closed) {
            return;
        }
        if (demoMode) {
            long entered = timingObserver == null ? 0 : SystemClock.elapsedRealtime();
            AssistantState.Snapshot current = AssistantState.snapshot();
            int reason = 0;
            long scopeMs = 0;
            if (!AssistantState.matches(current)) reason = 1;
            else {
                long beforeScope = timingObserver == null ? 0 : SystemClock.elapsedRealtime();
                if (!scope.test(current)) reason = 2;
                if (timingObserver != null) scopeMs = SystemClock.elapsedRealtime() - beforeScope;
            }
            if (reason == 0 && rules.excludes(current.title)) reason = 3;
            if (reason != 0) {
                AssistantState.clearAnalysis("本地模拟：等待当前聊天有效识别");
            } else if (!ChatInteraction.quiet(SystemClock.elapsedRealtime())) reason = 4;
            else if (AssistantState.analysis == null || !AssistantState.analysis.simulated) {
                AssistantState.analyzed(current, AnalysisResult.localDemo());
                reason = 6;
            } else reason = 5;
            if (reason == 4) scheduleQuietWake();
            else if (quietWakeAt >= 0) cancelQuietWake();
            if (timingObserver != null) timingObserver.accept(new long[]{AssistantState.frames, entered,
                    SystemClock.elapsedRealtime(), reason, ChatInteraction.quietRemaining(SystemClock.elapsedRealtime()), scopeMs});
            return;
        }
        long revision = settings.revision();
        if (configRevision != revision) {
            cancel();
            configRevision = revision;
            attempted = "";
            AssistantState.clearAnalysis("模型配置变化，请重新确认云端开关");
        }
        AssistantState.Snapshot snapshot = AssistantState.snapshot();
        // 在途请求只按会话级复核：可见集合漂移、浮窗高度变化都不再取消它，
        // 否则一次 8 秒的模型请求会被逐帧的画面变化反复杀掉。
        if (requested != null && !sessionGate(requested, revision, sequence).isEmpty()) {
            trace("CANCEL inflight gate=" + sessionGate(requested, revision, sequence));
            cancel();
        }
        if (!settings.cloudAllowed()) {
            cancel();
            AssistantState.clearAnalysis(AssistantState.excluded ? "已排除，不进行云端分析"
                    : "云端分析关闭；可在模型设置中配置并单独开启");
            return;
        }
        if (snapshot.coverage.obscured() && !settings.partialAnalysisAllowed()) {
            cancel();
            AssistantState.clearAnalysis("有浮窗遮挡，默认暂停分析；可在模型设置中自行允许仅分析未遮挡部分，可能遗漏关键回复");
            return;
        }
        if (!AssistantState.matches(snapshot) || !scope.test(snapshot) || rules.excludes(snapshot.title)) {
            trace("SKIP gate=" + gate(snapshot, revision, sequence)
                    + " paused=" + AssistantState.paused + " excluded=" + AssistantState.excluded);
            return;
        }
        if (!manual && (snapshot.contentSignature.equals(attempted) || requested != null)) {
            trace("SKIP auto=already-attempted");
            return;
        }
        // 自动触发不再要求"窗口连续稳定"：锚点（底部首条消息正文）与上次尝试相同即跳过，
        // 不同就分析一次。浮窗高度、滚动、采集编号变化都不会再阻断自动建议。
        cancel();
        requested = snapshot;
        long requestSequence = sequence;
        AssistantState.clearAnalysis("等待稳定快照后分析；自动请求间隔至少 5 秒");
        pending = () -> start(snapshot, revision, requestSequence);
        long delay = Math.max(900, 5000 - (SystemClock.elapsedRealtime() - lastStarted));
        handler.postDelayed(pending, delay);
    }

    private void start(AssistantState.Snapshot snapshot, long revision, long requestSequence) {
        pending = null;
        // 发送前复核：浮窗高度变化、滚动或新增消息都会改变可见集合，旧快照在 900ms 延迟后
        // 几乎必然不再 matches()，请求会被永久丢弃。改用发送瞬间的最新快照，门控一项不减。
        AssistantState.Snapshot bound = resolve(snapshot, revision, requestSequence);
        if (bound == null) {
            trace("DROP gate=stale-after-delay");
            cancel();
            return;
        }
        ModelConfig config = settings.load();
        BooleanSupplier permission = () -> permitted(bound, revision, requestSequence);
        AnalysisProvider client = factory.apply(permission);
        provider = client;
        attempted = bound.contentSignature;
        lastStarted = SystemClock.elapsedRealtime();
        AssistantState.analysisStatus = "正在分析当前可见文字；切换会话或暂停会取消，已发送数据无法撤回";
        trace("SEND messages=" + bound.messages.size() + " coverage=" + bound.coverage.signature()
                + " kind=" + bound.kind + " frame=" + bound.frame
                + (bound == snapshot ? " snapshot=original" : " snapshot=refreshed"));
        task = worker.submit(() -> {
            AnalysisResult result = null;
            String failure = "";
            try {
                if (!permission.getAsBoolean()) {
                    trace("DROP gate=before-send");
                    return;
                }
                String credential = settings.readKey();
                if (permission.getAsBoolean()) {
                    result = client.analyze(config, credential, new AnalysisInput(bound.messages, bound.context, bound.coverage));
                    if (result == null || (bound.context.mode == ConversationContext.Mode.GROUP) != (result.discussion != null)) {
                        throw new IOException("Analysis mode mismatch");
                    }
                }
            } catch (GeneralSecurityException exception) {
                failure = "无法解密密钥，请在模型设置中重新填写；未自动重试";
                trace("FAIL key " + exception.getMessage());
            } catch (IOException exception) {
                // 客户端的异常文案已区分超时/证书/格式校验/截断，直接透出比通用文案更有用。
                String detail = exception.getMessage();
                failure = detail == null || detail.isEmpty()
                        ? "模型请求未完成或响应校验失败；请检查网络、配置或用虚构对话测试，未自动重试"
                        : detail;
                trace("FAIL io " + failure);
            } catch (RuntimeException exception) {
                failure = "模型分析不可用，请检查配置；未自动重试";
                trace("FAIL runtime " + exception.getClass().getSimpleName() + " " + exception.getMessage());
            }
            AnalysisResult finalResult = result;
            String finalFailure = failure;
            handler.post(() -> {
                // 只拒绝被更新请求取代的结果；会话是否仍有效由 AssistantState.analyzed 判定。
                if (sequence != requestSequence) {
                    trace("DROP result=superseded");
                    return;
                }
                provider = null;
                task = null;
                requested = null;
                if (finalResult != null) {
                    boolean shown = AssistantState.analyzed(bound, finalResult);
                    trace("RESULT ok replies=" + finalResult.replies.size() + " discussions="
                            + (finalResult.discussion == null ? 0 : 1) + " shown=" + shown);
                    // 结果落地必须主动通知覆盖层重绘：覆盖层由无障碍事件/范围查询驱动，
                    // 安静聊天里没有后续事件，不通知则回复建议面板要等用户下一次交互才出现。
                    ChatGateService.notifyOverlayAnalysisChanged();
                } else {
                    trace("RESULT fail " + finalFailure);
                    AssistantState.clearAnalysis(finalFailure.isEmpty() ? "请求已取消" : finalFailure);
                    ChatGateService.notifyOverlayAnalysisChanged();
                }
            });
        });
    }

    /** 优先返回当前仍能通过门控的最新快照；旧快照与最新快照都不通过时返回 null。 */
    private AssistantState.Snapshot resolve(AssistantState.Snapshot snapshot, long revision, long requestSequence) {
        AssistantState.Snapshot live = AssistantState.snapshot();
        if (permitted(live, revision, requestSequence)) {
            return live;
        }
        return permitted(snapshot, revision, requestSequence) ? snapshot : null;
    }

    private void cancel() {
        cancelQuietWake();
        sequence++;
        if (pending != null) {
            handler.removeCallbacks(pending);
            pending = null;
        }
        if (provider != null) {
            provider.cancel();
            provider = null;
        }
        if (task != null) {
            task.cancel(true);
            task = null;
        }
        requested = null;
    }

    void close() {
        closed = true;
        timingObserver = null;
        demoMode = false;
        cancel();
        worker.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        if (active == this) {
            active = null;
        }
    }
}
