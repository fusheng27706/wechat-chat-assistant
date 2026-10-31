package com.codextemp.wechatcaptureprobe;

import android.graphics.Color;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class ConversationContextPanel {
    final LinearLayout view;
    private final Context service;
    private final ConfirmedMemoryStore store;
    private final LinearLayout controls;
    private final LinearLayout choices;
    private final TextView summary;
    private final TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Future<?> loading;
    private AssistantState.Snapshot target;
    private long sequence;
    private boolean released;

    ConversationContextPanel(ChatGateService service) {
        this((Context) service);
    }

    ConversationContextPanel(Context service) {
        this.service = service;
        store = new ConfirmedMemoryStore(service);
        view = new LinearLayout(service);
        view.setOrientation(LinearLayout.VERTICAL);
        summary = text(view, "未关联本轮背景");
        controls = new LinearLayout(service);
        controls.setOrientation(LinearLayout.VERTICAL);
        status = text(controls, "记忆不会按标题自动匹配，首次未设置也可做通用分析。");
        button(view, "设置本轮私聊/群聊与已确认背景…", () -> {
            if (!current(AssistantState.snapshot())) {
                controls.setVisibility(View.VISIBLE);
                status.setText("请收起卡片，等待当前聊天确认后再设置背景");
                return;
            }
            boolean show = controls.getVisibility() != View.VISIBLE;
            if (!show) {
                clearChoices();
            }
            controls.setVisibility(show ? View.VISIBLE : View.GONE);
        });
        view.addView(controls);
        text(controls, "只影响当前会话版本，切换、离开或暂停会解除。模式/背景改变会清空旧分析；若云端已开启，点击确认后会重新分析并可能收费。不要把 OCR 标题当作账号身份，请人工核对对象。");
        button(controls, "本轮按私聊分析（不关联记忆）", () -> setMode(ConversationContext.Mode.PRIVATE));
        button(controls, "本轮按群聊分析（不关联记忆）", () -> setMode(ConversationContext.Mode.GROUP));
        button(controls, "本轮恢复未确认类型（通用分析）", () -> setMode(ConversationContext.Mode.UNKNOWN));
        button(controls, "选择已确认的本机记忆…", this::loadChoices);
        button(controls, "解除本轮记忆关联（不删除档案）", () -> setMode(AssistantState.context.mode));
        choices = new LinearLayout(service);
        choices.setOrientation(LinearLayout.VERTICAL);
        controls.addView(choices);
        controls.setVisibility(View.GONE);
    }

    private TextView text(LinearLayout parent, String value) {
        TextView text = new TextView(service);
        text.setText(value);
        text.setTextColor(Color.WHITE);
        text.setTextSize(14);
        parent.addView(text);
        return text;
    }

    private void button(LinearLayout parent, String value, Runnable action) {
        Button button = new Button(service);
        button.setText(value);
        button.setOnClickListener(control -> action.run());
        parent.addView(button);
    }

    private boolean current(AssistantState.Snapshot snapshot) {
        ChatGateService.Snapshot gate = ChatGateService.snapshot();
        return !released && AssistantState.expanded && AssistantCaptureService.running() && gate.valid()
                && gate.generation == snapshot.generation && AssistantState.matches(snapshot);
    }

    private void setMode(ConversationContext.Mode mode) {
        ChatGateService.verify();
        AssistantState.Snapshot snapshot = AssistantState.snapshot();
        if (!current(snapshot) || !AssistantState.setContext(snapshot, mode, null, -1, false)) {
            status.setText("当前会话尚未确认，没有改变模式或关联");
            return;
        }
        clearChoices();
        status.setText("已确认本轮模式：" + mode.label + "；没有关联或发送记忆。群成员仍可能无法识别。");
        AnalysisCoordinator.retryCurrent();
        refresh();
    }

    private void loadChoices() {
        AssistantState.Snapshot snapshot = AssistantState.snapshot();
        if (!current(snapshot)) {
            status.setText("请先收起卡片，等待当前聊天确认");
            return;
        }
        clearChoices();
        target = snapshot;
        long request = sequence;
        status.setText("正在解密本机档案；不读取微信、不调用模型…");
        loading = worker.submit(() -> {
            ConfirmedMemoryStore.Snapshot loaded = null;
            try {
                loaded = store.load();
            } catch (GeneralSecurityException | IOException exception) {
                loaded = null;
            }
            ConfirmedMemoryStore.Snapshot result = loaded;
            handler.post(() -> {
                if (request != sequence || !current(snapshot)) {
                    return;
                }
                loading = null;
                if (result == null) {
                    status.setText("无法解密记忆，未关联或上传；请在助手页的记忆管理中检查");
                    return;
                }
                if (result.revision != store.revision()) {
                    status.setText("档案已变化，请重新选择；没有关联旧版本");
                    return;
                }
                int count = 0;
                for (MemoryProfile profile : result.profiles) {
                    if (snapshot.context.mode != ConversationContext.Mode.UNKNOWN && snapshot.context.mode != profile.kind) {
                        continue;
                    }
                    count++;
                    button(choices, "本机档案：" + profile.kind.label + " · " + profile.label + " · " + profile.id.substring(0, 6),
                            () -> review(snapshot, profile, result.revision));
                }
                status.setText(count == 0 ? "没有匹配类型的档案；可在助手页的记忆管理中新建，或保持通用分析。"
                        : "请选择正确对象的档案；同名档案以类型和本机标识区分，不会自动匹配微信标题。");
            });
        });
    }

    private void review(AssistantState.Snapshot snapshot, MemoryProfile profile, long revision) {
        if (!current(snapshot) || revision != store.revision()) {
            clearChoices();
            status.setText("会话或档案已变化，请重新确认");
            return;
        }
        choices.removeAllViews();
        text(choices, "当前标题（OCR 推断）：" + snapshot.title + "\n\n请人工确认这份档案属于当前实际对象：\n" + profile.review()
                + "\n\n不按标题自动识别账号，切换后需重新关联。仅本机关联时，AI 无法参考这些私有背景。");
        CheckBox share = new CheckBox(service);
        share.setTextColor(Color.WHITE);
        share.setText("另行允许当前文字快照的云端分析携带以上背景/偏好/事项（默认不允许；文字变化即撤回；本机别名/标识不发送）");
        share.setSaveEnabled(false);
        choices.addView(share);
        button(choices, "我已核对对象：本轮使用此档案并分析", () -> {
            ChatGateService.verify();
            if (!current(snapshot) || revision != store.revision()
                    || !AssistantState.setContext(snapshot, profile.kind, profile, revision, share.isChecked())) {
                clearChoices();
                status.setText("会话、类型或档案已变化，没有关联或上传旧背景");
                return;
            }
            boolean allowed = share.isChecked();
            clearChoices();
            status.setText(allowed ? "已关联本轮背景，并仅允许随当前文字快照分析发送；文字变化会回到仅本机。云端总开关仍需开启，已发送数据无法撤回。"
                    : "已关联，仅本机展示；没有授权 AI 使用这些记忆字段。可继续只分析当前文字。");
            AnalysisCoordinator.retryCurrent();
            refresh();
        });
        button(choices, "取消本次关联", () -> {
            clearChoices();
            status.setText("已取消，没有关联或上传所选记忆");
        });
    }

    void refresh() {
        AssistantState.Snapshot snapshot = AssistantState.snapshot();
        ConversationContext context = snapshot.context;
        String value = AssistantState.matches(snapshot) ? "本轮模式：" + context.mode.label
                + (context.memory == null ? " · 无记忆" : "\n本机档案：" + context.memory.label
                + (context.shareMemory ? "（允许当前快照云端携带）" : "（仅本机，不发给 AI；需再次允许当前快照）"))
                : "等待当前会话确认；不使用跨会话背景";
        if (!value.contentEquals(summary.getText())) {
            summary.setText(value);
        }
        if (!AssistantState.expanded || target != null && !current(target)) {
            clearChoices();
            controls.setVisibility(View.GONE);
        }
    }

    private void clearChoices() {
        // 已经撤权且无异步任务时，不反复触发布局；有目标或晚到任务仍立即递增序号。
        if (target == null && loading == null && choices.getChildCount() == 0) return;
        sequence++;
        target = null;
        if (loading != null) {
            loading.cancel(true);
            loading = null;
        }
        choices.removeAllViews();
    }

    void close() {
        clearChoices();
        handler.removeCallbacksAndMessages(null);
        controls.setVisibility(View.GONE);
        summary.setText("未关联本轮背景");
        status.setText("");
    }

    void release() {
        released = true;
        close();
        worker.shutdownNow();
    }
}
