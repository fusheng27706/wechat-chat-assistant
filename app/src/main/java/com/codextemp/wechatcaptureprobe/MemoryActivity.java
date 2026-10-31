package com.codextemp.wechatcaptureprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MemoryActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ConfirmedMemoryStore store;
    private LinearLayout form;
    private LinearLayout entries;
    private Spinner kind;
    private EditText label;
    private EditText relationship;
    private EditText style;
    private EditText facts;
    private CheckBox confirmed;
    private TextView status;
    private Button save;
    private String editing = "";
    private long loadedRevision;
    private int sequence;
    private boolean visible;
    private boolean busy;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new ConfirmedMemoryStore(this);
        form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setFocusableInTouchMode(true);
        form.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        form.setPadding(padding, padding, padding, padding * 3);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, 0, 0, padding * 2);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        status = new TextView(this);
        status.setPadding(padding, padding / 2, padding, padding / 2);
        page.addView(status);
        setContentView(page);
        text(form, "用户确认的本机记忆", 24);
        text(form, "只保存你手工确认的关系背景、表达偏好和重要事项，不自动从聊天或模型结果提取。不要粘贴聊天原文、密码或证件信息。私聊和群聊是不同类型的独立档案；别名仅供本机选择，不是微信账号身份。", 15);
        text(form, "整份档案用本机 Keystore 加密，不备份；设备或进程被攻破仍有风险。保存不调用模型。每次回到聊天都要人工关联正确档案，允许本轮云端携带背景还需另行确认。修改/删除会撤销已有本轮关联，已发送给服务商的数据无法撤回。离开页面清理未保存表单。", 14);
        text(form, "档案类型（编辑时不可改变）", 15);
        kind = new Spinner(this);
        kind.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"私聊联系人", "群聊背景"}));
        form.addView(kind);
        label = input("本机档案别名（1–80 字符）", 80, false);
        relationship = input("已确认关系 / 群背景与角色（最多 400）", 400, true);
        style = input("已确认表达偏好（最多 400）", 400, true);
        facts = input("已确认重要事项（最多 1200）", 1200, true);
        confirmed = new CheckBox(this);
        confirmed.setText("我确认以上是已核实的背景/偏好/事项，同意仅保存到本机；不是自动保存聊天记录");
        confirmed.setSaveEnabled(false);
        form.addView(confirmed);
        save = button(form, "核对并保存本机记忆…", this::confirmSave);
        button(form, "新建另一份档案（清空表单）", () -> {
            if (!busy) {
                resetForm();
            }
        });
        button(form, "重新载入已保存记忆", this::reload);
        entries = new LinearLayout(this);
        entries.setOrientation(LinearLayout.VERTICAL);
        form.addView(entries);
        button(form, "删除全部本机记忆…", () -> {
            if (busy) {
                return;
            }
            long revision = store.revision();
            new AlertDialog.Builder(this).setTitle("删除全部本机记忆？")
                    .setMessage("移除所有档案及本轮关联，不删除微信聊天。已发送给服务商的数据不能由本操作撤回；不是安全擦除或远端删除。")
                    .setNegativeButton("取消", null).setPositiveButton("删除全部", (dialog, which) -> mutate(() -> store.clear(revision))).show();
        });
        button(form, "返回聊天助手", this::finish);
    }

    private EditText input(String title, int maximum, boolean multiline) {
        text(form, title, 15);
        EditText field = new EditText(this);
        field.setHint(title);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        field.setSingleLine(!multiline);
        field.setMaxLines(multiline ? 4 : 1);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maximum)});
        field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        field.setSaveEnabled(false);
        form.addView(field);
        return field;
    }

    private void text(LinearLayout layout, String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        layout.addView(view);
    }

    private Button button(LinearLayout layout, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        layout.addView(button);
        return button;
    }

    private void resetForm() {
        editing = "";
        label.getText().clear();
        relationship.getText().clear();
        style.getText().clear();
        facts.getText().clear();
        kind.setEnabled(!busy);
        kind.setSelection(0);
        confirmed.setChecked(false);
        save.setText("核对并保存本机记忆…");
        form.requestFocus();
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(form.getWindowToken(), 0);
    }

    private void setBusy(boolean value) {
        busy = value;
        save.setEnabled(!value);
        label.setEnabled(!value);
        relationship.setEnabled(!value);
        style.setEnabled(!value);
        facts.setEnabled(!value);
        confirmed.setEnabled(!value);
        kind.setEnabled(!value && editing.isEmpty());
    }

    private void confirmSave() {
        if (busy) {
            return;
        }
        if (!confirmed.isChecked()) {
            status.setText("请核对内容并主动勾选确认；没有保存");
            return;
        }
        try {
            ConversationContext.Mode mode = kind.getSelectedItemPosition() == 0 ? ConversationContext.Mode.PRIVATE : ConversationContext.Mode.GROUP;
            MemoryProfile profile = editing.isEmpty() ? MemoryProfile.create(mode, label.getText().toString(), relationship.getText().toString(), style.getText().toString(), facts.getText().toString())
                    : new MemoryProfile(editing, mode, label.getText().toString(), relationship.getText().toString(), style.getText().toString(), facts.getText().toString());
            long revision = loadedRevision;
            form.requestFocus();
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(form.getWindowToken(), 0);
            new AlertDialog.Builder(this).setTitle("确认保存这些已核实的信息？").setMessage(profile.review()
                    + "\n\n仅本机加密保存，不读取微信、不调用模型。以后在当前聊天关联及允许云端携带需另行确认。")
                    .setNegativeButton("继续修改", null).setPositiveButton("确认保存", (dialog, which) -> mutate(() -> store.save(profile, revision, true))).show();
        } catch (IllegalArgumentException exception) {
            status.setText(exception.getMessage());
        }
    }

    private interface Mutation {
        long run() throws GeneralSecurityException, IOException;
    }

    private void mutate(Mutation mutation) {
        if (busy) {
            return;
        }
        setBusy(true);
        int request = ++sequence;
        status.setText("正在处理本机记忆；没有调用模型…");
        worker.submit(() -> {
            String notice;
            try {
                mutation.run();
                notice = "本机操作完成，旧关联已撤销；没有调用模型";
            } catch (GeneralSecurityException exception) {
                notice = "加密记忆或系统密钥不可用，没有自动覆盖。可重试；必要时明确删除全部本机记忆后重建。";
            } catch (IOException | IllegalArgumentException exception) {
                notice = "档案已变化或本机写入未确认，请重新载入检查；没有自动重试或调用模型";
            }
            String result = notice;
            handler.post(() -> {
                if (request != sequence || !visible) {
                    return;
                }
                setBusy(false);
                resetForm();
                status.setText(result);
                loadEntries(false);
            });
        });
    }

    private void reload() {
        if (!busy) {
            resetForm();
            loadEntries(true);
        }
    }

    private void loadEntries(boolean notice) {
        int request = ++sequence;
        setBusy(true);
        if (notice) {
            status.setText("正在解密本机已确认记忆…");
        }
        worker.submit(() -> {
            ConfirmedMemoryStore.Snapshot snapshot = null;
            try {
                snapshot = store.load();
            } catch (GeneralSecurityException | IOException exception) {
                snapshot = null;
            }
            ConfirmedMemoryStore.Snapshot result = snapshot;
            handler.post(() -> {
                if (request != sequence || !visible) {
                    return;
                }
                setBusy(false);
                entries.removeAllViews();
                if (result == null) {
                    loadedRevision = store.revision();
                    status.setText("无法解密已保存记忆；不会当作空档案自动覆盖。可重试或明确删除全部本机记忆。");
                    return;
                }
                loadedRevision = result.revision;
                if (notice) {
                    status.setText("已载入 " + result.profiles.size() + " 份本机档案；没有调用模型");
                }
                if (result.profiles.isEmpty()) {
                    text(entries, "当前没有已保存记忆", 16);
                }
                for (MemoryProfile profile : result.profiles) {
                    button(entries, "查看/编辑：" + profile.kind.label + " · " + profile.label + " · " + profile.id.substring(0, 6), () -> {
                        if (busy) {
                            return;
                        }
                        editing = profile.id;
                        label.setText(profile.label);
                        relationship.setText(profile.relationship);
                        style.setText(profile.style);
                        facts.setText(profile.facts);
                        kind.setSelection(profile.kind == ConversationContext.Mode.PRIVATE ? 0 : 1);
                        kind.setEnabled(false);
                        confirmed.setChecked(false);
                        save.setText("核对并更新这份本机记忆…");
                        status.setText("正在编辑已有档案；保存仍需重新确认。表单在页面上方。");
                    });
                    button(entries, "删除档案：" + profile.kind.label + " · " + profile.label + " · " + profile.id.substring(0, 6), () -> {
                        if (!busy) {
                            new AlertDialog.Builder(this).setTitle("删除这份本机记忆？").setMessage(profile.review()
                                    + "\n\n同时撤销本轮关联，不删除微信聊天，不能撤回已发送给服务商的数据。")
                                    .setNegativeButton("取消", null).setPositiveButton("删除档案", (dialog, which) -> mutate(() -> store.remove(profile.id, result.revision))).show();
                        }
                    });
                }
            });
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        setBusy(false);
        reload();
    }

    @Override
    protected void onStop() {
        visible = false;
        sequence++;
        resetForm();
        entries.removeAllViews();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
