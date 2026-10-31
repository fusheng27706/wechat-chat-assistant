package com.codextemp.wechatcaptureprobe;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.os.PersistableBundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.function.BooleanSupplier;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

final class AssistOverlay {
    private final Context service;
    private final DraftOperations draftOperations;
    private final WindowManager manager;
    private final LinearLayout panel;
    private final TextView ball;
    private final LinearLayout quick;
    private final TextView quickText;
    private final Button quickCopy;
    private final Button quickNext;
    private AnalysisResult quickAnalysis;
    private AssistantState.Snapshot quickTarget;
    private int quickIndex;
    private final SuggestionVisibility suggestionVisibility = new SuggestionVisibility();
    private final TextView details;
    private final Button demoToggle;
    private final Button retry;
    private final TextView demoNotice;
    private final TextView explanation;
    private final Button explanationToggle;
    private final LinearLayout management;
    private final Button managementToggle;
    private final ScrollView scroll;
    private final LinearLayout actions;
    private final LinearLayout replyChoices;
    private final LinearLayout copyChoices;
    private final LinearLayout replyActions;
    private final TextView replyPreview;
    private final TextView operationStatus;
    private final TextView sourceDetails;
    private final Button sourceToggle;
    private final LinearLayout draftControls;
    private final TextView draftPreview;
    private final Button appendDraft;
    private final Button replaceDraft;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DisplayMetrics screen = new DisplayMetrics();
    private final Button exclude;
    private final Button pause;
    private final ConversationRulesStore rules;
    private final ConversationContextPanel contextPanel;
    private final WindowManager.LayoutParams params;
    // 消息级就地叠加标注条：半透明小条，紧贴最新一条对方回复气泡显示其隐含需求/目的与准确率。
    private final LinearLayout insight;
    private final TextView insightTitle;
    private final TextView insightText;
    private final WindowManager.LayoutParams insightParams;
    private boolean insightAttached;
    private String lastInsightSkip = "";
    // 回复建议面板：第三个常驻窗口，悬浮于输入栏上方的毛玻璃面板（定稿 01 屏）。
    // 与标注条同策略：不参与遮挡上报（captureOcclusion），仅以展开态包围盒做指纹自遮蔽，
    // 否则贴输入栏的窗口会触发 ChatViewport.withOverlay 的底部 56dp 拒绝 → 范围失效自指循环。
    private final LinearLayout suggestPanel;
    private final LinearLayout suggestRows;
    private final TextView suggestChevron;
    private final TextView suggestFooter;
    private final TextView suggestBadge;
    private final WindowManager.LayoutParams suggestParams;
    private boolean suggestAttached;
    /** 建议面板默认收起为标题行：展开态会覆盖消息区并暂停帧处理，只在用户主动点开时进入；
     *  默认展开会让每个结果都触发一次「展开→暂停→范围抖动」，浪费分析并放大不稳定性。 */
    private boolean suggestCollapsed = true;
    private AnalysisResult suggestAnalysis;
    private AssistantState.Snapshot suggestTarget;
    private AnalysisResult renderedSuggest;
    private int copiedIndex = -1;
    private String lastSuggestSkip = "";
    // 建议面板上一次成功应用的布局签名（宽x高@x,y）：外层布局方法在每个无障碍事件上
    // 都会执行 updateSuggestions，几何未变化时跳过 WindowManager IPC 与日志，避免每帧空转刷屏。
    // hideSuggestions 时重置为空，保证隐藏后再次显示时一定重新应用几何。
    private String lastSuggestLayoutKey = "";
    // 建议面板当前展开态包围盒：指纹自遮蔽用；无可展示建议时为 null。
    private volatile ChatViewport.Bounds suggestScreenBounds;
    /** 建议面板当前实际可见包围盒（隐藏即 null）；与 suggestScreenBounds（固定展开态，供指纹）
     *  不同，采集端 OCR 遮蔽必须用真实几何，否则收起态会把上方无窗区域也误遮蔽、漏掉真实气泡。 */
    private volatile ChatViewport.Bounds suggestVisibleBounds;
    private final java.util.List<LinearLayout> suggestRowBodies = new java.util.ArrayList<>();
    private final java.util.List<TextView> suggestFeedbacks = new java.util.ArrayList<>();
    private final Runnable clearCopiedFeedback = this::resetCopiedFeedback;
    // 标注条当前全屏包围盒：仅供采集端在指纹计算中把这段区域当作自身窗口遮蔽（返回固定灰），
    // 避免「结果落地 → 标注条出现 → 投影帧里多出一块 FLAG_SECURE 黑区 → bodyHash 变化 → 清空结果」的自指循环。
    private volatile ChatViewport.Bounds insightScreenBounds;
    /** 悬浮球当前是否处于图标模式（收起态机器人图标）；与 refresh 中 iconBall 判定联动切换背景。 */
    private boolean ballIconMode;
    // 窗口是否已真正 addView 到 WindowManager（只在 destroy 移除，避免运行时反复增删触发无障碍事件自指循环）。
    private boolean windowsAdded;
    private final SharedPreferences positionStore;
    private volatile boolean attached;
    private boolean renderedExpanded;
    private boolean layoutPending;
    private android.view.ViewTreeObserver drawObserver;
    private final android.view.ViewTreeObserver.OnPreDrawListener captureDraw = () -> {
        layoutPending = false;
        captureOcclusion();
        return true;
    };
    private volatile OverlayOcclusion captureOcclusion;
    private String lastDetails = "";
    private float touchVertical;
    private float touchHorizontal;
    private int startOffset;
    private int startHorizontal;
    private int startScreenTop;
    private long lastDragUpdate;
    private boolean dragged;
    private boolean dragging;
    private boolean dockRight;
    private float savedVerticalFraction;
    private ScreenGeometry viewport;
    private final Runnable persistPosition = this::savePosition;
    private AnalysisResult renderedAnalysis;
    private AnalysisResult seenAnalysis;
    private java.util.List<ChatMessage> renderedSourceMessages;
    private AssistantState.Snapshot exclusionTarget;
    private AssistantState.Snapshot replyTarget;
    private AnalysisResult selectedAnalysis;
    private AnalysisResult.Reply selectedReply;
    private DraftPlan draftPlan;
    private volatile long operationRevision;
    private DraftWorker.Task<?> draftTask;
    private boolean writeMayHaveOccurred;
    private final IntConsumer windowRegistered;

    AssistOverlay(ChatGateService service) {
        this(service, new DraftOperations());
    }

    AssistOverlay(Context service, DraftOperations draftOperations) {
        this.service = service;
        this.draftOperations = draftOperations;
        windowRegistered = service instanceof ChatGateService ? ((ChatGateService) service).ownedWindowRegistration() : identifier -> { };
        positionStore = service.getSharedPreferences("overlay_layout", 0);
        dockRight = positionStore.getBoolean("dock_right", true);
        savedVerticalFraction = positionStore.getFloat("vertical_fraction", 0);
        // 钳制到屏幕可见范围：历史上 fitPosition 病态值（如 y=-451/2400≈-0.19）曾被持久化，
        // 恢复后球每次启动都停在屏幕外、建议面板随之跟随出屏（2026-10-30 23:50）。
        if (!Float.isFinite(savedVerticalFraction) || savedVerticalFraction < 0.03f || savedVerticalFraction > 0.9f) {
            savedVerticalFraction = 0.25f;
        }
        rules = new ConversationRulesStore(service);
        operationStatus = panelText();
        manager = service.getSystemService(WindowManager.class);
        panel = new LinearLayout(service);
        panel.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                drawObserver = view.getViewTreeObserver();
                drawObserver.addOnPreDrawListener(captureDraw);
                registerAttachedWindow();
                panel.post(AssistOverlay.this::registerAttachedWindow);
            }
            @Override public void onViewDetachedFromWindow(View view) {
                if (drawObserver != null && drawObserver.isAlive()) drawObserver.removeOnPreDrawListener(captureDraw);
                drawObserver = null;
            }
        });
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(8), dp(8), dp(8));
        // 面板改为毛玻璃白底（定稿 01 屏）：透出底下聊天内容，墨色文字保持可读；
        // 所有正文由 panelText 提供深色文字。
        GradientDrawable panelGlass = new GradientDrawable();
        panelGlass.setColor(0xEBFFFFFF);
        panelGlass.setCornerRadius(dp(20));
        panelGlass.setStroke(dp(1), 0xFFEEF1F0);
        panel.setBackground(panelGlass);
        ball = new TextView(service);
        // 悬浮球：白色圆形 + 品牌绿（定稿 01 屏）。收起态用矢量机器人头图标替代「助手」文字；
        // 展开/暂停/排除态仍回退为文字标签以传达状态（见 refresh 的 iconBall 分支）。
        ball.setTextColor(0xFF0E7A55);
        ball.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        ball.setBackground(roundedStroke(0xFFFFFFFF, 28, 0xFFDCE7E2, 1));
        ball.setTextSize(14);
        ball.setGravity(Gravity.CENTER);
        panel.addView(ball, new LinearLayout.LayoutParams(-1, dp(40)));
        quick = new LinearLayout(service);
        quick.setOrientation(LinearLayout.VERTICAL);
        quickText = panelText();
        quickText.setTextSize(15);
        quickText.setMaxLines(3);
        quickText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        quick.addView(quickText, new LinearLayout.LayoutParams(-1, dp(68)));
        LinearLayout quickActions = new LinearLayout(service);
        quickCopy = new Button(service);
        quickCopy.setText(R.string.quick_copy);
        quickCopy.setTextSize(12);
        quickCopy.setOnClickListener(view -> copyQuickReply());
        quickNext = new Button(service);
        quickNext.setText(R.string.quick_next);
        quickNext.setTextSize(12);
        quickNext.setOnClickListener(view -> {
            if (quickAnalysis != null && quickAnalysis == AssistantState.analysis
                    && quickTarget != null && AssistantState.matches(quickTarget)) {
                quickIndex = (quickIndex + 1) % quickAnalysis.replies.size();
                renderQuick();
            }
        });
        Button more = new Button(service);
        more.setText(R.string.quick_more);
        more.setTextSize(12);
        more.setOnClickListener(view -> {
            AssistantState.requestExpansion(true);
            refresh(ChatGateService.snapshot().valid() && AssistantCaptureService.running());
        });
        quickActions.addView(quickCopy, new LinearLayout.LayoutParams(0, -1, 1.2f));
        quickActions.addView(quickNext, new LinearLayout.LayoutParams(0, -1, 1));
        quickActions.addView(more, new LinearLayout.LayoutParams(0, -1, 0.8f));
        quick.addView(quickActions, new LinearLayout.LayoutParams(-1, dp(48)));
        panel.addView(quick, new LinearLayout.LayoutParams(-1, dp(116)));
        scroll = new ScrollView(service);
        details = new TextView(service);
        details.setTextColor(0xFF14261F);
        details.setTextSize(15);
        details.setPadding(dp(4), dp(8), dp(4), dp(8));
        LinearLayout content = new LinearLayout(service);
        content.setOrientation(LinearLayout.VERTICAL);
        demoToggle = new Button(service);
        demoToggle.setText(R.string.local_demo_suggestions);
        demoToggle.setOnClickListener(view -> {
            AnalysisCoordinator.toggleDemo();
            refresh(ChatGateService.snapshot().valid() && AssistantCaptureService.running());
            scroll.scrollTo(0, 0);
        });
        demoNotice = panelText();
        demoNotice.setText("固定示例 · 与聊天内容无关 · 未调用模型");
        demoNotice.setVisibility(View.GONE);
        content.addView(demoNotice);
        content.addView(details);
        explanationToggle = new Button(service);
        explanationToggle.setText("查看分析详情");
        explanationToggle.setVisibility(android.view.View.GONE);
        explanation = panelText();
        explanation.setVisibility(android.view.View.GONE);
        explanationToggle.setOnClickListener(view -> {
            boolean opening = explanation.getVisibility() != android.view.View.VISIBLE;
            explanation.setVisibility(opening ? android.view.View.VISIBLE : android.view.View.GONE);
            explanationToggle.setText(opening ? "收起判断详情" : "查看分析详情");
        });
        content.addView(explanationToggle);
        content.addView(explanation);
        management = new LinearLayout(service);
        management.setOrientation(LinearLayout.VERTICAL);
        management.setVisibility(android.view.View.GONE);
        management.addView(demoToggle);
        managementToggle = new Button(service);
        managementToggle.setText("更多操作");
        managementToggle.setOnClickListener(view -> {
            boolean opening = management.getVisibility() != android.view.View.VISIBLE;
            management.setVisibility(opening ? android.view.View.VISIBLE : android.view.View.GONE);
            managementToggle.setText(opening ? "收起更多操作" : "更多操作");
        });
        retry = new Button(service);
        retry.setText("重新分析（可能收费）");
        retry.setOnClickListener(view -> AnalysisCoordinator.retryCurrent());
        management.addView(retry);
        exclude = new Button(service);
        exclude.setText("排除此 OCR 标题…");
        exclude.setOnClickListener(view -> excludeCurrent());
        management.addView(exclude);
        Button capability = new Button(service);
        capability.setText("检查当前输入框能力（只读，不修改）");
        capability.setOnClickListener(view -> checkCapability());
        management.addView(capability);
        contextPanel = new ConversationContextPanel(service);
        management.addView(contextPanel.view);
        replyChoices = new LinearLayout(service);
        replyChoices.setOrientation(LinearLayout.VERTICAL);
        TextView draftHint = panelText();
        draftHint.setText("需要填入草稿时，先选择回复，再检查输入框能力");
        management.addView(draftHint);
        management.addView(replyChoices);
        copyChoices = new LinearLayout(service);
        copyChoices.setOrientation(LinearLayout.VERTICAL);
        content.addView(copyChoices, 1);
        replyPreview = panelText();
        content.addView(replyPreview, 2);
        replyActions = new LinearLayout(service);
        replyActions.setOrientation(LinearLayout.VERTICAL);
        Button prepare = new Button(service);
        prepare.setText("填入草稿…");
        prepare.setOnClickListener(view -> prepareDraft());
        replyActions.addView(prepare);
        Button copy = new Button(service);
        copy.setText("确认复制（替换剪贴板）");
        copy.setOnClickListener(view -> copyReply());
        replyActions.addView(copy, 0);
        Button cancel = new Button(service);
        cancel.setText("取消");
        cancel.setOnClickListener(view -> {
            boolean attempted = writeMayHaveOccurred;
            clearSelection();
            operationStatus.setText(attempted ? "已取消后续操作；已请求的草稿修改不能保证撤回，请检查微信。没有发送。"
                    : "已取消选择，没有修改草稿或发送");
        });
        replyActions.addView(cancel);
        content.addView(replyActions, 3);
        draftControls = new LinearLayout(service);
        draftControls.setOrientation(LinearLayout.VERTICAL);
        draftPreview = panelText();
        draftControls.addView(draftPreview);
        Button keep = new Button(service);
        keep.setText("保留原草稿（不修改）");
        keep.setOnClickListener(view -> commitDraft(DraftPlan.Mode.KEEP));
        draftControls.addView(keep);
        appendDraft = new Button(service);
        appendDraft.setText("已核对对象：追加到原草稿（不发送）");
        appendDraft.setOnClickListener(view -> commitDraft(DraftPlan.Mode.APPEND));
        draftControls.addView(appendDraft);
        replaceDraft = new Button(service);
        replaceDraft.setOnClickListener(view -> commitDraft(DraftPlan.Mode.REPLACE));
        draftControls.addView(replaceDraft);
        content.addView(draftControls);
        content.addView(operationStatus);
        sourceDetails = panelText();
        sourceToggle = new Button(service);
        sourceToggle.setText("查看识别原文 · 请核对");
        sourceToggle.setOnClickListener(view -> {
            boolean opening = sourceDetails.getVisibility() != View.VISIBLE;
            sourceDetails.setVisibility(opening ? View.VISIBLE : View.GONE);
            sourceToggle.setText(opening ? "收起识别原文" : "查看识别原文 · 请核对");
        });
        content.addView(sourceToggle);
        sourceDetails.setVisibility(android.view.View.GONE);
        content.addView(sourceDetails);
        content.addView(managementToggle);
        content.addView(management);
        clearSelection();
        scroll.addView(content);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(220)));
        actions = new LinearLayout(service);
        Button pauseButton = null;
        for (String label : new String[]{"收起", "暂停", "停止"}) {
            Button button = new Button(service);
            button.setText(label);
            button.setTextSize(12);
            if ("暂停".equals(label)) {
                pauseButton = button;
            }
            button.setOnClickListener(view -> {
                if ("收起".equals(label)) {
                    AssistantState.requestExpansion(false);
                    dismissQuick();
                } else if ("暂停".equals(label)) {
                    AssistantState.paused = !AssistantState.paused;
                    AssistantState.requestExpansion(false);
                    if (AssistantState.paused) {
                        AssistantState.invalidateScope("已手动暂停");
                    }
                } else {
                    CaptureControl.stopAll(service);
                }
                refresh(ChatGateService.snapshot().valid() && AssistantCaptureService.running());
            });
            actions.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        }
        pause = pauseButton;
        panel.addView(actions);
        params = new WindowManager.LayoutParams(dp(56), -2,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
        if (Build.VERSION.SDK_INT >= 30) {
            params.setFitInsetsTypes(0);
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        panel.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            captureOcclusion();
            panel.post(this::fitPosition);
        });
        ball.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                handler.removeCallbacks(persistPosition);
                touchVertical = event.getRawY();
                touchHorizontal = event.getRawX();
                startOffset = params.y;
                startHorizontal = params.x;
                int[] location = new int[2];
                panel.getLocationOnScreen(location);
                startScreenTop = location[1];
                dragged = false;
                dragging = !AssistantState.expanded;
                captureOcclusion();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE && !AssistantState.expanded) {
                float delta = event.getRawY() - touchVertical;
                float horizontal = event.getRawX() - touchHorizontal;
                if (dragged || Math.hypot(delta, horizontal) > dp(8)) {
                    dragged = true;
                    int targetTop = Math.max(viewportTop(), Math.min(viewportBottom() - panel.getHeight(), startScreenTop + Math.round(delta)));
                    params.y = startOffset + targetTop - startScreenTop;
                    params.x = Math.max(viewport.dockLeft(params.width, false),
                            Math.min(viewport.dockLeft(params.width, true), startHorizontal + Math.round(horizontal)));
                    lastDragUpdate = android.os.SystemClock.elapsedRealtime();
                    if (attached) {
                        updateWindow();
                    }
                }
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                dragging = false;
                if (dragged) {
                    dockRight = params.x + params.width / 2 >= screen.widthPixels / 2;
                    dockHorizontally();
                    // 保存前钳制到可见范围，防止瞬态负 y 被持久化（重启后球停在屏幕外）。
                    savedVerticalFraction = Math.max(0.03f, Math.min(0.9f,
                            (float) params.y / Math.max(1, screen.heightPixels)));
                    if (attached) {
                        updateWindow();
                    }
                    handler.postDelayed(persistPosition, 350);
                }
                if (!dragged && event.getAction() == MotionEvent.ACTION_UP) {
                    view.performClick();
                }
                return true;
            }
            return false;
        });
        ball.setOnClickListener(view -> {
            boolean controlsOnly = AssistantState.paused || AssistantState.excluded;
            if (quick.getVisibility() == View.VISIBLE || AssistantState.expanded) {
                dismissQuick();
            } else {
                suggestionVisibility.open();
                // 贴底聊天时收起条因空隙不足不显示（不得遮输入框），点球切换建议面板
                // 展开/收起：展开态覆盖消息区（底边不越过输入栏），采集端暂停帧处理，
                // 是贴底场景查看完整建议的入口。
                suggestCollapsed = !suggestCollapsed;
            }
            AssistantState.requestExpansion(controlsOnly && !AssistantState.expanded);
            resetFolds();
            if (AssistantState.expanded) {
                seenAnalysis = AssistantState.analysis;
                scroll.scrollTo(0, 0);
            }
            refresh(true);
        });

        // 消息级标注卡：半透明两行小卡片（定稿 01 屏），第一行「意图 · N%」、第二行完整意图文本。
        // 独立轻量窗口，不抢焦点；点击展开主卡片查看依据与回复。
        insight = new LinearLayout(service);
        insight.setOrientation(LinearLayout.VERTICAL);
        insight.setPadding(dp(10), dp(6), dp(10), dp(7));
        insight.setBackground(roundedStroke(0xCCFFFFFF, 10, 0x66FFFFFF, 1));
        insight.setOnClickListener(view -> {
            // 点击标注卡展开主卡片，查看完整依据与回复建议。
            AssistantState.requestExpansion(true);
            refresh(ChatGateService.snapshot().valid() && AssistantCaptureService.running());
        });
        insightTitle = new TextView(service);
        insightTitle.setTextSize(11);
        insightTitle.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        insight.addView(insightTitle, new LinearLayout.LayoutParams(-2, -2));
        insightText = new TextView(service);
        insightText.setTextSize(11);
        insightText.setTextColor(0xFF14261F);
        // 定稿要求完整意图文本不截断；卡片宽度有限，换行展示。
        insightText.setMaxLines(4);
        insightText.setPadding(0, dp(1), 0, 0);
        insight.addView(insightText, new LinearLayout.LayoutParams(-2, -2));
        // 标注卡挂载时登记自己的窗口 ID，供 ownedWindows 忽略其无障碍事件与遮挡，
        // 否则它会被当作外来遮挡 → uncertain → viewport=null → 范围永远失效（自指循环）。
        insight.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                view.post(() -> registerOwnedWindow(insight));
            }
            @Override public void onViewDetachedFromWindow(View view) { }
        });
        insightParams = new WindowManager.LayoutParams(dp(200), -2,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        insightParams.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 30) {
            insightParams.setFitInsetsTypes(0);
            insightParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }

        // 回复建议面板：毛玻璃底（白 80% + 半透明白描边），头部/建议行/底部提示三段。
        // TYPE_ACCESSIBILITY_OVERLAY 公开 API 不提供跨窗口背景模糊；
        // 定稿的 30% 白在浅色聊天底上可读，但展开态会覆盖绿色气泡，需提高不透明度保证墨色文字可读。
        suggestPanel = new LinearLayout(service);
        suggestPanel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0xCCFFFFFF);
        glass.setCornerRadius(dp(20));
        glass.setStroke(dp(1), 0x73FFFFFF);
        suggestPanel.setBackground(glass);
        suggestPanel.setPadding(dp(12), dp(8), dp(12), dp(6));
        LinearLayout suggestHead = new LinearLayout(service);
        suggestHead.setOrientation(LinearLayout.HORIZONTAL);
        suggestHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView suggestSpark = new TextView(service);
        suggestSpark.setText("✦");
        suggestSpark.setTextSize(13);
        suggestSpark.setTextColor(0xFF0E7A55);
        suggestHead.addView(suggestSpark, new LinearLayout.LayoutParams(-2, -2));
        TextView suggestTitle = new TextView(service);
        suggestTitle.setText("回复建议");
        suggestTitle.setTextSize(14);
        suggestTitle.setTextColor(0xFF14261F);
        suggestTitle.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        suggestTitle.setPadding(dp(6), 0, 0, 0);
        suggestHead.addView(suggestTitle, new LinearLayout.LayoutParams(-2, -2));
        suggestBadge = new TextView(service);
        suggestBadge.setTextSize(10);
        suggestBadge.setTextColor(0xFF0E7A55);
        suggestBadge.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        suggestBadge.setPadding(dp(6), dp(1), dp(6), dp(1));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(0xFFE4F2EB);
        badgeBg.setCornerRadius(dp(999));
        suggestBadge.setBackground(badgeBg);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2);
        badgeParams.leftMargin = dp(8);
        suggestHead.addView(suggestBadge, badgeParams);
        TextView headSpacer = new TextView(service);
        suggestHead.addView(headSpacer, new LinearLayout.LayoutParams(0, 0, 1));
        suggestChevron = new TextView(service);
        suggestChevron.setTextSize(13);
        suggestChevron.setTextColor(0xFF5C6B64);
        suggestChevron.setPadding(dp(8), dp(4), dp(4), dp(4));
        suggestChevron.setContentDescription("收起或展开回复建议");
        suggestChevron.setOnClickListener(view -> {
            suggestCollapsed = !suggestCollapsed;
            refresh(ChatGateService.snapshot().valid() && AssistantCaptureService.running());
        });
        suggestHead.addView(suggestChevron, new LinearLayout.LayoutParams(-2, -2));
        suggestPanel.addView(suggestHead, new LinearLayout.LayoutParams(-1, -2));
        suggestRows = new LinearLayout(service);
        suggestRows.setOrientation(LinearLayout.VERTICAL);
        suggestPanel.addView(suggestRows, new LinearLayout.LayoutParams(-1, -2));
        suggestFooter = new TextView(service);
        suggestFooter.setText("点击建议文本即可复制 · 助手不会自动发送");
        suggestFooter.setTextSize(10);
        suggestFooter.setTextColor(0x995C6B64);
        suggestFooter.setGravity(Gravity.CENTER);
        suggestFooter.setPadding(0, dp(4), 0, dp(2));
        suggestPanel.addView(suggestFooter, new LinearLayout.LayoutParams(-1, -2));
        suggestPanel.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                view.post(() -> registerOwnedWindow(suggestPanel));
            }
            @Override public void onViewDetachedFromWindow(View view) { }
        });
        suggestParams = new WindowManager.LayoutParams(-2, -2,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        suggestParams.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 30) {
            suggestParams.setFitInsetsTypes(0);
            suggestParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        // 静态构建完成后的按钮样式收口；动态建议按钮在 renderReplies 中单独套用。
        styleDescendantButtons(panel);
    }

    void refresh(boolean show) {
        ChatGateService.Snapshot sourceScope = ChatGateService.snapshot();
        if (!show || !sourceScope.valid() || !AssistantCaptureService.running()) {
            close();
            AssistantState.expanded = false;
            return;
        }
        ChatViewport sourceViewport = sourceScope.viewport;
        viewport = sourceViewport == null ? null : sourceViewport.overlayGeometry();
        if (viewport == null || viewportBottom() - viewportTop() < dp(56)) {
            if (viewport == null) AssistantState.expanded = false;
            else AssistantState.requestExpansion(false);
            close();
            return;
        }
        screen.widthPixels = viewport.width;
        screen.heightPixels = viewport.height;
        if (!attached) {
            params.y = Math.round(savedVerticalFraction * screen.heightPixels);
        }
        int available = viewportBottom() - viewportTop();
        if ((AssistantState.expanded || AssistantState.expansionRequested()) && available < dp(200)) {
            AssistantState.requestExpansion(false);
            clearSelection();
            AssistantState.status = "可用高度不足，已收起卡片；请先收起键盘";
        } else if (!AssistantState.expanded && AssistantState.expansionRequested()) {
            // 只恢复显示偏好；即使失效期间未执行close，也先撤销旧草稿回调再开放详情控件。
            clearSelection();
            contextPanel.close();
            AssistantState.expanded = true;
        }
        boolean expanded = AssistantState.expanded;
        contextPanel.refresh();
        if (renderedExpanded != expanded) {
            params.y = Math.round(savedVerticalFraction * screen.heightPixels);
            renderedExpanded = expanded;
        }
        AnalysisResult analysis = AssistantState.analysis;
        // 结果出现后不再让主面板自动膨胀成「快捷建议条」：面板从小球(56dp)变高会改变正文区域
        // 遮挡指纹，触发「内容变化→清空→重新分析」的自指死循环。消息级意图由 insight 标注条
        // 就地展示（不遮挡气泡、不纳入遮挡上报）；完整建议由用户点小球/标注条主动展开查看。
        boolean compact = false;
        // 建议条缩短标题带，正文仍保留三行、操作区仍保留48dp触摸高度。
        LinearLayout.LayoutParams titleParams = (LinearLayout.LayoutParams) ball.getLayoutParams();
        int titleHeight = dp(compact ? 24 : 40);
        boolean titleResized = titleParams.height != titleHeight;
        if (titleResized) {
            titleParams.height = titleHeight;
            ball.setLayoutParams(titleParams);
        }
        quick.setVisibility(compact ? View.VISIBLE : View.GONE);
        renderQuick();
        boolean demo = AnalysisCoordinator.demoEnabled();
        String demoLabel = demo ? "退出模拟建议" : service.getString(R.string.local_demo_suggestions);
        if (!demoLabel.contentEquals(demoToggle.getText())) demoToggle.setText(demoLabel);
        String retryLabel = demo ? "刷新模拟建议（本地）" : "重新分析（可能收费）";
        if (!retryLabel.contentEquals(retry.getText())) retry.setText(retryLabel);
        demoNotice.setVisibility(demo ? View.VISIBLE : View.GONE);
        String label = expanded || compact ? demo ? "本地模拟 · 固定示例" : "聊天助手 · 回复建议" : AssistantState.paused ? "暂停"
                : AssistantState.excluded ? "排除" : analysis != null ? analysis == seenAnalysis ? "查看" : "新建议" : "助手";
        // 收起态悬浮球按定稿 01 屏显示机器人图标而非文字；展开标题行/暂停/排除仍用文字。
        boolean iconBall = !expanded && !compact && !AssistantState.paused && !AssistantState.excluded;
        if (iconBall != ballIconMode) {
            ballIconMode = iconBall;
            ball.setBackground(iconBall ? ballIconBackground()
                    : roundedStroke(0xFFFFFFFF, 28, 0xFFDCE7E2, 1));
        }
        if (iconBall) {
            if (ball.length() > 0) ball.setText("");
        } else if (!label.contentEquals(ball.getText())) {
            ball.setText(label);
        }
        scroll.setVisibility(expanded ? android.view.View.VISIBLE : android.view.View.GONE);
        actions.setVisibility(expanded ? android.view.View.VISIBLE : android.view.View.GONE);
        String pauseLabel = AssistantState.paused ? "继续" : "暂停";
        if (!pauseLabel.contentEquals(pause.getText())) pause.setText(pauseLabel);
        if (exclusionTarget != null && !AssistantState.matches(exclusionTarget)) {
            exclusionTarget = null;
            exclude.setText("排除此 OCR 标题…");
        }
        exclude.setEnabled(!AssistantState.title.isEmpty() && !AssistantState.paused && !AssistantState.excluded);
        // 简洁建议条不构建隐藏的分析正文和回复按钮；旧选择仍立即撤销，展开时按当前结果补建。
        if (renderedAnalysis != analysis && (expanded || renderedAnalysis != null)) {
            resetFolds();
            explanation.setText(!expanded || analysis == null ? "" : AnalysisPresentation.details(analysis, AssistantState.analysisSource));
            explanationToggle.setVisibility(!expanded || analysis == null || analysis.simulated ? android.view.View.GONE : android.view.View.VISIBLE);
            scroll.scrollTo(0, 0);
            boolean hadSelection = replyTarget != null;
            boolean hadWrite = writeMayHaveOccurred;
            renderReplies(expanded ? analysis : null);
            if (hadSelection) {
                operationStatus.setText(hadWrite ? "结果变化，已取消后续操作；先前可能已修改草稿，请检查微信。没有发送。"
                        : "会话或结果变化，已取消回复选择，没有请求填入。");
            }
        }
        if (expanded) {
            seenAnalysis = analysis;
        }
        if (replyTarget != null && (!AssistantState.matches(replyTarget) || selectedAnalysis != analysis)) {
            clearSelection();
            operationStatus.setText("会话或结果变化，已取消后续操作；如曾请求填入，请检查微信草稿。没有发送。");
        }
        int width = Math.min(expanded || compact ? dp(296) : dp(56), viewport.contentWidth());
        boolean resized = titleResized || params.width != width;
        params.width = width;
        int height = compact ? dp(156) : expanded ? -2 : dp(56);
        resized |= params.height != height;
        params.height = height;
        if (!dragging) {
            int previousHorizontal = params.x;
            dockHorizontally();
            resized |= previousHorizontal != params.x;
        }
        LinearLayout.LayoutParams scrollParams = (LinearLayout.LayoutParams) scroll.getLayoutParams();
        int chrome = Math.max(dp(104), panel.getHeight() - scroll.getHeight());
        // 键盘出现时保留详情滚动能力，但缩短视口给聊天留出更多可见区域。
        int detailLimit = sourceScope.keyboardTop >= 0 ? dp(112) : dp(220);
        int scrollHeight = Math.min(detailLimit, Math.max(dp(80), available - chrome));
        if (scrollParams.height != scrollHeight) {
            scrollParams.height = scrollHeight;
            scroll.setLayoutParams(scrollParams);
            resized = true;
        }
        if (expanded) {
            StringBuilder output = new StringBuilder();
            if (analysis != null && analysis.simulated) {
                output.append("本地模拟 · 固定示例，不是 AI 分析\n");
            } else if (analysis != null) {
                output.append("对话分析 · 仅供参考\n").append(AnalysisPresentation.brief(analysis, AssistantState.analysisSource)).append("\n\n")
                        .append(AnalysisPresentation.source(AssistantState.analysisSource, android.os.SystemClock.elapsedRealtime()))
                        .append("\n不是对方真实意图的证明；OCR 和表情需人工核对。\n按目的选择回复，确认后才尝试填入 / 复制，不自动发送。\n");
            } else {
                output.append(AssistantState.title.isEmpty() ? "聊天助手" : "当前聊天 · " + AssistantState.title)
                        .append("\n\n");
            }
            if (AssistantState.messages.isEmpty()) {
                output.append(AssistantState.status).append('\n');
            } else {
                output.append("最近识别 · 持续更新\n");
                java.util.List<ChatMessage> preview = AssistantState.messages;
                for (int index = Math.max(0, preview.size() - 2); index < preview.size(); index++) {
                    ChatMessage message = preview.get(index);
                    String text = message.text;
                    output.append(message.outgoing ? "我：" : "对方/成员：")
                            .append(text.length() > 160 ? text.substring(0, 160) + "…" : text).append('\n');
                }
            }
            output.append("\n仅当前可见内容；遮挡处可能漏字，请核对原文。\n");
            if (analysis == null) output.append(AssistantState.analysisStatus).append('\n');
            // publish/invalidate 均替换不可变列表；没有新快照时避免每次范围续期重建全文。
            java.util.List<ChatMessage> sourceMessages = AssistantState.messages;
            if (renderedSourceMessages != sourceMessages) {
                StringBuilder source = new StringBuilder("可核对的本地 OCR：\n");
                for (ChatMessage message : sourceMessages) {
                    source.append('\n').append(message.outgoing ? "自己：" : "对方/成员：").append(message.text);
                    if (message.uncertain) {
                        source.append(" [").append(message.warning.isEmpty() ? "混合字符需核对" : message.warning).append(']');
                    }
                    source.append('\n');
                }
                String sourceText = source.toString();
                if (!sourceText.contentEquals(sourceDetails.getText())) sourceDetails.setText(sourceText);
                renderedSourceMessages = sourceMessages;
            }
            String text = output.toString();
            if (!text.equals(lastDetails)) {
                lastDetails = text;
                details.setText(text);
            }
        }
        if (!windowsAdded) {
            // 窗口首次 addView：面板、标注卡与建议面板一次性加入，之后运行期内只 updateViewLayout
            // 调整位置，绝不在范围翻转时 removeView（窗口增删触发无障碍事件 → 范围失效 → 再增删的自指循环）。
            windowsAdded = true;
            manager.addView(panel, params);
            attached = true;
            // 隐藏态用 INVISIBLE 而不是负坐标：MIUI 会把负坐标钳制回屏幕原点，
            // 「隐藏」窗口实际可见且半透明面板叠在聊天内容上，污染投影帧指纹并反复清空分析结果。
            insight.setVisibility(View.INVISIBLE);
            manager.addView(insight, insightParams);
            insightAttached = true;
            suggestPanel.setVisibility(View.INVISIBLE);
            manager.addView(suggestPanel, suggestParams);
            suggestAttached = true;
        } else if (!attached) {
            // 已 addView 但上次被 hideWindows 隐藏：恢复可见并 updateViewLayout 回到 params 位置。
            layoutPending = true;
            captureOcclusion();
            if (panel.getVisibility() != View.VISIBLE) panel.setVisibility(View.VISIBLE);
            manager.updateViewLayout(panel, params);
            attached = true;
        } else if (resized) {
            updateWindow();
        } else {
            fitPosition();
        }
        updateInsight(show);
        updateSuggestions(show);
        // UI 侧布局应用后立即刷新自有窗口变化时间戳：主卡片展开/收起、意图卡与建议面板
        // 出现/消失都会改变投影帧指纹与消息可见区，采集侧的 600ms 过渡豁免依赖该时间戳；
        // 若等采集侧下一帧才轮询刷新，滞后窗口内的 bodyHash 变化会绕过豁免，
        // 触发 invalidate 清空会话与分析（实测点悬浮球后面板与意图卡同时消失，22:50 日志）。
        ChatGateService.selfWindowBounds();
    }

    /**
     * 消息级就地标注卡：把最新一条对方回复的「隐含需求/目的 + 准确率」以两行半透明小卡片
     * 显示在气泡右侧（定稿 01 屏）；右侧空间不足时回退到气泡下方间隙。仅在非暂停、非排除、
     * 未展开、有逐消息判断且该消息气泡几何可靠时显示；否则收起。
     * 坐标由共享帧局部坐标转换为全屏坐标（viewport 的 leftInset/topInset 即 bounds 左上角）。
     */
    private void updateInsight(boolean show) {
        String need = "";
        int confidence = -1;
        boolean simulated = false;
        int anchorBottom = -1;
        int anchorLeft = -1;
        int anchorRight = -1;
        int anchorTop = -1;
        boolean visible = false;
        // 诊断：记录标注卡未显示的具体原因，便于定位显示条件哪一环失效。
        String skipReason = "";
        if (!show) {
            skipReason = "show=false";
        } else if (AssistantState.paused) {
            skipReason = "paused";
        } else if (AssistantState.excluded) {
            skipReason = "excluded";
        } else if (AssistantState.expanded) {
            skipReason = "expanded";
        }
        if (skipReason.isEmpty()) {
            AnalysisResult analysis = AssistantState.analysis;
            java.util.List<ChatMessage> messages = AssistantState.messages;
            if (analysis == null) {
                skipReason = "analysis=null";
            } else if (analysis.perMessage.isEmpty()) {
                skipReason = "perMessage-empty";
            } else if (viewport == null) {
                skipReason = "viewport-null";
            } else if (messages.isEmpty()) {
                skipReason = "messages-empty";
            } else {
                // 只对「最后一条可见消息就是对方消息」就地叠加：卡片不遮挡任何气泡，也就不会
                // 因遮挡排除而改变识别文字、触发结果清空。
                ChatMessage last = messages.get(messages.size() - 1);
                if (last.outgoing) {
                    skipReason = "last-outgoing";
                } else if (!last.hasGeometry()) {
                    skipReason = "no-geometry";
                } else {
                    AnalysisResult.MessageInsight matched = analysis.perMessage.get(analysis.perMessage.size() - 1);
                    if (matched == null) {
                        skipReason = "matched-null";
                    } else if ("暂无法判断".equals(matched.need)) {
                        skipReason = "need-unknown";
                    } else {
                        simulated = analysis.simulated;
                        need = matched.need;
                        confidence = matched.confidence;
                        anchorLeft = last.left;
                        anchorBottom = last.bottom;
                        anchorRight = last.right;
                        anchorTop = last.top;
                        visible = true;
                    }
                }
            }
        }
        if (!visible) {
            // 不增删窗口（addView/removeView 会触发媒体投影虚拟显示器抖动，进而让范围校验失效、
            // 取消在途分析，形成自指循环）；无内容时只把窗口设为 INVISIBLE 并清空文字，保持常驻。
            // 不能用负坐标移出屏幕：MIUI 会把负坐标钳制回原点，隐藏窗实际可见并污染投影帧。
            insightScreenBounds = null;
            if (insightAttached) {
                if (!insightTitle.getText().toString().isEmpty()) {
                    insightTitle.setText("");
                    insightText.setText("");
                }
                if (insight.getVisibility() != View.INVISIBLE) insight.setVisibility(View.INVISIBLE);
            }
            if (!skipReason.isEmpty() && !skipReason.equals(lastInsightSkip)) {
                lastInsightSkip = skipReason;
                android.util.Log.i("ChatAssistant", "INSIGHT skip=" + skipReason);
            }
            return;
        }
        lastInsightSkip = "";
        // 展开态建议面板覆盖消息区：标注卡会被压在面板玻璃后面形成双层玻璃叠影，
        // 展开期间隐藏标注卡，收起后随下一次 refresh 恢复。
        if (ChatGateService.suggestExpanded()) {
            insightScreenBounds = null;
            if (insightAttached && insight.getVisibility() != View.INVISIBLE) {
                insight.setVisibility(View.INVISIBLE);
            }
            return;
        }
        // 第一行「意图 · N%」：百分比绿色加粗；第二行完整意图文本不截断。
        android.text.SpannableStringBuilder title = new android.text.SpannableStringBuilder("意图 · ");
        int percentStart = title.length();
        title.append(String.valueOf(confidence)).append('%');
        title.setSpan(new android.text.style.ForegroundColorSpan(0xFF0E7A55), percentStart, title.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        title.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), percentStart, title.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        insightTitle.setText(title);
        insightTitle.setTextColor(0xFF14261F);
        String body = (simulated ? "模拟 · " : "") + need;
        if (!body.contentEquals(insightText.getText())) insightText.setText(body);
        int contentWidth = viewport.contentWidth();
        insightParams.width = Math.min(dp(200), contentWidth - dp(16));
        // 定位策略（定稿 01 屏）：优先贴气泡右侧空白区（对方消息靠左，右侧通常有空）；
        // 右侧放不下时回退到气泡下方间隙。输入区顶部 = 微信窗口底部 - 输入框高度（56dp）。
        int windowBottom = viewport.height - viewport.bottomInset;
        int inputTop = windowBottom - dp(56);
        int screenBottom = viewport.topInset + anchorBottom;
        boolean belowFits = inputTop - screenBottom >= dp(16);
        // 气泡右边缘的全屏坐标 = viewport.leftInset + 局部 right；与 anchorLeft 同基准。
        int rightEdge = viewport.leftInset + anchorRight;
        int rightSpace = viewport.width - viewport.rightInset - rightEdge;
        boolean rightFits = rightSpace >= insightParams.width + dp(8);
        int cardHeight = insight.getHeight() > 0 ? insight.getHeight() : dp(34);
        if (!belowFits && !rightFits) {
            // 两种位置都放不下：不遮挡任何元素，把常驻窗口设为 INVISIBLE，不增删窗口。
            insightScreenBounds = null;
            if (insightAttached) {
                if (insight.getVisibility() != View.INVISIBLE) insight.setVisibility(View.INVISIBLE);
            }
            android.util.Log.i("ChatAssistant", "INSIGHT skip=no-space belowGap=" + (inputTop - screenBottom)
                    + " rightSpace=" + rightSpace + " width=" + insightParams.width);
            return;
        }
        if (rightFits) {
            // 右侧回退为主形态：贴气泡右边缘，垂直方向与气泡顶对齐，避免超出屏幕底部。
            insightParams.x = Math.min(rightEdge + dp(6), viewport.width - viewport.rightInset - insightParams.width);
            insightParams.y = Math.min(viewport.topInset + anchorTop, Math.max(viewport.topInset + dp(48), inputTop - cardHeight - dp(2)));
        } else {
            insightParams.x = Math.max(viewport.leftInset,
                    Math.min(viewport.leftInset + anchorLeft, viewport.width - viewport.rightInset - insightParams.width));
            insightParams.y = Math.max(viewport.topInset + dp(48), Math.min(screenBottom + dp(2), inputTop - dp(2)));
        }
        // 记录标注卡全屏包围盒，供采集端在指纹里遮蔽这段自身窗口区域，避免投影帧黑区触发结果清空。
        insightScreenBounds = new ChatViewport.Bounds(insightParams.x, insightParams.y,
                insightParams.x + insightParams.width, insightParams.y + cardHeight);
        // 窗口已由 refresh 一次性 addView 常驻；此处只调整位置与可见性，不做增删。
        if (insightAttached) {
            if (insight.getVisibility() != View.VISIBLE) insight.setVisibility(View.VISIBLE);
            manager.updateViewLayout(insight, insightParams);
        }
    }

    /**
     * 回复建议面板（定稿 01 屏重点）：悬浮于输入栏上方的毛玻璃面板，最多展示 3 条建议。
     * 展示条件与标注卡同源：非暂停/排除/展开，有非空回复且匹配当前会话快照；只在最后一条
     * 可见消息与输入栏之间的空隙内显示，绝不覆盖气泡（避免 OCR 读到面板像素污染识别）。
     * 高度约 1/3 屏封顶，放不下时自动收起为标题行，再放不下则隐藏。
     */
    private void updateSuggestions(boolean show) {
        AnalysisResult analysis = AssistantState.analysis;
        java.util.List<ChatMessage> messages = AssistantState.messages;
        String skipReason = "";
        AnalysisResult.Reply[] replies = null;
        if (!show) {
            skipReason = "show=false";
        } else if (AssistantState.paused) {
            skipReason = "paused";
        } else if (AssistantState.excluded) {
            skipReason = "excluded";
        } else if (AssistantState.expanded) {
            // 展开主卡片时收起建议面板，避免两块自窗同时覆盖聊天区。
            skipReason = "expanded";
        } else if (!ChatInteraction.quiet(android.os.SystemClock.elapsedRealtime())) {
            // 滚动/输入期间隐藏面板：聊天气泡可能滚入面板在投影帧中的黑区被误读，
            // 且内容变化本身就会触发重新分析，此处的显示门控不会引入额外清空。
            skipReason = "busy";
        } else if (viewport == null) {
            skipReason = "viewport-null";
        } else if (analysis == null || analysis.replies.isEmpty()) {
            skipReason = "replies-empty";
        } else if (messages.isEmpty() || !messages.get(messages.size() - 1).hasGeometry()) {
            skipReason = "no-geometry";
        } else {
            AssistantState.Snapshot target = AssistantState.snapshot();
            if (!AssistantState.matches(target)) {
                skipReason = "stale-snapshot";
            } else {
                replies = analysis.replies.toArray(new AnalysisResult.Reply[0]);
                suggestAnalysis = analysis;
                suggestTarget = target;
            }
        }
        if (replies == null) {
            if (suggestAnalysis != null && analysis != suggestAnalysis) {
                // 结果已清空或更换：复制反馈与行状态随之失效。
                copiedIndex = -1;
                suggestAnalysis = null;
                suggestTarget = null;
                renderedSuggest = null;
                suggestRows.removeAllViews();
            }
            hideSuggestions();
            if (!skipReason.isEmpty() && !skipReason.equals(lastSuggestSkip)) {
                lastSuggestSkip = skipReason;
                android.util.Log.i("ChatAssistant", "SUGGEST skip=" + skipReason);
            }
            return;
        }
        ChatMessage last = messages.get(messages.size() - 1);
        int windowBottom = viewport.height - viewport.bottomInset;
        // 几何合理性钳制：真机微信聊天页的窗口底部接近整屏（约 2200px）。
        // 微信 LauncherUI 等页面会被既有启发式检测间歇误判为聊天页，其误判几何明显偏小；
        // 面板是自动展示面，必须拒绝这种几何，避免悬浮到错误位置。
        if (windowBottom < dp(480)) {
            hideSuggestions();
            if (!"small-window".equals(lastSuggestSkip)) {
                lastSuggestSkip = "small-window";
                android.util.Log.i("ChatAssistant", "SUGGEST skip=small-window bottom=" + windowBottom);
            }
            return;
        }
        int inputTop = windowBottom - dp(56);
        // 面板底边硬上限：绝不遮输入框（用户明确要求）。
        int bottomLimit = inputTop - dp(4);
        int width = Math.min(dp(320), viewport.contentWidth() - dp(16));
        int collapsedHeight = dp(34);
        if (width < dp(200)) {
            hideSuggestions();
            if (!"narrow".equals(lastSuggestSkip)) {
                lastSuggestSkip = "narrow";
                android.util.Log.i("ChatAssistant", "SUGGEST skip=narrow width=" + width);
            }
            return;
        }
        // 此后必然进入显示路径：清掉 skip 去重键，让后续重新出现的 skip 原因能再记一次日志。
        lastSuggestSkip = "";
        suggestBadge.setText(analysis.simulated ? "本地示例" : "AI 分析");
        suggestChevron.setText(suggestCollapsed ? "﹀" : "︿");
        if (renderedSuggest != analysis) {
            // 新结果重建行时清掉上一结果的复制反馈，避免新结果同序号行误显示「已复制」。
            copiedIndex = -1;
            renderSuggestRows(analysis, replies);
            renderedSuggest = analysis;
        }
        int expandedHeight = measureSuggestions(width);
        int shownHeight;
        if (suggestCollapsed) {
            shownHeight = collapsedHeight;
            suggestRows.setVisibility(View.GONE);
            suggestFooter.setVisibility(View.GONE);
        } else {
            // 定稿 01 屏：展开态覆盖输入栏上方（含部分消息区，毛玻璃半透明透出内容）。
            // 覆盖区已纳入自有窗口识别遮蔽，且展开期间采集端暂停帧处理
            // （ChatGateService.suggestExpanded），被盖住的气泡不会造成消息集合振荡或结果自指清空。
            shownHeight = expandedHeight;
            suggestRows.setVisibility(View.VISIBLE);
            suggestFooter.setVisibility(View.VISIBLE);
        }
        // 水平位置跟随悬浮球（用户需求）：球中心在右半屏则面板右缘贴球右缘，左半屏则左缘贴球左缘，
        // 再夹到内容区内（两侧各留 8dp）。球拖动时 fitWindows 重走本方法，面板实时跟随。
        int contentLeft = viewport.leftInset + dp(8);
        int contentRight = viewport.leftInset + viewport.contentWidth() - dp(8);
        int ballWidth = Math.max(dp(56), params.width);
        boolean ballOnRight = params.x + ballWidth / 2 >= viewport.leftInset + viewport.contentWidth() / 2;
        int suggestX = ballOnRight ? params.x + ballWidth - width : params.x;
        suggestX = Math.max(contentLeft, Math.min(contentRight - width, suggestX));
        // 垂直定位分态（2026-10-30 23:41 实测教训）：
        // - 收起条是长期驻留的待命入口，必须贴输入栏上方且只在真实空隙显示——跟随球会遮住
        //   消息区某个气泡，消息集合缺一条 → publish 非豁免期清空分析 → 面板消失循环。
        // - 展开态是用户主动点球查看建议的临时形态，跟随球：首选球下方 8dp，下方放不下
        //   （会压到输入栏）则球上方 8dp。展开期采集端暂停帧处理 + 自窗遮蔽 + 600ms 豁免
        //   （selfWindowsChangedAt 由 UI 侧立即刷新）保护，被盖住的气泡不会引发循环。
        // 两态统一钳制：底边不越过 bottomLimit（绝不遮输入框），顶端不低于标题栏下方 48dp；
        // 主面板瞬态负 y 时也不得把面板带出屏幕外（实测 y=-275，23:37）。
        int minTop = viewport.topInset + dp(48);
        int suggestY;
        if (suggestCollapsed) {
            int gapTop = viewport.topInset + last.bottom;
            ChatViewport.Bounds insightBox = insightScreenBounds;
            if (insightBox != null && insightBox.bottom > gapTop) gapTop = insightBox.bottom + dp(2);
            if (collapsedHeight > bottomLimit - gapTop) {
                hideSuggestions();
                if (!"small-space".equals(lastSuggestSkip)) {
                    lastSuggestSkip = "small-space";
                    android.util.Log.i("ChatAssistant", "SUGGEST skip=small-space collapsedTop=" + gapTop
                            + " bottomLimit=" + bottomLimit);
                }
                return;
            }
            suggestY = bottomLimit - collapsedHeight;
        } else {
            // 球的真实屏幕位置必须用 getLocationOnScreen()：主面板窗口 gravity 含
            // CENTER_VERTICAL，params.y 是「相对屏幕中心的偏移」（y=600 → 实际顶边
            // (2400-154)/2+600=1723，与 dumpsys mFrame 完全吻合），直接当坐标用会把
            // 面板放到错误位置。高度 clamp 到球/主卡片合理范围，防 relayout 残留大值。
            int[] ballLocation = new int[2];
            panel.getLocationOnScreen(ballLocation);
            int ballHeight = Math.max(dp(56), Math.min(dp(300), panel.getHeight()));
            int ballBottom = ballLocation[1] + ballHeight;
            if (ballBottom + dp(8) + shownHeight <= bottomLimit) {
                suggestY = ballBottom + dp(8);
            } else {
                suggestY = ballLocation[1] - dp(8) - shownHeight;
            }
            suggestY = Math.max(minTop, Math.min(bottomLimit - shownHeight, suggestY));
        }
        suggestScreenBounds = new ChatViewport.Bounds(
                suggestX,
                suggestY,
                suggestX + width,
                suggestY + shownHeight);
        suggestParams.width = width;
        suggestParams.height = shownHeight;
        suggestParams.x = suggestX;
        suggestParams.y = suggestY;
        suggestVisibleBounds = new ChatViewport.Bounds(suggestParams.x, suggestParams.y,
                suggestParams.x + width, suggestParams.y + shownHeight);
        ChatGateService.setSuggestExpanded(suggestAttached && suggestPanel.getVisibility() == View.VISIBLE
                && shownHeight != collapsedHeight);
        if (suggestAttached) {
            if (suggestPanel.getVisibility() != View.VISIBLE) suggestPanel.setVisibility(View.VISIBLE);
            String layoutKey = width + "x" + shownHeight + "@" + suggestParams.x + "," + suggestParams.y;
            if (!layoutKey.equals(lastSuggestLayoutKey)) {
                lastSuggestLayoutKey = layoutKey;
                manager.updateViewLayout(suggestPanel, suggestParams);
                // 诊断日志：真机核对显示路径是否真正生效（可见性、收起态、窗口几何）。
                // 仅在布局签名变化时打印；rowsVis 为容器可见性（0=VISIBLE），非行数。
                android.util.Log.i("ChatAssistant", "SUGGEST show vis=" + suggestPanel.getVisibility()
                        + " collapsed=" + (shownHeight == collapsedHeight) + " w=" + width + " h=" + shownHeight
                        + " x=" + suggestParams.x + " y=" + suggestParams.y
                        + " ballOffsetY=" + params.y + " ballH=" + params.height
                        + " rowsVis=" + suggestRows.getVisibility());
            }
        }
    }

    /** 无建议可展示时把常驻窗口设为 INVISIBLE（不绘制像素、不污染投影帧）；不增删窗口，指纹遮蔽同步取消。 */
    private void hideSuggestions() {
        suggestScreenBounds = null;
        suggestVisibleBounds = null;
        lastSuggestLayoutKey = "";
        ChatGateService.setSuggestExpanded(false);
        if (suggestAttached && suggestPanel.getVisibility() != View.INVISIBLE) {
            suggestRows.setVisibility(View.VISIBLE);
            suggestFooter.setVisibility(View.VISIBLE);
            suggestPanel.setVisibility(View.INVISIBLE);
        }
    }

    /** 面板当前展开态包围盒（无可展示建议时 null）；仅供采集端指纹自遮蔽，不参与遮挡计数。 */
    ChatViewport.Bounds publishedSuggestionBounds() {
        return suggestScreenBounds;
    }

    /** 以精确宽度预量面板展开高度；上限约 1/3 屏（定稿：高度克制、不遮挡聊天内容）。 */
    private int measureSuggestions(int width) {
        int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        suggestPanel.measure(widthSpec, View.MeasureSpec.UNSPECIFIED);
        int thirdScreen = Math.max(dp(120), (screen.heightPixels - viewport.topInset - viewport.bottomInset) / 3);
        return Math.min(suggestPanel.getMeasuredHeight(), thirdScreen);
    }

    private void renderSuggestRows(AnalysisResult analysis, AnalysisResult.Reply[] replies) {
        suggestRows.removeAllViews();
        suggestRowBodies.clear();
        suggestFeedbacks.clear();
        int limit = Math.min(3, replies.length);
        for (int index = 0; index < limit; index++) {
            final int rowIndex = index;
            AnalysisResult.Reply reply = replies[index];
            LinearLayout row = new LinearLayout(service);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(8), dp(6), dp(8), dp(6));
            if (index > 0) {
                View divider = new View(service);
                divider.setBackgroundColor(0x1A14261F);
                suggestRows.addView(divider, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))));
            }
            LinearLayout body = new LinearLayout(service);
            body.setOrientation(LinearLayout.HORIZONTAL);
            TextView text = new TextView(service);
            text.setText(reply.text);
            text.setTextSize(13);
            text.setTextColor(0xFF14261F);
            text.setMaxLines(3);
            text.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.setLineSpacing(dp(2), 1);
            body.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            // 右侧：置信度百分比 + 40dp 渐变进度条；模型未提供置信度时不伪造数值。
            LinearLayout meter = new LinearLayout(service);
            meter.setOrientation(LinearLayout.VERTICAL);
            meter.setGravity(Gravity.END);
            TextView percent = new TextView(service);
            percent.setTextSize(13);
            percent.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            meter.addView(percent, new LinearLayout.LayoutParams(-2, -2));
            if (reply.confidence >= 0) {
                percent.setText(reply.confidence + "%");
                boolean strong = reply.confidence >= 70;
                percent.setTextColor(strong ? 0xFF0E7A55 : 0xFF8C9993);
                LinearLayout barTrack = new LinearLayout(service);
                barTrack.setOrientation(LinearLayout.HORIZONTAL);
                GradientDrawable trackBg = new GradientDrawable();
                trackBg.setColor(strong ? 0x3D0E7A55 : 0x338C9993);
                trackBg.setCornerRadius(dp(2));
                barTrack.setBackground(trackBg);
                barTrack.setGravity(Gravity.START);
                View barFill = new View(service);
                GradientDrawable fillBg = new GradientDrawable();
                if (strong) {
                    fillBg.setColors(new int[]{0xFF0E7A55, 0xFF0FB0A5});
                    fillBg.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
                } else {
                    fillBg.setColor(0xFF8C9993);
                }
                fillBg.setCornerRadius(dp(2));
                barFill.setBackground(fillBg);
                barTrack.addView(barFill, new LinearLayout.LayoutParams(
                        Math.max(dp(2), dp(40) * reply.confidence / 100), dp(3)));
                LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(dp(40), dp(3));
                trackParams.topMargin = dp(3);
                meter.addView(barTrack, trackParams);
            } else {
                percent.setText(analysis.simulated ? "示例" : "·");
                if (!analysis.simulated) percent.setTextColor(0xFF8C9993);
            }
            body.addView(meter, new LinearLayout.LayoutParams(-2, -2));
            row.addView(body, new LinearLayout.LayoutParams(-1, -2));
            TextView feedback = new TextView(service);
            feedback.setText("已复制，去粘贴发送");
            feedback.setTextSize(11);
            feedback.setTextColor(0xFF0E7A55);
            feedback.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            feedback.setPadding(0, dp(3), 0, 0);
            feedback.setVisibility(View.GONE);
            row.addView(feedback, new LinearLayout.LayoutParams(-1, -2));
            row.setOnClickListener(view -> copySuggestion(rowIndex));
            suggestRows.addView(row, new LinearLayout.LayoutParams(-1, -2));
            suggestRowBodies.add(row);
            suggestFeedbacks.add(feedback);
        }
        applyCopiedFeedback();
    }

    /** 点击建议文本即复制（不添加复制按钮）；成功后该行淡绿高亮并显示行内反馈约 2 秒。 */
    private void copySuggestion(int index) {
        AnalysisResult analysis = suggestAnalysis;
        AssistantState.Snapshot target = suggestTarget;
        // 绑定展示时的分析对象与快照；过期内容不复制，与展开卡复制同一条校验链。
        if (analysis == null || target == null || AssistantState.analysis != analysis
                || !AssistantState.matches(target) || index < 0
                || index >= Math.min(3, analysis.replies.size())) {
            return;
        }
        if (!ChatInteraction.quiet(android.os.SystemClock.elapsedRealtime())) {
            // 聊天正在变化：建议即将更新，此时复制可能拿到过期文本，等待下一轮。
            return;
        }
        ChatGateService.Snapshot gate = ChatGateService.verify();
        if (!gate.valid() || gate.generation != target.generation || rules.excludes(target.title)) {
            copiedIndex = -1;
            applyCopiedFeedback();
            operationStatus.setText("会话或结果变化，本次未复制；请等待建议更新。不会自动发送。");
            return;
        }
        AnalysisResult.Reply reply = analysis.replies.get(index);
        beginNodeOperation();
        try {
            ClipData clip = ClipData.newPlainText("用户确认的回复建议", reply.text);
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
            service.getSystemService(ClipboardManager.class).setPrimaryClip(clip);
            copiedIndex = index;
            applyCopiedFeedback();
            handler.removeCallbacks(clearCopiedFeedback);
            handler.postDelayed(clearCopiedFeedback, 2000);
            operationStatus.setText("已复制；请自行核对对象和现有草稿后粘贴。本次复制没有执行填入或发送。");
        } catch (RuntimeException exception) {
            operationStatus.setText("系统未确认本次复制，请先检查剪贴板，再决定是否重试。不会自动发送。");
        }
    }

    /** 复制反馈只改行背景与反馈行可见性，不重建行，避免闪动。 */
    private void applyCopiedFeedback() {
        for (int index = 0; index < suggestRowBodies.size(); index++) {
            LinearLayout row = suggestRowBodies.get(index);
            TextView feedback = suggestFeedbacks.get(index);
            boolean copied = index == copiedIndex;
            row.setBackground(copied ? roundedStroke(0xFFE4F2EB, 10, 0xFF0E7A55, 1) : null);
            feedback.setVisibility(copied ? View.VISIBLE : View.GONE);
        }
    }

    private void resetCopiedFeedback() {
        copiedIndex = -1;
        applyCopiedFeedback();
    }

    /** 圆角 + 描边背景；供标注卡与复制反馈行共用。 */
    private GradientDrawable roundedStroke(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    /** 定稿 01 屏悬浮机器人头图标：天线 + 圆角头 + 双耳 + 白色双眼，矢量绘制不依赖图片资源。 */
    private android.graphics.drawable.Drawable robotHeadIcon() {
        return new android.graphics.drawable.Drawable() {
            @Override
            public void draw(android.graphics.Canvas canvas) {
                android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                paint.setColor(0xFF0E7A55);
                android.graphics.Rect bounds = getBounds();
                float cx = bounds.exactCenterX();
                float cy = bounds.exactCenterY();
                float unit = Math.min(bounds.width(), bounds.height()) / 24f;
                // 天线：立杆 + 顶部圆点
                paint.setStrokeWidth(1.6f * unit);
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                canvas.drawLine(cx, cy - 7.0f * unit, cx, cy - 4.4f * unit, paint);
                paint.setStyle(android.graphics.Paint.Style.FILL);
                canvas.drawCircle(cx, cy - 8.4f * unit, 1.4f * unit, paint);
                // 头部与双耳
                android.graphics.RectF head = new android.graphics.RectF(cx - 7.4f * unit, cy - 4.4f * unit,
                        cx + 7.4f * unit, cy + 6.2f * unit);
                canvas.drawRoundRect(head, 3.4f * unit, 3.4f * unit, paint);
                canvas.drawRoundRect(new android.graphics.RectF(cx - 9.6f * unit, cy - 1.4f * unit,
                        cx - 7.0f * unit, cy + 3.2f * unit), 1.2f * unit, 1.2f * unit, paint);
                canvas.drawRoundRect(new android.graphics.RectF(cx + 7.0f * unit, cy - 1.4f * unit,
                        cx + 9.6f * unit, cy + 3.2f * unit), 1.2f * unit, 1.2f * unit, paint);
                // 双眼
                paint.setColor(0xFFFFFFFF);
                canvas.drawCircle(cx - 3.2f * unit, cy + 0.8f * unit, 1.5f * unit, paint);
                canvas.drawCircle(cx + 3.2f * unit, cy + 0.8f * unit, 1.5f * unit, paint);
            }

            @Override
            public void setAlpha(int alpha) {
            }

            @Override
            public void setColorFilter(android.graphics.ColorFilter filter) {
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };
    }

    /** 收起态悬浮球背景：白圆底（描边同旧形态）+ 居中机器人图标。 */
    private android.graphics.drawable.Drawable ballIconBackground() {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xFFFFFFFF);
        circle.setStroke(dp(1), 0xFFDCE7E2);
        android.graphics.drawable.LayerDrawable layers =
                new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{circle, robotHeadIcon()});
        int inset = dp(9);
        layers.setLayerInset(1, inset, inset, inset, inset);
        return layers;
    }

    /** 标注条当前全屏包围盒（无显示时为 null）；只供采集端指纹遮蔽，不参与遮挡计数。 */
    ChatViewport.Bounds publishedInsightBounds() {
        return insightScreenBounds;
    }

    /** 常驻自窗挂载后登记窗口 ID，供 ownedWindows 忽略其无障碍事件与遮挡。 */
    private void registerOwnedWindow(View owner) {
        if (!owner.isAttachedToWindow()) {
            return;
        }
        android.view.accessibility.AccessibilityNodeInfo node = owner.createAccessibilityNodeInfo();
        if (node == null) {
            return;
        }
        try {
            int identifier = node.getWindowId();
            if (identifier >= 0) {
                windowRegistered.accept(identifier);
            }
        } finally {
            node.recycle();
        }
    }

    void close() {
        if (!AssistantCaptureService.running()) suggestionVisibility.open();
        quickTarget = null;
        quickAnalysis = null;
        quickText.setText("");
        quickCopy.setEnabled(false);
        contextPanel.close();
        // 范围翻转导致的 close 不再 removeView：窗口增删本身会触发无障碍 TYPE_WINDOWS_CHANGED 事件，
        // 使范围租约失效 → revokeCurrent → 再次 close，形成「窗口抖动 → 范围失效 → 窗口抖动」自指死循环，
        // 并让 OwnWindowIds(64) 被高速递增的窗口 ID 打满，进而无法识别自有窗口、把范围永远判失效。
        // 改为仅把窗口设为 INVISIBLE 并清空内容，保持常驻；真正的销毁只在 destroy() 里 removeView。
        hideWindows();
        lastDetails = "";
        dragging = false;
        layoutPending = false;
        captureOcclusion();
        handler.removeCallbacksAndMessages(null);
        details.setText("");
        explanation.setText("");
        resetFolds();
        exclusionTarget = null;
        exclude.setText("排除此 OCR 标题…");
        renderedAnalysis = null;
        seenAnalysis = null;
        replyChoices.removeAllViews();
        copyChoices.removeAllViews();
        clearSelection();
        operationStatus.setText("");
        sourceDetails.setText("");
        renderedSourceMessages = null;
        sourceDetails.setVisibility(android.view.View.GONE);
        sourceToggle.setText("查看识别原文 · 请核对");
        // 建议面板随范围失效一并复位：行、复制反馈与绑定对象清空，窗口由 hideWindows 移出屏幕。
        renderedSuggest = null;
        suggestAnalysis = null;
        suggestTarget = null;
        copiedIndex = -1;
        suggestRows.removeAllViews();
        suggestRowBodies.clear();
        suggestFeedbacks.clear();
    }

    /** 必须在主线程读取；安全窗口不向投射输出助手正文，几何遮蔽再剔除邻近气泡。 */
    OverlayOcclusion publishedCaptureOcclusion() { return captureOcclusion; }

    OverlayOcclusion captureOcclusion() {
        boolean known = !dragging && !layoutPending && (!attached || panel.isAttachedToWindow() && panel.isShown()
                && panel.getWidth() > 0 && panel.getHeight() > 0 && panel.getWidth() == params.width);
        ChatViewport.Bounds bounds = null;
        if (attached && known) {
            int[] point = new int[2];
            panel.getLocationOnScreen(point);
            bounds = new ChatViewport.Bounds(point[0], point[1], point[0] + panel.getWidth(), point[1] + panel.getHeight());
        }
        // 消息级标注条不纳入遮挡上报：它只落在气泡与输入区之间的间隙、不覆盖任何气泡，
        // 若并入 bounds 会让采集把遮挡变化当作新识别环境，进而清空产生它的分析结果（自指循环）。
        if (captureOcclusion == null || !captureOcclusion.sameShape(known, bounds))
            captureOcclusion = new OverlayOcclusion(known, bounds, android.os.SystemClock.elapsedRealtime());
        return captureOcclusion;
    }

    private TextView panelText() {
        TextView view = new TextView(service);
        // 毛玻璃白底上的正文使用墨色；次级说明用次级灰。
        view.setTextColor(0xFF14261F);
        view.setTextSize(14);
        return view;
    }

    /** 展开面板内的按钮统一为品牌浅底胶囊；只改观感，不改点击语义。 */
    private void stylePill(Button button) {
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setTextColor(0xFF0E7A55);
        button.setMinHeight(dp(36));
        button.setStateListAnimator(null);
        button.setBackground(roundedStroke(0xFFE4F2EB, 12, 0xFFDCEAE3, 1));
    }

    /** 构建完成后统一收口：展开面板内所有按钮应用胶囊样式。 */
    private void styleDescendantButtons(android.view.ViewGroup root) {
        for (int index = 0; index < root.getChildCount(); index++) {
            View child = root.getChildAt(index);
            if (child instanceof Button) {
                stylePill((Button) child);
            } else if (child instanceof android.view.ViewGroup) {
                styleDescendantButtons((android.view.ViewGroup) child);
            }
        }
    }

    private void registerAttachedWindow() {
        if (!panel.isAttachedToWindow()) return;
        android.view.accessibility.AccessibilityNodeInfo node = panel.createAccessibilityNodeInfo();
        if (node == null) return;
        try {
            int identifier = node.getWindowId();
            if (identifier >= 0) windowRegistered.accept(identifier);
        } finally {
            node.recycle();
        }
    }

    void destroy() {
        close();
        // 真正销毁时才移除窗口（无障碍服务重连/几何变化/进程结束）；运行时范围翻转一律走 hideWindows。
        detachWindows();
        contextPanel.release();
    }

    /** 把三个常驻窗口设为 INVISIBLE 并清空标注文字；不 removeView，避免触发无障碍窗口事件自指循环。
     *  也不能用负坐标移出屏幕：MIUI 会把负坐标钳制回屏幕原点，隐藏窗实际可见并污染投影帧。 */
    private void hideWindows() {
        if (attached) {
            if (panel.getVisibility() != View.INVISIBLE) panel.setVisibility(View.INVISIBLE);
            attached = false;
        }
        if (insightAttached) {
            if (!insightText.getText().toString().isEmpty()) {
                insightTitle.setText("");
                insightText.setText("");
            }
            if (insight.getVisibility() != View.INVISIBLE) insight.setVisibility(View.INVISIBLE);
        }
        if (suggestAttached) {
            if (suggestPanel.getVisibility() != View.INVISIBLE) suggestPanel.setVisibility(View.INVISIBLE);
        }
        // 与 suggestScreenBounds 对称：标注卡不可见期间同步取消指纹遮蔽，
        // 避免长期早退的 refresh 让过期包围盒遮蔽一段没有标注卡的区域。
        insightScreenBounds = null;
        suggestScreenBounds = null;
        suggestVisibleBounds = null;
    }

    /**
     * 自有安全窗口当前实际可见区域（主卡片 + 意图卡 + 建议面板，隐藏即不含）。
     * 采集端把这些区域并入识别遮蔽，并用于自有窗口过渡时间戳（selfWindowsChangedAt）：
     * 主卡片展开/收起的窗口变形若不纳入，600ms 过渡豁免不生效，曾导致点悬浮球后
     * MATCH_FAIL 风暴、面板与意图卡双双消失（2026-10-30 真机）。
     */
    java.util.List<ChatViewport.Bounds> publishedSelfWindowBounds() {
        java.util.List<ChatViewport.Bounds> bounds = new java.util.ArrayList<>(3);
        if (AssistantState.expanded && attached && panel.getVisibility() == View.VISIBLE
                && params.width > 0) {
            int height = panel.getHeight() > 0 ? panel.getHeight() : params.height;
            if (height > 0) {
                bounds.add(new ChatViewport.Bounds(params.x, params.y,
                        params.x + params.width, params.y + height));
            }
        }
        ChatViewport.Bounds insightBox = insightScreenBounds;
        if (insightBox != null && insightAttached && insight.getVisibility() == View.VISIBLE) {
            bounds.add(insightBox);
        }
        ChatViewport.Bounds suggestBox = suggestVisibleBounds;
        if (suggestBox != null && suggestAttached && suggestPanel.getVisibility() == View.VISIBLE) {
            bounds.add(suggestBox);
        }
        return bounds;
    }

    private void detachWindows() {
        if (windowsAdded) {
            manager.removeView(panel);
            manager.removeView(insight);
            manager.removeView(suggestPanel);
            windowsAdded = false;
        }
        attached = false;
        insightAttached = false;
        suggestAttached = false;
    }

    private void excludeCurrent() {
        if (!ChatGateService.verify().valid() || AssistantState.paused || AssistantState.excluded) {
            return;
        }
        if (exclusionTarget == null) {
            AssistantState.Snapshot target = AssistantState.snapshot();
            if (!AssistantState.matches(target)) {
                operationStatus.setText("正在确认当前标题和正文，请稍候");
                return;
            }
            exclusionTarget = target;
            exclude.setText("确认排除：" + target.title + "（同名也排除）");
            operationStatus.setText("再次点击确认后，本机保存标题规则；之后可在助手的排除管理中删除。标题错读、改名仍可能漏配。");
            return;
        }
        AssistantState.Snapshot target = exclusionTarget;
        if (!AssistantState.matches(target)) {
            operationStatus.setText("会话变化，没有添加排除规则");
            exclusionTarget = null;
            return;
        }
        try {
            if (rules.add(target.title)) {
                operationStatus.setText("排除规则已保存；可在助手的排除管理中删除。仍需必要的共享帧和标题检查。");
                AssistantState.invalidate("已保存排除规则，重新检查标题；不再分析该标题正文");
                AssistantState.requestExpansion(false);
            } else {
                operationStatus.setText("保存失败，没有确认排除，请重试");
            }
        } catch (IllegalArgumentException exception) {
            operationStatus.setText(exception.getMessage());
        }
        exclusionTarget = null;
        exclude.setText("排除此 OCR 标题…");
    }

    private void renderReplies(AnalysisResult analysis) {
        renderedAnalysis = analysis;
        replyChoices.removeAllViews();
        copyChoices.removeAllViews();
        clearSelection();
        if (analysis == null) {
            return;
        }
        // 复制按钮绑定展示时的快照；旧卡片不能借点击时的新来源继续使用。
        AssistantState.Snapshot displayedTarget = AssistantState.snapshot();
        TextView copyHint = panelText();
        copyHint.setText("点选即复制并收起 · 将替换剪贴板 · 粘贴后自行发送");
        copyChoices.addView(copyHint);
        for (AnalysisResult.Reply reply : analysis.replies) {
            Button directCopy = new Button(service);
            stylePill(directCopy);
            directCopy.setAllCaps(false);
            directCopy.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            directCopy.setText(service.getString(analysis.simulated ? R.string.reply_copy_demo_card
                    : R.string.reply_copy_card, reply.objective, reply.text));
            directCopy.setOnClickListener(view -> {
                if (AssistantState.analysis != analysis || !AssistantState.matches(displayedTarget)
                        || !ChatInteraction.quiet(android.os.SystemClock.elapsedRealtime())) {
                    operationStatus.setText("聊天正在变化，请等待建议更新后再复制");
                    return;
                }
                boolean previousWrite = writeMayHaveOccurred;
                clearSelection();
                replyTarget = displayedTarget;
                selectedAnalysis = analysis;
                selectedReply = reply;
                writeMayHaveOccurred = previousWrite;
                copyReply();
            });
            copyChoices.addView(directCopy);
            Button choice = new Button(service);
            stylePill(choice);
            choice.setText((analysis.simulated ? "模拟 · " : "选择回复方向：") + reply.objective);
            choice.setOnClickListener(view -> {
                AssistantState.Snapshot target = AssistantState.snapshot();
                if (AssistantState.analysis != analysis || !AssistantState.matches(target)) {
                    operationStatus.setText("结果已过期，请等待当前会话重新分析");
                    return;
                }
                boolean previousWrite = writeMayHaveOccurred;
                clearSelection();
                replyTarget = target;
                selectedAnalysis = analysis;
                selectedReply = reply;
                writeMayHaveOccurred = false;
                replyPreview.setText((analysis.simulated ? "本地模拟：以下是固定示例，不是模型生成。\n\n" : "") + "请人工核对微信当前对象（不能自动区分同名联系人）。选择后仍需检查草稿并确认填入，或确认复制；不会发送：\n"
                        + target.title + "\n\n" + reply.text);
                replyActions.setVisibility(android.view.View.VISIBLE);
                scroll.post(() -> { if (selectedReply == reply) scroll.smoothScrollTo(0, replyPreview.getTop()); });
                operationStatus.setText((previousWrite ? "先前确认的填入可能已改变草稿，请先检查微信；没有发送。\n" : "")
                        + "复制会替换现有剪贴板；内容仍可能被系统或其他有权限的应用访问，请谨慎处理。");
            });
            replyChoices.addView(choice);
        }
    }

    private void copyReply() {
        ChatGateService.Snapshot gate = ChatGateService.verify();
        if (replyTarget == null || selectedReply == null || !gate.valid()
                || gate.generation != replyTarget.generation || !AssistantState.matches(replyTarget)
                || AssistantState.analysis != selectedAnalysis || rules.excludes(replyTarget.title)) {
            clearSelection();
            operationStatus.setText("当前会话或结果变化，本次未执行复制或填入；此前若已请求填入，请检查草稿。不会自动发送。");
            return;
        }
        boolean previousWrite = writeMayHaveOccurred;
        beginNodeOperation();
        try {
            ClipData clip = ClipData.newPlainText("用户确认的回复建议", selectedReply.text);
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
            service.getSystemService(ClipboardManager.class).setPrimaryClip(clip);
            android.widget.Toast.makeText(service, "已复制，请在微信粘贴后自行发送", android.widget.Toast.LENGTH_SHORT).show();
            clearSelection();
            dismissQuick();
            AssistantState.requestExpansion(false);
            refresh(ChatGateService.snapshot().valid() && AssistantCaptureService.running());
            operationStatus.setText(previousWrite
                    ? "已复制；先前确认的填入可能已改变草稿，请先检查微信再决定是否粘贴。本次仅复制，没有发送。"
                    : "已复制；请自行核对对象和现有草稿后粘贴。本次复制没有执行填入或发送。");
        } catch (RuntimeException exception) {
            operationStatus.setText("系统未确认本次复制，请先检查剪贴板和微信草稿，再决定是否重试。不会自动发送。");
        }
    }

    private void dismissQuick() {
        suggestionVisibility.dismiss(ChatInteraction.revision(), AssistantState.frames, android.os.SystemClock.uptimeMillis());
    }

    /** 卡片尺寸固定；结果失效立即清除，输入/滚动安静后才替换展示。 */
    private void renderQuick() {
        AnalysisResult current = AssistantState.analysis;
        if (quickTarget != null && (!AssistantState.matches(quickTarget) || current != quickAnalysis)) {
            quickTarget = null;
            quickAnalysis = null;
        }
        boolean quiet = ChatInteraction.quiet(android.os.SystemClock.elapsedRealtime());
        if (quickAnalysis == null && current != null && !current.replies.isEmpty() && quiet) {
            AssistantState.Snapshot target = AssistantState.snapshot();
            if (AssistantState.matches(target)) {
                quickTarget = target;
                quickAnalysis = current;
                quickIndex = 0;
            }
        }
        boolean ready = quickAnalysis != null && quickTarget != null;
        // 没有内容时必须说出原因：否则用户只能看到一个永远转不完的“正在准备建议…”。
        String pending = AssistantState.analysisStatus;
        String text;
        if (ready) {
            text = quickAnalysis.replies.get(quickIndex).text;
        } else if (!quiet) {
            text = "等你操作完，再更新建议";
        } else if (pending.contains("正在分析")) {
            text = "正在分析当前可见文字…";
        } else if (pending.isEmpty()) {
            text = "正在准备建议…";
        } else {
            text = pending.length() > 36 ? pending.substring(0, 36) + "…" : pending;
        }
        if (!text.contentEquals(quickText.getText())) quickText.setText(text);
        quickCopy.setEnabled(ready && quiet);
        quickNext.setEnabled(ready && quiet && quickAnalysis.replies.size() > 1);
    }

    /** 点击本身即复制确认；绑定正在展示的快照，随后仍走复制入口的实时校验。 */
    private void copyQuickReply() {
        if (quickTarget == null || quickAnalysis == null
                || !ChatInteraction.quiet(android.os.SystemClock.elapsedRealtime())) return;
        AssistantState.Snapshot target = quickTarget;
        AnalysisResult analysis = quickAnalysis;
        AnalysisResult.Reply reply = analysis.replies.get(quickIndex);
        clearSelection();
        replyTarget = target;
        selectedAnalysis = analysis;
        selectedReply = reply;
        copyReply();
    }

    private void clearSelection() {
        operationRevision++;
        cancelNodeTask();
        draftPlan = null;
        writeMayHaveOccurred = false;
        draftPreview.setText("");
        draftControls.setVisibility(android.view.View.GONE);
        replyTarget = null;
        selectedAnalysis = null;
        selectedReply = null;
        replyPreview.setText("");
        replyActions.setVisibility(android.view.View.GONE);
    }

    private void cancelNodeTask() {
        if (draftTask != null) {
            draftTask.cancel();
            draftTask = null;
        }
    }

    private long beginNodeOperation() {
        operationRevision++;
        cancelNodeTask();
        return operationRevision;
    }

    private BooleanSupplier nodePermission(long operation, AssistantState.Snapshot target, boolean replyRequired) {
        AnalysisResult expected = selectedAnalysis;
        return () -> operationRevision == operation && attached && AssistantState.expanded
                && AssistantState.matches(target) && (!replyRequired || AssistantState.analysis == expected)
                && operationRevision == operation;
    }

    private <Value> void runNodeTask(long operation, AssistantState.Snapshot target,
            AssistantCaptureService.ComposerProof proof, boolean replyRequired,
            BiFunction<DraftPlan.Editor, BooleanSupplier, Value> work, Consumer<Value> completed) {
        BooleanSupplier permission = nodePermission(operation, target, replyRequired);
        if (!permission.getAsBoolean()) return;
        cancelNodeTask();
        draftTask = draftOperations.submit(proof, permission, work, result -> {
            if (!permission.getAsBoolean()) return;
            draftTask = null;
            if (result.status != DraftWorker.Status.COMPLETED) {
                draftPlan = null;
                draftControls.setVisibility(android.view.View.GONE);
                operationStatus.setText(writeMayHaveOccurred
                        ? "输入框操作超时或未能确认，先前请求可能已经改变草稿。请先检查微信，不会自动重试、回滚或发送。"
                        : "输入框检查超时、过期或不可用，本次未请求修改。可确认复制后手动粘贴，不会强行输入。");
                android.util.Log.i("ChatAssistant", "DRAFT_NODES status=" + result.status + " writePossible=" + writeMayHaveOccurred);
                return;
            }
            completed.accept(result.value);
        });
        if (draftTask == null) {
            draftPlan = null;
            draftControls.setVisibility(android.view.View.GONE);
            operationStatus.setText("前次输入框查询仍在结束，本轮没有新请求；若先前确认过填入，请先检查草稿。不会自动重试或发送。");
        }
    }

    private boolean replyCurrent() {
        ChatGateService.Snapshot gate = ChatGateService.snapshot();
        return attached && AssistantState.expanded && replyTarget != null && selectedReply != null
                && gate.valid() && gate.generation == replyTarget.generation
                && AssistantState.matches(replyTarget) && AssistantState.analysis == selectedAnalysis
                && !rules.excludes(replyTarget.title);
    }

    private void checkCapability() {
        AssistantState.Snapshot target = AssistantState.snapshot();
        if (!AssistantState.expanded || !AssistantState.matches(target)) {
            operationStatus.setText("正在确认当前聊天标题和正文，请稍候");
            return;
        }
        long operation = beginNodeOperation();
        operationStatus.setText("正在用新的共享帧核对输入区；只读检查，不改草稿、不联网…");
        AssistantCaptureService.requestComposer(target, proof -> {
            if (operation != operationRevision || !attached || !AssistantState.expanded || !AssistantState.matches(target)) {
                return;
            }
            this.<DraftPlan.Field>runNodeTask(operation, target, proof, false,
                    (editor, allowed) -> editor == null ? null : editor.inspect(), field -> {
                operationStatus.setText(field == null ? "无法确认安全可写输入框，本次只读检查未执行写入。若微信未开放节点，选择建议后请用确认复制，不强行模拟输入。"
                        : field.text.isEmpty() ? "已找到当前输入区的唯一可写文本框，草稿为空。这里只读检查；选定回复后仍需确认填入。"
                        : "已找到当前输入区的唯一可写文本框，已有草稿。这里只读检查；选定回复后需选择保留、追加或替换。");
                android.util.Log.i("ChatAssistant", "DRAFT_CAPABILITY proof=" + (proof == null ? "UNCONFIRMED" : "CONFIRMED")
                        + " editor=" + (field == null ? "UNAVAILABLE" : "AVAILABLE") + " writes=0");
            });
        });
    }

    private void prepareDraft() {
        if (!replyCurrent()) {
            clearSelection();
            operationStatus.setText("会话或建议已过期，本次没有检查或请求修改；此前若已请求填入，请检查微信草稿。");
            return;
        }
        long operation = beginNodeOperation();
        AssistantState.Snapshot target = replyTarget;
        String reply = selectedReply.text;
        draftPlan = null;
        draftControls.setVisibility(android.view.View.GONE);
        operationStatus.setText("重新核对当前页面及草稿，只读检查；不会自动覆盖或发送…");
        AssistantCaptureService.requestComposer(target, proof -> {
            if (operation != operationRevision || !replyCurrent()) {
                return;
            }
            this.<DraftPlan>runNodeTask(operation, target, proof, true,
                    (editor, allowed) -> editor == null ? null : DraftPlan.inspect(editor, reply, allowed), plan -> {
                if (plan == null) {
                    operationStatus.setText("无法确认安全输入框或草稿，本次只读检查未请求修改；若先前确认过填入请先检查微信。可确认复制后手动粘贴，不会强行输入。");
                    return;
                }
                draftPlan = plan;
                draftPreview.setText("请核对微信当前对象，标题不能证明账号身份。\n"
                        + (plan.original.isEmpty() ? "当前草稿为空。" : "原草稿预览：\n" + previewDraft(plan.original))
                        + "\n\n选中的建议：\n" + reply + "\n\n追加会在原草稿后换行加入；替换会覆盖原草稿。操作后仍由你自己发送。");
                appendDraft.setVisibility(plan.original.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
                replaceDraft.setText(plan.original.isEmpty() ? "已核对对象：填入空草稿（不发送）" : "已核对对象：替换原草稿（不发送）");
                draftControls.setVisibility(android.view.View.VISIBLE);
                operationStatus.setText("选择保留、追加或替换；写入前还会再次核对，草稿变化会拒绝覆盖。");
            });
        });
    }

    private void commitDraft(DraftPlan.Mode mode) {
        DraftPlan plan = draftPlan;
        if (plan == null) {
            return;
        }
        if (mode == DraftPlan.Mode.KEEP) {
            clearSelection();
            operationStatus.setText("已保留当前草稿，本次未执行写入或发送");
            return;
        }
        if (!replyCurrent()) {
            clearSelection();
            operationStatus.setText("会话或建议已过期，没有请求填入");
            return;
        }
        long operation = beginNodeOperation();
        AssistantState.Snapshot target = replyTarget;
        draftControls.setVisibility(android.view.View.GONE);
        writeMayHaveOccurred = true;
        operationStatus.setText("正在重新核对页面、输入框和原草稿；仅这次确认允许一次文本设置，不会发送…");
        AssistantCaptureService.requestComposer(target, proof -> {
            if (operation != operationRevision || !replyCurrent()) {
                return;
            }
            this.<DraftPlan.Status>runNodeTask(operation, target, proof, true,
                    (editor, allowed) -> editor == null ? DraftPlan.Status.UNAVAILABLE : plan.commit(mode, editor, allowed), status -> {
                if (status == DraftPlan.Status.REQUESTED || status == DraftPlan.Status.UNCONFIRMED) {
                    operationStatus.setText("已请求设置草稿，正在只读核对结果；不会点击发送，不会自动重试…");
                    handler.postDelayed(() -> verifyDraft(plan, target, operation), 300);
                } else {
                    draftPlan = null;
                    operationStatus.setText(status == DraftPlan.Status.CHANGED ? "原草稿或输入框已变化，本轮未请求覆盖。请重新检查草稿。"
                            : status == DraftPlan.Status.TOO_LONG ? "追加后草稿过长，本轮未请求修改；请手动编辑。"
                            : "当前不满足安全填入条件，本轮未再次请求修改；如先前确认过填入请先检查微信，可确认复制。");
                }
            });
        });
    }

    private void verifyDraft(DraftPlan plan, AssistantState.Snapshot target, long operation) {
        if (operation != operationRevision || !replyCurrent()) {
            return;
        }
        operationStatus.setText("只读核对草稿是否与预期一致，不再写入…");
        AssistantCaptureService.requestComposer(target, proof -> {
            if (operation != operationRevision) {
                return;
            }
            this.<DraftPlan.Status>runNodeTask(operation, target, proof, true,
                    (editor, allowed) -> editor == null ? DraftPlan.Status.UNCONFIRMED : plan.verify(editor, allowed), status -> {
                clearSelection();
                operationStatus.setText(status == DraftPlan.Status.VERIFIED ? "已核对当前草稿与确认内容一致；没有发送，请自行检查后发送。"
                        : "无法确认写入结果，可能已改变草稿。请先检查微信，不会自动重试或发送；不要直接重复追加。");
            });
        });
    }

    private String previewDraft(String text) {
        if (text.length() <= 500) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(499)) ? 499 : 500;
        return text.substring(0, end) + "\n[仅预览前段，操作以完整草稿为准]";
    }

    private int viewportBottom() {
        int keyboard = ChatGateService.snapshot().keyboardTop;
        int bottom = viewport.height - viewport.bottomInset;
        return (keyboard >= 0 ? Math.min(keyboard, bottom) : bottom) - dp(64);
    }

    private int viewportTop() {
        return Math.max(dp(88), viewport.topInset + dp(48));
    }

    private void resetFolds() {
        explanation.setVisibility(android.view.View.GONE);
        explanationToggle.setText("查看分析详情");
        management.setVisibility(android.view.View.GONE);
        managementToggle.setText("更多操作");
        sourceDetails.setVisibility(android.view.View.GONE);
        sourceToggle.setText("查看识别原文 · 请核对");
    }

    private void dockHorizontally() {
        params.x = viewport.dockLeft(params.width, dockRight);
    }

    private void savePosition() {
        // 拖动后的延迟保存可能晚于详情打开；临时顶部停靠不能覆盖拖动时的偏好。
        if (!AssistantState.expanded || ChatGateService.snapshot().keyboardTop < 0) {
            fitPosition();
            savedVerticalFraction = Math.max(0.03f, Math.min(0.9f,
                    (float) params.y / Math.max(1, screen.heightPixels)));
        }
        positionStore.edit().putBoolean("dock_right", dockRight)
                .putFloat("vertical_fraction", savedVerticalFraction).apply();
    }

    /** 布局提交至下一次自有绘制前不提供几何许可；这不是系统合成回执。 */
    private void updateWindow() {
        layoutPending = true;
        captureOcclusion();
        manager.updateViewLayout(panel, params);
    }

    private void fitPosition() {
        if (!attached || panel.getHeight() == 0 || dragging || android.os.SystemClock.elapsedRealtime() - lastDragUpdate < 100) {
            return;
        }
        // 越界修正必须用 params.height/width（窗口参数目标值）：panel.getHeight() 是
        // 上一次布局的残留值，主卡片收起后 relayout 异步完成前仍是旧高度（~全屏），
        // 据此计算 shift 会把球连续推到屏幕外（实测 params.y=-451，2026-10-30 23:50）。
        int viewHeight = params.height > 0 ? params.height : panel.getHeight();
        int viewWidth = params.width > 0 ? params.width : panel.getWidth();
        int top = viewportTop();
        int bottom = viewportBottom();
        int available = bottom - top;
        if (AssistantState.expanded && viewHeight > available) {
            LinearLayout.LayoutParams layout = (LinearLayout.LayoutParams) scroll.getLayoutParams();
            int height = Math.max(dp(80), layout.height - (viewHeight - available));
            if (height < layout.height) {
                layout.height = height;
                scroll.setLayoutParams(layout);
                updateWindow();
                return;
            }
        }
        int[] location = new int[2];
        panel.getLocationOnScreen(location);
        // 键盘占用下半屏时，详情靠上给最近消息留空间；不覆盖用户保存的悬浮位置。
        boolean keyboardDetails = AssistantState.expanded && ChatGateService.snapshot().keyboardTop >= 0;
        int shift = keyboardDetails || location[1] < top ? top - location[1]
                : location[1] + viewHeight > bottom ? bottom - location[1] - viewHeight : 0;
        int horizontalShift = location[0] < viewport.leftInset ? viewport.leftInset - location[0]
                : location[0] + viewWidth > viewport.width - viewport.rightInset
                ? viewport.width - viewport.rightInset - location[0] - viewWidth : 0;
        if (shift != 0 || horizontalShift != 0) {
            params.y += shift;
            params.x += horizontalShift;
            updateWindow();
        }
    }

    private int dp(int value) {
        return Math.round(value * service.getResources().getDisplayMetrics().density);
    }
}
