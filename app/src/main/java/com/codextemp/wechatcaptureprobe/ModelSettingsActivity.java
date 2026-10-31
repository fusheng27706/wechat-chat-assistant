package com.codextemp.wechatcaptureprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class ModelSettingsActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ProductUi ui;
    private ModelSettingsStore store;
    private LinearLayout form;
    private EditText endpoint;
    private EditText model;
    private EditText secret;
    private EditText timeout;
    private EditText tokens;
    private CheckBox jsonMode;
    private CheckBox completionTokens;
    private TextView keyState;
    private TextView status;
    private TextView result;
    private Button test;
    private CheckBox partialAnalysis;
    private Switch cloud;
    private boolean cloudSync;
    private TextView connectionState;
    private TextView cloudState;
    private AnalysisProvider active;
    private Future<?> task;
    private int revision;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        store = new ModelSettingsStore(this);
        ModelConfig config = store.load();
        ui = new ProductUi(this);
        LinearLayout layout = ui.page();
        form = layout;
        layout.setFocusableInTouchMode(true);
        layout.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        ui.backRow(layout, this::finish);
        ui.heading(layout, "AI 服务", 26);
        ui.text(layout, "可稍后设置，不影响本机识别。", 14, ProductUi.MUTED);
        ui.text(layout, "使用你信任的服务生成分析和回复。保存不会上传聊天；只有下方单独同意后才开启，可能产生费用。密钥仅加密保存在本机。", 12, ProductUi.WEAK);
        // 连接状态行：只反映本机已保存的地址/模型/密钥状态；「已保存配置」不代表已通过连通检测。
        LinearLayout connection = ui.card(layout);
        connection.setBackground(ui.rounded(ProductUi.TINT, 20));
        connection.setOrientation(LinearLayout.HORIZONTAL);
        connection.setGravity(Gravity.CENTER_VERTICAL);
        connection.setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(4));
        TextView stateIcon = new TextView(this);
        stateIcon.setText("✓");
        stateIcon.setTextSize(14);
        stateIcon.setTypeface(null, android.graphics.Typeface.BOLD);
        stateIcon.setTextColor(ProductUi.BRAND);
        connection.addView(stateIcon, new LinearLayout.LayoutParams(-2, -2));
        connectionState = new TextView(this);
        connectionState.setTextSize(14);
        connectionState.setTypeface(ui.medium());
        connectionState.setTextColor(ProductUi.BRAND);
        connectionState.setPadding(ui.dp(8), 0, 0, 0);
        connection.addView(connectionState, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout credentials = ui.card(layout);
        ui.heading(credentials, "模型配置", 16);
        ui.fieldLabel(credentials, "服务地址");
        endpoint = ui.formInput("https://服务商域名/v1/chat/completions", config.endpoint,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, 2048);
        endpoint.setSaveEnabled(false);
        credentials.addView(endpoint, new LinearLayout.LayoutParams(-1, -2));
        ui.fieldLabel(credentials, "模型名称");
        model = ui.formInput("填写服务商提供的模型 ID", config.model,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, 200);
        model.setSaveEnabled(false);
        credentials.addView(model, new LinearLayout.LayoutParams(-1, -2));
        // API 密钥行：标签右侧为「已加密保存」状态胶囊；密钥本身不回显。
        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.setGravity(Gravity.CENTER_VERTICAL);
        ui.fieldLabel(keyRow, "API 密钥");
        TextView keySpacer = new TextView(this);
        keyRow.addView(keySpacer, new LinearLayout.LayoutParams(0, 0, 1));
        keyState = ui.capsule("未保存密钥", false, true);
        keyRow.addView(keyState, new LinearLayout.LayoutParams(-2, -2));
        credentials.addView(keyRow, new LinearLayout.LayoutParams(-1, -2));
        secret = ui.formInput("留空保留已保存的密钥", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 4096);
        secret.setSaveEnabled(false);
        secret.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_ACTION_DONE);
        credentials.addView(secret, new LinearLayout.LayoutParams(-1, -2));
        ui.primaryButton(credentials, "保存配置", this::save);
        // 聊天 AI 分析开关卡：开关只改观感，确认弹窗与修订校验逻辑与原按钮一致。
        LinearLayout cloudCard = ui.card(layout);
        cloudCard.setOrientation(LinearLayout.VERTICAL);
        LinearLayout cloudRow = new LinearLayout(this);
        cloudRow.setOrientation(LinearLayout.HORIZONTAL);
        cloudRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout cloudText = ui.column();
        TextView cloudTitle = new TextView(this);
        cloudTitle.setText("聊天 AI 分析");
        cloudTitle.setTextSize(15);
        cloudTitle.setTypeface(ui.medium());
        cloudTitle.setTextColor(ProductUi.INK);
        cloudText.addView(cloudTitle, new LinearLayout.LayoutParams(-2, -2));
        cloudState = new TextView(this);
        cloudState.setTextSize(12);
        cloudState.setTextColor(ProductUi.WEAK);
        cloudState.setPadding(0, ui.dp(2), 0, 0);
        cloudText.addView(cloudState, new LinearLayout.LayoutParams(-2, -2));
        cloudRow.addView(cloudText, new LinearLayout.LayoutParams(0, -2, 1));
        cloud = new Switch(this);
        ui.styleSwitch(cloud);
        cloudRow.addView(cloud, new LinearLayout.LayoutParams(-2, -2));
        cloudCard.addView(cloudRow, new LinearLayout.LayoutParams(-1, -2));
        TextView cloudDesc = new TextView(this);
        cloudDesc.setText("分析当前会话以生成建议，上传需单独同意。");
        cloudDesc.setTextSize(12);
        cloudDesc.setTextColor(ProductUi.WEAK);
        cloudDesc.setPadding(0, ui.dp(6), 0, 0);
        cloudCard.addView(cloudDesc, new LinearLayout.LayoutParams(-1, -2));
        cloud.setOnCheckedChangeListener((button, checked) -> {
            if (cloudSync) return;
            if (checked != store.cloudAllowed()) toggleCloud();
            syncCloudSwitch();
        });
        status = label(layout, "", 13);
        status.setTextColor(ProductUi.WEAK);
        LinearLayout advanced = ui.column();
        ui.tintButton(layout, "高级选项与连接检测", () -> {
            boolean opening = advanced.getVisibility() != View.VISIBLE;
            advanced.setVisibility(opening ? View.VISIBLE : View.GONE);
        });
        layout.addView(advanced);
        advanced.setVisibility(View.GONE);
        timeout = input(advanced, "等待时限（5–120 秒）", "30", Integer.toString(config.timeoutSeconds),
                InputType.TYPE_CLASS_NUMBER, 3);
        tokens = input(advanced, "输出 token 上限（128–4096）", "1024", Integer.toString(config.outputTokens),
                InputType.TYPE_CLASS_NUMBER, 4);
        jsonMode = new CheckBox(this);
        jsonMode.setText("请求 JSON 模式（接口不支持时可关闭）");
        jsonMode.setChecked(config.jsonMode);
        advanced.addView(jsonMode);
        completionTokens = new CheckBox(this);
        completionTokens.setText("使用 max_completion_tokens（默认 max_tokens）");
        completionTokens.setChecked(config.completionTokens);
        advanced.addView(completionTokens);
        partialAnalysis = new CheckBox(this);
        partialAnalysis.setText("有遮挡时，仅分析未遮挡部分");
        partialAnalysis.setChecked(store.partialAnalysisAllowed());
        advanced.addView(partialAnalysis);
        label(advanced, "默认关闭，可能遗漏关键回复。浮窗及邻近气泡仍不读取，标题或输入区被遮挡时仍暂停；开启不会授权聊天上传。", 14);
        label(advanced, "支持 OpenAI 兼容 Chat Completions 接口，不支持 Responses、Claude Messages、流式或自定义鉴权。只接受 HTTPS，不跟随重定向；失败不自动重试。", 14);
        button(advanced, "保存高级选项", this::save);
        test = button(advanced, "检测连接（发送虚构对话）", this::confirmTest);
        button(advanced, "取消检测并清空结果", () -> cancel("已取消并清空；如果请求已发出，数据无法撤回"));
        result = label(advanced, "", 15);
        result.setSaveEnabled(false);
        button(advanced, "删除已保存的密钥", () -> new AlertDialog.Builder(this)
                .setTitle("删除本机密钥？").setMessage("保留地址和模型配置；以后使用需重新填写密钥。不会在服务商处撤销密钥。")
                .setNegativeButton("取消", null).setPositiveButton("删除", (dialog, which) -> clearKey()).show());
        label(advanced, "服务商的数据留存策略需自行核实。识别 SDK 与模型接口独立：ML Kit 可能发送设备、应用及性能统计，但不向 Google 上传输入图片或识别文字。", 14);
        TextView footer = new TextView(this);
        footer.setText("密钥仅加密保存在本机，不会上传。");
        footer.setTextSize(12);
        footer.setTextColor(ProductUi.WEAK);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, ui.dp(16), 0, 0);
        layout.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        updateKeyState();
    }

    private TextView label(LinearLayout layout, String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(ProductUi.MUTED);
        view.setPadding(0, ui.dp(8), 0, ui.dp(8));
        layout.addView(view);
        return view;
    }

    private EditText input(LinearLayout layout, String title, String hint, String value, int type, int maximum) {
        ui.fieldLabel(layout, title);
        EditText field = ui.formInput(hint, value, type, maximum);
        field.setSaveEnabled(false);
        layout.addView(field, new LinearLayout.LayoutParams(-1, -2));
        return field;
    }

    private Button button(LinearLayout layout, String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(14);
        button.setTypeface(ui.medium());
        button.setAllCaps(false);
        button.setTextColor(ProductUi.BRAND);
        button.setMinHeight(ui.dp(44));
        button.setMinimumHeight(ui.dp(44));
        button.setPadding(ui.dp(12), 0, ui.dp(12), 0);
        button.setStateListAnimator(null);
        button.setBackground(ui.rounded(ProductUi.TINT, 12));
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = ui.dp(8);
        layout.addView(button, params);
        return button;
    }

    private ModelConfig readFields() {
        try {
            return new ModelConfig(endpoint.getText().toString(), model.getText().toString(),
                    Integer.parseInt(timeout.getText().toString()), Integer.parseInt(tokens.getText().toString()),
                    jsonMode.isChecked(), completionTokens.isChecked());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("请填写有效的超时秒数和输出 token 上限");
        }
    }

    private boolean save() {
        form.requestFocus();
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(form.getWindowToken(), 0);
        cancel("");
        try {
            store.save(readFields(), secret.getText().toString().trim(), partialAnalysis.isChecked());
            secret.getText().clear();
            updateKeyState();
            notice("配置已保存，未调用模型；真实聊天云端分析已关闭，开启需单独确认");
            return true;
        } catch (IllegalArgumentException exception) {
            notice(exception.getMessage());
        } catch (GeneralSecurityException exception) {
            notice("系统密钥库不可用，未保存新密钥；请重试或删除旧密钥后重新填写");
        } catch (IOException exception) {
            notice("本机保存失败，配置未确认写入；请重试");
        }
        return false;
    }

    private void clearKey() {
        cancel("");
        secret.getText().clear();
        try {
            store.clearKey();
            updateKeyState();
            notice("本机密钥已删除；没有发出请求");
        } catch (IOException exception) {
            notice("删除失败，请重试；必要时在服务商后台撤销密钥");
        }
    }

    private void updateKeyState() {
        // 密钥胶囊与连接行只反映本机保存状态；不回显密钥，也不声称已完成连通检测。
        keyState.setText(store.hasKey() ? "已加密保存" : "未保存密钥");
        keyState.setTextColor(store.hasKey() ? ProductUi.BRAND : ProductUi.MUTED);
        keyState.setBackground(ui.rounded(store.hasKey() ? ProductUi.TINT : ProductUi.TINT_NEUTRAL, 999));
        String endpointText = endpoint.getText().toString().trim();
        String modelText = model.getText().toString().trim();
        boolean saved = !endpointText.isEmpty() && !modelText.isEmpty();
        connectionState.setText(saved ? "已保存配置 · " + modelText : "未配置服务");
        connectionState.setTextColor(saved ? ProductUi.BRAND : ProductUi.MUTED);
        syncCloudSwitch();
    }

    /** 程序化同步开关状态；cloudSync 防止 setChecked 触发确认弹窗链路。 */
    private void syncCloudSwitch() {
        if (cloud == null || cloudState == null) return;
        cloudSync = true;
        cloud.setChecked(store.cloudAllowed());
        cloudSync = false;
        cloudState.setText(store.cloudAllowed() ? "已允许；助手开启时生效" : "未允许上传");
    }

    private void toggleCloud() {
        if (store.cloudAllowed()) {
            boolean saved = store.setCloudAllowed(false, store.revision());
            AssistantState.clearAnalysis("云端分析已关闭，旧结果清空");
            updateKeyState();
            notice(saved ? "云端分析已关闭；已发出的数据无法撤回" : "关闭未确认保存，请停止聊天辅助后重试");
            return;
        }
        if (!save()) {
            return;
        }
        try {
            ModelConfig config = store.load();
            config.validate(true);
            if (!store.hasKey()) {
                notice("尚未保存 API 密钥；没有开启云端分析");
                return;
            }
            long expectedRevision = store.revision();
            new AlertDialog.Builder(this).setTitle("允许真实聊天文字发往这个接口？")
                    .setMessage("完整接口：" + config.endpoint + "\n模型：" + config.model
                            + "\n\n开启后，助手在当前微信聊天确认稳定基线或新增可见内容时自动分析；每次最多 20 条、6000 字符。发送正文、气泡方向和 OCR 风险标记，不发送图片、聊天标题或历史数据库。未解析表情只有占位，不推断含义。"
                            + "\n若你另行在当前文字快照允许使用已确认记忆，会额外发送对应背景/偏好/事项（合计最多 2000 字符），不发送本机档案别名或标识字段。记忆只本机保存不等于允许给 AI 使用，文字变化会撤回该次记忆授权。"
                            + (store.partialAnalysisAllowed() ? "\n已选择允许局部分析：有正文浮窗时只发送未遮挡部分和遮挡计数，结果可能遗漏关键上下文。"
                            : "\n局部分析未允许：有正文浮窗时暂停云端分析。")
                            + "\n\n可能产生费用。群聊成员身份与 OCR 结果可能不准；服务商的留存策略由其决定。切换会话、排除或暂停会取消后续处理，但已发送的数据无法撤回。保存配置、更换或删除密钥会关闭此开关。不会自动发送微信回复。")
                    .setNegativeButton("保持关闭", null).setPositiveButton("明确同意并开启", (dialog, which) -> {
                        boolean saved = store.setCloudAllowed(true, expectedRevision);
                        updateKeyState();
                        notice(saved ? "已开启；回到当前微信聊天并运行助手后生效" : "配置已变化或保存失败，没有开启；请重新检查");
                    }).show();
        } catch (IllegalArgumentException exception) {
            notice(exception.getMessage());
        }
    }

    private void confirmTest() {
        if (!save()) {
            return;
        }
        try {
            ModelConfig config = store.load();
            config.validate(true);
            if (!store.hasKey()) {
                notice("尚未保存 API 密钥；没有发出请求");
                return;
            }
            new AlertDialog.Builder(this).setTitle("发送虚构对话测试？")
                    .setMessage("完整接口：" + config.endpoint + "\n模型：" + config.model
                            + "\n会发送模型 ID、密钥和以下两条虚构文本，不读取微信：\n\n对方：预算只有八千，可以先确认必要功能吗？\n自己：可以，我们先列出范围再核价。\n\n可能产生少量费用；不会开启真实聊天上传。")
                    .setNegativeButton("取消", null).setPositiveButton("发送测试", (dialog, which) -> beginTest(config)).show();
        } catch (IllegalArgumentException exception) {
            notice(exception.getMessage());
        }
    }

    private void beginTest(ModelConfig config) {
        cancel("");
        int sequence = ++revision;
        ChatCompletionsClient client = new ChatCompletionsClient();
        active = client;
        test.setEnabled(false);
        notice("正在测试固定虚构对话；没有读取或发送微信内容…");
        task = worker.submit(() -> {
            String output = "";
            String notice;
            try {
                String credential = store.readKey();
                AnalysisResult response = client.analyze(config, credential, Arrays.asList(
                        new ChatMessage(false, "预算只有八千，可以先确认必要功能吗？", 0),
                        new ChatMessage(true, "可以，我们先列出范围再核价。", 0)));
                output = response.display();
                notice = "接口与结构化分析格式通过；以下仅是虚构对话的模型输出，不代表真实聊天质量";
            } catch (GeneralSecurityException exception) {
                notice = "无法解密已保存密钥，请删除后重新填写；没有发送聊天内容";
            } catch (IOException exception) {
                notice = exception.getMessage();
            } catch (IllegalArgumentException exception) {
                notice = "配置无效，请重新检查地址、模型与密钥";
            } catch (RuntimeException exception) {
                notice = "接口测试未完成，响应不可用；未自动重试";
            }
            String finalOutput = output;
            String finalNotice = notice;
            handler.post(() -> {
                if (sequence != revision || isFinishing() || isDestroyed()) {
                    return;
                }
                active = null;
                task = null;
                test.setEnabled(true);
                result.setText(finalOutput);
                notice(finalNotice);
            });
        });
    }

    private void cancel(String notice) {
        revision++;
        if (active != null) {
            active.cancel();
            active = null;
        }
        if (task != null) {
            task.cancel(true);
            task = null;
        }
        if (test != null) {
            test.setEnabled(true);
        }
        if (result != null) {
            result.setText("");
        }
        if (status != null && !notice.isEmpty()) {
            status.setText(notice);
        }
    }

    private void notice(String text) {
        status.setText(text);
    }

    @Override
    protected void onStop() {
        cancel("页面离开后测试已取消并清空；已发送的数据无法撤回");
        if (secret != null) {
            secret.getText().clear();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        cancel("");
        worker.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
