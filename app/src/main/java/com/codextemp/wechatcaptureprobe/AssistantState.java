package com.codextemp.wechatcaptureprobe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class AssistantState {
    static volatile String status = "助手未开启";
    static volatile boolean paused;
    static volatile boolean expanded;
    private static volatile boolean expansionRequested;

    /** 主线程显式操作的运行期显示偏好；不进入快照、持久化或来源/草稿许可。 */
    static synchronized void requestExpansion(boolean requested) {
        expansionRequested = requested;
        expanded = requested;
    }

    static boolean expansionRequested() { return expansionRequested; }
    static volatile String title = "";
    static volatile String token = "";
    static volatile List<ChatMessage> messages = Collections.emptyList();
    static volatile CaptureCoverage coverage = CaptureCoverage.UNOBSTRUCTED;
    static volatile long generation = -1;
    static volatile long frames;
    static volatile long ocrMs;
    /** 只作数值诊断，不参与来源许可；只与一次成功发布一起替换。 */
    static volatile CaptureTiming captureTiming;
    /** 分析输入快照的发布计数；同内容复用时不改写，-1表示无分析。 */
    static volatile long analysisFrame = -1;

    static final class CaptureTiming {
        // 均为elapsedRealtime毫秒；changedAt指最后检测到变化，非触摸结束时间。
        final long changedAt, startedAt, completedAt;
        CaptureTiming(long changedAt, long startedAt, long completedAt) {
            this.changedAt = changedAt;
            this.startedAt = startedAt;
            this.completedAt = completedAt;
        }
    }
    static volatile int newCount;
    static volatile long policyVersion;
    static volatile String incrementStatus = "";
    static volatile boolean excluded;
    static volatile AnalysisResult analysis;
    static volatile String analysisStatus = "云端分析默认关闭";
    static volatile long analyzedAt;
    static volatile AnalysisPresentation.Source analysisSource;
    private static MessageTracker.Kind incrementKind = MessageTracker.Kind.BASELINE;
    private static boolean automaticAnalysisEligible;
    private static String messageSignature = "";
    /** 只含会话与消息内容的签名：用于判断窗口是否稳定，不受浮窗遮挡面积影响。 */
    private static String contentSignature = "";
    /** 连续多少次采集的底部锚点完全一致；锚点稳定到 2 帧即可自动分析。 */
    private static long stableFrames;
    static volatile ConversationContext context = ConversationContext.empty();
    private static long contextVersion;

    static final class Snapshot {
        final long frame;
        final CaptureTiming timing;
        final long generation;
        final long policy;
        final String title;
        final String token;
        final List<ChatMessage> messages;
        final MessageTracker.Kind kind;
        final boolean automaticAnalysisEligible;
        final String signature;
        final String messageSignature;
        final String contentSignature;
        final long stableFrames;
        final long contextVersion;
        final ConversationContext context;
        final CaptureCoverage coverage;

        Snapshot() {
            frame = frames;
            timing = captureTiming;
            generation = AssistantState.generation;
            policy = policyVersion;
            title = AssistantState.title;
            token = AssistantState.token;
            messages = AssistantState.messages;
            kind = incrementKind;
            automaticAnalysisEligible = AssistantState.automaticAnalysisEligible;
            messageSignature = AssistantState.messageSignature;
            contentSignature = AssistantState.contentSignature;
            stableFrames = AssistantState.stableFrames;
            contextVersion = AssistantState.contextVersion;
            context = AssistantState.context;
            coverage = AssistantState.coverage;
            signature = messageSignature + ":context=" + contextVersion;
        }
    }

    static synchronized Snapshot snapshot() {
        return new Snapshot();
    }

    static synchronized boolean matches(Snapshot snapshot) {
        return !paused && !excluded && !token.isEmpty() && !messages.isEmpty()
                && generation == snapshot.generation && policyVersion == snapshot.policy
                && token.equals(snapshot.token) && messageSignature.equals(snapshot.messageSignature)
                && contextVersion == snapshot.contextVersion;
    }

    /**
     * 会话级匹配：只判断「还是同一个会话、还在辅助范围、配置与背景未变」，不比较可见集合。
     * 在途模型请求期间浮窗高度、滚动、抗锯齿都会改变可见集合与视觉指纹，若仍要求 messageSignature
     * 逐字相等，正常请求也会被逐帧画面变化反复取消（SCOPE_FAIL matches）。会话身份只由
     * generation/token/policyVersion/contextVersion 决定，这些只在场景切换、标题确认或排除规则变化时变。
     */
    static synchronized boolean matchesSession(Snapshot snapshot) {
        boolean matched = !paused && !excluded && !token.isEmpty()
                && generation == snapshot.generation && policyVersion == snapshot.policy
                && token.equals(snapshot.token) && contextVersion == snapshot.contextVersion;
        // 诊断日志（不打 token 内容）：真机上 SCOPE_FAIL matches 长期反复出现，需定位是哪个
        // 会话字段与采集侧快照不一致。格式 state字段/快照字段。
        if (!matched) {
            android.util.Log.i("ChatAssistant", "MATCH_FAIL paused=" + paused + " excluded=" + excluded
                    + " stateTokenEmpty=" + token.isEmpty() + " snapTokenEmpty=" + snapshot.token.isEmpty()
                    + " gen=" + generation + "/" + snapshot.generation
                    + " policy=" + policyVersion + "/" + snapshot.policy
                    + " tokenEq=" + token.equals(snapshot.token)
                    + " ctx=" + contextVersion + "/" + snapshot.contextVersion);
        }
        return matched;
    }

    static synchronized boolean setContext(Snapshot expected, ConversationContext.Mode mode, MemoryProfile memory,
                                           long memoryRevision, boolean shareMemory) {
        if (!matches(expected) || mode == null || memory != null && (memory.kind != mode || memoryRevision < 0
                || context.mode != ConversationContext.Mode.UNKNOWN && context.mode != mode)) {
            return false;
        }
        context = new ConversationContext(token, mode, memory, memoryRevision, shareMemory);
        contextVersion++;
        clearAnalysis("本轮模式或已确认背景变化，旧分析已清空");
        return true;
    }

    static synchronized void clearContext(String reason) {
        if (context.mode != ConversationContext.Mode.UNKNOWN || context.memory != null) {
            context = ConversationContext.empty();
            contextVersion++;
            clearAnalysis(reason);
        }
    }

    static synchronized void memoryChanged(long revision) {
        if (context.memory != null && context.memoryRevision != revision) {
            context = new ConversationContext(context.sessionToken, context.mode, null, -1, false);
            contextVersion++;
            clearAnalysis("记忆已编辑或删除，本轮关联已解除；重新使用需再次确认");
        }
    }

    static synchronized void invalidateScope(String reason) {
        android.util.Log.i("ChatAssistant", "INVALIDATE_SCOPE reason=" + reason);
        clearContext(reason);
        invalidate(reason);
    }

    /**
     * 结果落盘只要求"同一会话、未被暂停或排除"：模型请求期间浮窗高度、滚动或新消息
     * 都会改变可见集合，若仍要求逐字相等，正常结果也会被静默丢弃。复制与填入入口
     * 另有实时校验，过期结果会在那里被拒绝。
     */
    static synchronized boolean analyzed(Snapshot snapshot, AnalysisResult result) {
        if (paused || excluded || token.isEmpty() || !token.equals(snapshot.token)) {
            return false;
        }
        analyzedAt = android.os.SystemClock.elapsedRealtime();
        analysisFrame = snapshot.frame;
        analysisSource = new AnalysisPresentation.Source(snapshot.title, snapshot.messages.size(), System.currentTimeMillis(), analyzedAt, snapshot.coverage);
        analysis = result;
        analysisStatus = result.simulated ? "本地模拟 · 固定示例，未调用大模型"
                : "AI 推测 · 仅基于当前可见快照，不是对方真实意图的证明";
        return true;
    }

    static synchronized void clearAnalysis(String reason) {
        android.util.Log.i("ChatAssistant", "CLEAR_ANALYSIS reason=" + reason);
        analysis = null;
        analyzedAt = 0;
        analysisFrame = -1;
        analysisSource = null;
        analysisStatus = reason;
    }

    static synchronized void leave(long currentGeneration) {
        android.util.Log.i("ChatAssistant", "LEAVE gen=" + currentGeneration);
        if (generation != currentGeneration) {
            clearContext("已离开原会话，本轮背景已解除");
            generation = currentGeneration;
            title = "";
            token = "";
            messages = Collections.emptyList();
            captureTiming = null;
            coverage = CaptureCoverage.UNOBSTRUCTED;
            newCount = 0;
            incrementStatus = "";
            automaticAnalysisEligible = false;
            stableFrames = 0;
            excluded = false;
            clearAnalysis("会话变化，旧分析已清空");
            policyVersion++;
            if (AssistantCaptureService.running()) {
                status = "已暂停：不在微信聊天或设备已锁定";
            }
        }
    }

    static synchronized void publish(long currentGeneration, String currentTitle,
                                     String currentToken, List<ChatMessage> currentMessages,
                                     long elapsed, MessageTracker.Update increment) {
        publish(currentGeneration, currentTitle, currentToken, currentMessages, elapsed, increment, CaptureCoverage.UNOBSTRUCTED);
    }

    static synchronized void publish(long currentGeneration, String currentTitle,
                                     String currentToken, List<ChatMessage> currentMessages,
                                     long elapsed, MessageTracker.Update increment, CaptureCoverage currentCoverage) {
        publish(currentGeneration, currentTitle, currentToken, currentMessages, elapsed, increment, currentCoverage, null);
    }

    /** timing只描述此次已完成的采集；旧夹具未提供时保持未知，不拼接活动任务的时间。 */
    static synchronized void publish(long currentGeneration, String currentTitle,
                                     String currentToken, List<ChatMessage> currentMessages,
                                     long elapsed, MessageTracker.Update increment, CaptureCoverage currentCoverage,
                                     CaptureTiming timing) {
        if (currentCoverage == null) {
            throw new IllegalArgumentException("Capture coverage required");
        }
        if (!context.sessionToken.isEmpty() && !context.sessionToken.equals(currentToken)) {
            clearContext("会话版本变化，本轮背景已解除");
        }
        boolean sameSource = generation == currentGeneration && token.equals(currentToken);
        generation = currentGeneration;
        title = currentTitle;
        token = currentToken;
        messages = Collections.unmodifiableList(new ArrayList<>(currentMessages));
        coverage = currentCoverage;
        StringBuilder keys = new StringBuilder(currentToken).append(":coverage=").append(currentCoverage.signature());
        for (ChatMessage message : currentMessages) {
            keys.append(':').append(message.key()).append(':').append(message.warning);
        }
        String signature = keys.toString();
        // 锚点只取窗口最底部那条消息的正文，且不带视觉指纹：采集编号每帧都会变、
        // 指纹会随抗锯齿抖动，两者都不该参与"是不是同一条消息"的判断。
        String contentNow = currentMessages.isEmpty() ? currentToken
                : currentToken + ':' + currentMessages.get(currentMessages.size() - 1).text.replaceAll("\\s", "");
        boolean contentSame = contentSignature.equals(contentNow) && !currentMessages.isEmpty();
        contentSignature = contentNow;
        stableFrames = contentSame ? stableFrames + 1 : 1;
        automaticAnalysisEligible = increment.kind == MessageTracker.Kind.KNOWN && sameSource
                && signature.equals(messageSignature) && automaticAnalysisEligible
                || increment.kind == MessageTracker.Kind.BASELINE || increment.kind == MessageTracker.Kind.APPEND;
        if (!signature.equals(messageSignature)) {
            // 自有窗口动画/展开过渡期（<600ms）里消息集合的增减来自面板遮蔽自身，不是聊天
            // 内容变化；此时清空结果会形成「面板出现→清空→面板消失→重新分析」自指循环。
            // 只豁免清空动作，签名照常更新：复制/填入入口的 matches 校验仍然生效。
            boolean selfWindowTransition = android.os.SystemClock.elapsedRealtime() - ChatGateService.selfWindowsChangedAt() < 600;
            if (context.shareMemory) {
                context = new ConversationContext(context.sessionToken, context.mode, context.memory, context.memoryRevision, false);
                contextVersion++;
            }
            if (!selfWindowTransition) {
                if (increment.kind == MessageTracker.Kind.GAP || increment.kind == MessageTracker.Kind.AMBIGUOUS) {
                    clearContext("窗口无法可靠衔接，已解除本轮背景");
                }
                clearAnalysis("当前文字变化，旧分析已清空");
            }
        }
        messageSignature = signature;
        ocrMs = elapsed;
        captureTiming = timing;
        newCount = increment.additions;
        incrementStatus = increment.description();
        incrementKind = increment.kind;
        excluded = false;
        frames++;
        status = "已读 " + currentMessages.size() + " 条可见消息 · " + elapsed + " ms\n" + incrementStatus;
    }

    static synchronized void clear() {
        clearContext("助手已停止，本轮背景已解除");
        title = "";
        token = "";
        messages = Collections.emptyList();
        coverage = CaptureCoverage.UNOBSTRUCTED;
        newCount = 0;
        frames = 0;
        captureTiming = null;
        requestExpansion(false);
        excluded = false;
        clearAnalysis("助手已停止，分析已清空");
        incrementStatus = "";
        automaticAnalysisEligible = false;
        stableFrames = 0;
        contentSignature = "";
        policyVersion++;
    }

    static synchronized void invalidate(String reason) {
        android.util.Log.i("ChatAssistant", "INVALIDATE reason=" + reason);
        captureTiming = null;
        title = "";
        token = "";
        messages = Collections.emptyList();
        coverage = CaptureCoverage.UNOBSTRUCTED;
        newCount = 0;
        incrementStatus = "";
        automaticAnalysisEligible = false;
        stableFrames = 0;
        contentSignature = "";
        excluded = false;
        clearAnalysis("等待当前会话的稳定文字");
        policyVersion++;
        status = reason;
    }

    static synchronized void block(long currentGeneration, String currentTitle, String currentToken) {
        invalidateScope("当前标题已排除；只检查必要标题，不识别气泡正文、不分析、不上传聊天");
        generation = currentGeneration;
        title = currentTitle;
        token = currentToken;
        excluded = true;
        analysisStatus = "已排除，不进行云端分析";
    }

    private AssistantState() {
    }
}
