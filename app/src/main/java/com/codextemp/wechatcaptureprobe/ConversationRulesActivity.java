package com.codextemp.wechatcaptureprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputFilter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class ConversationRulesActivity extends Activity {
    private ConversationRulesStore store;
    private LinearLayout entries;
    private TextView status;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new ConversationRulesStore(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding * 4);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
        TextView explanation = new TextView(this);
        explanation.setTextSize(17);
        explanation.setText("排除聊天（按 OCR 标题）\n\n规则只保存在本机，可逐条删除。忽略空格、大小写及末尾数字人数括号；同名聊天会一起排除。OCR 错读或标题改名可能漏配，不是微信账号级身份隔离。\n\n已排除聊天仍需共享帧和必要的标题检查；不进行气泡正文 OCR、AI 分析或聊天上传。无法确认标题时也暂停正文处理。请勿把这一功能当作敏感内容隔离保证。");
        layout.addView(explanation);
        EditText title = new EditText(this);
        title.setHint("填写要排除的聊天标题");
        title.setSingleLine();
        title.setSaveEnabled(false);
        title.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        layout.addView(title);
        Button add = new Button(this);
        add.setText("添加排除规则");
        add.setOnClickListener(view -> {
            String value = title.getText().toString();
            String identity = ConversationRulesStore.identity(value);
            if (identity.isEmpty()) {
                status.setText("请填写标题；没有添加规则");
                return;
            }
            new AlertDialog.Builder(this).setTitle("排除这个标题？")
                    .setMessage(identity + "\n同名及数字人数后缀变化的聊天也会排除；本机将保存这条标题规则。")
                    .setNegativeButton("取消", null).setPositiveButton("确认排除", (dialog, which) -> {
                        try {
                            boolean saved = store.add(value);
                            status.setText(saved ? "规则已保存，返回微信后重新确认标题" : "保存失败，请重试");
                            if (saved) {
                                title.getText().clear();
                                AssistantState.invalidate("排除规则变化，重新确认会话");
                            }
                            render();
                        } catch (IllegalArgumentException exception) {
                            status.setText(exception.getMessage());
                        }
                    }).show();
        });
        layout.addView(add);
        status = new TextView(this);
        layout.addView(status);
        entries = new LinearLayout(this);
        entries.setOrientation(LinearLayout.VERTICAL);
        layout.addView(entries);
        Button back = new Button(this);
        back.setText("返回聊天助手");
        back.setOnClickListener(view -> finish());
        layout.addView(back);
        render();
    }

    private void render() {
        entries.removeAllViews();
        for (String entry : store.entries()) {
            Button remove = new Button(this);
            remove.setText("删除规则：" + entry);
            remove.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle("恢复此标题的处理？")
                    .setMessage(entry + "\n仅删除这条本机排除规则，不会开启云端分析或自动发送消息。")
                    .setNegativeButton("取消", null).setPositiveButton("删除规则", (dialog, which) -> {
                        boolean saved = store.remove(entry);
                        status.setText(saved ? "已删除规则" : "删除失败，请重试");
                        if (saved) {
                            AssistantState.invalidate("排除规则变化，重新确认会话");
                        }
                        render();
                    }).show());
            entries.addView(remove);
        }
        if (store.entries().isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("当前没有排除规则");
            entries.addView(empty);
        }
    }
}
