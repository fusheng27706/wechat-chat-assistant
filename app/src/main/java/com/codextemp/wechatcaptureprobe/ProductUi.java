package com.codextemp.wechatcaptureprobe;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 定稿设计系统（2026-10-29 UI重设计定稿）的原生实现。
 * 色板、圆角、字号与组件只在此处定义，页面不得自行发明样式。
 * 字体基线为 Noto Sans SC / Inter；未内置字体文件，中文走系统默认 CJK 字体，
 * 数字与英文以 sans-serif 呈现（见交付说明的降级项）。
 */
final class ProductUi {
    // 定稿色板：墨绿→湖青渐变只用于主按钮与 AI 元素；文字分墨/次/弱三级。
    static final int BRAND = rgb(0x0E, 0x7A, 0x55);
    static final int TEAL = rgb(0x0F, 0xB0, 0xA5);
    static final int INK = rgb(0x14, 0x26, 0x1F);
    static final int MUTED = rgb(0x5C, 0x6B, 0x64);
    static final int WEAK = rgb(0x8C, 0x99, 0x93);
    static final int PAGE = rgb(0xF3, 0xF5, 0xF4);
    static final int DIVIDER = rgb(0xEE, 0xF1, 0xF0);
    static final int CARD = Color.WHITE;
    // 品牌浅底：状态胶囊、编号块、图标底使用的品牌淡绿。
    static final int TINT = rgb(0xE4, 0xF2, 0xEB);
    // 中性胶囊底（预览版等弱标签）。
    static final int TINT_NEUTRAL = rgb(0xE9, 0xEC, 0xEA);
    // 输入框浅灰底。
    static final int FIELD = rgb(0xEF, 0xF2, 0xF1);
    // 开关未选中轨道灰。
    static final int TRACK_OFF = rgb(0xC7, 0xD0, 0xCB);
    // 复制成功反馈的淡绿底。
    static final int COPIED = rgb(0xE4, 0xF2, 0xEB);
    // 旧常量名保留，映射到新色板，避免历史页面（排除规则、记忆）脱离基线。
    static final int GREEN = BRAND;
    static final int PAPER = PAGE;

    private final Activity activity;

    ProductUi(Activity activity) {
        this.activity = activity;
        activity.getWindow().setStatusBarColor(PAGE);
        activity.getWindow().setNavigationBarColor(PAGE);
        activity.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    private static int rgb(int red, int green, int blue) {
        return Color.rgb(red, green, blue);
    }

    int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    /** 定稿中文 Medium 字重；系统无该字族时回退默认字体。 */
    Typeface medium() {
        return Build.VERSION.SDK_INT >= 21 ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT;
    }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    LinearLayout page() {
        LinearLayout content = column();
        content.setPadding(dp(20), dp(16), dp(20), dp(32));
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAGE);
        scroll.addView(content);
        activity.setContentView(scroll);
        return content;
    }

    TextView text(LinearLayout parent, String value, int size, int color) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setLineSpacing(dp(3), 1);
        text.setPadding(0, dp(6), 0, dp(6));
        parent.addView(text, new LinearLayout.LayoutParams(-1, -2));
        return text;
    }

    TextView heading(LinearLayout parent, String value, int size) {
        TextView text = text(parent, value, size, INK);
        text.setTypeface(null, Typeface.BOLD);
        return text;
    }

    /** 分组小标签：定稿 12sp 弱色，如「按需设置」「使用帮助」。 */
    TextView sectionLabel(LinearLayout parent, String value) {
        TextView label = text(parent, value, 12, WEAK);
        label.setPadding(dp(4), dp(14), dp(4), dp(6));
        return label;
    }

    /** 20dp 圆角白卡；16–20dp 内边距，卡片间距 14dp。 */
    LinearLayout card(LinearLayout parent) {
        return card(parent, CARD);
    }

    LinearLayout card(LinearLayout parent, int color) {
        LinearLayout card = column();
        card.setPadding(dp(18), dp(16), dp(18), dp(18));
        card.setBackground(rounded(color, 20));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(14);
        parent.addView(card, params);
        return card;
    }

    /** 墨绿→湖青主按钮：48dp 高、14dp 圆角、白字 Medium。 */
    Button primaryButton(LinearLayout parent, String title, Runnable action) {
        Button button = new Button(activity);
        button.setText(title);
        button.setTextSize(16);
        button.setTypeface(medium());
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setStateListAnimator(null);
        button.setBackground(brandGradient(14));
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.topMargin = dp(12);
        parent.addView(button, params);
        return button;
    }

    /** 次级按钮：品牌浅底 + 墨绿字，用于收起区入口等非主操作。 */
    Button tintButton(LinearLayout parent, String title, Runnable action) {
        Button button = new Button(activity);
        button.setText(title);
        button.setTextSize(15);
        button.setTypeface(medium());
        button.setAllCaps(false);
        button.setTextColor(BRAND);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setStateListAnimator(null);
        button.setBackground(rounded(TINT, 14));
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.topMargin = dp(10);
        parent.addView(button, params);
        return button;
    }

    /** 墨绿→湖青水平渐变背景。 */
    GradientDrawable brandGradient(int radiusDp) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{BRAND, TEAL});
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    GradientDrawable roundedStroke(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable drawable = rounded(color, radiusDp);
        drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    /**
     * 46×28dp 开关：选中轨道品牌绿、未选中灰，白色 22dp 圆钮。
     * 仅改观感；选中状态与业务回调仍由页面逻辑控制。
     */
    void styleSwitch(Switch toggle) {
        GradientDrawable trackOn = rounded(BRAND, 14);
        GradientDrawable trackOff = rounded(TRACK_OFF, 14);
        StateListDrawable track = new StateListDrawable();
        track.addState(new int[]{android.R.attr.state_checked}, trackOn);
        track.addState(new int[]{}, trackOff);
        GradientDrawable thumb = rounded(Color.WHITE, 11);
        thumb.setSize(dp(22), dp(22));
        if (Build.VERSION.SDK_INT >= 23) {
            toggle.setTrackDrawable(track);
            toggle.setThumbDrawable(thumb);
        }
        toggle.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
    }

    /**
     * 色点 + 胶囊标签：品牌浅底绿字（如「● 运行中」「AI 分析」）。
     * neutral 时使用灰底弱字（如「预览版」）。
     */
    TextView capsule(String label, boolean withDot, boolean neutral) {
        TextView capsule = new TextView(activity);
        capsule.setText(withDot ? "●  " + label : label);
        capsule.setTextSize(11);
        capsule.setTypeface(medium());
        capsule.setTextColor(neutral ? MUTED : BRAND);
        capsule.setPadding(dp(10), dp(4), dp(10), dp(4));
        capsule.setBackground(rounded(neutral ? TINT_NEUTRAL : TINT, dp(999)));
        return capsule;
    }

    /** 顶部品牌栏：渐变圆角图标 + 应用名 + 右侧弱胶囊。 */
    LinearLayout brandBar(LinearLayout parent, String title, String badge) {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = new TextView(activity);
        icon.setText("✦");
        icon.setTextSize(16);
        icon.setTextColor(Color.WHITE);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(brandGradient(10));
        bar.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView name = new TextView(activity);
        name.setText(title);
        name.setTextSize(15);
        name.setTypeface(medium());
        name.setTextColor(INK);
        name.setPadding(dp(10), 0, 0, 0);
        bar.addView(name, new LinearLayout.LayoutParams(-2, -2));
        TextView weight = new TextView(activity);
        weight.setText("");
        bar.addView(weight, new LinearLayout.LayoutParams(0, 0, 1));
        if (badge != null && !badge.isEmpty()) {
            bar.addView(capsule(badge, false, true));
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(4);
        params.bottomMargin = dp(6);
        parent.addView(bar, params);
        return bar;
    }

    /** 返回行：白色圆形返回钮 + 「返回」。 */
    LinearLayout backRow(LinearLayout parent, Runnable action) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(6), dp(2), dp(6));
        TextView circle = new TextView(activity);
        circle.setText("‹");
        circle.setTextSize(18);
        circle.setTypeface(null, Typeface.BOLD);
        circle.setTextColor(INK);
        circle.setGravity(Gravity.CENTER);
        circle.setBackground(rounded(CARD, 18));
        circle.setOnClickListener(view -> action.run());
        row.addView(circle, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView label = new TextView(activity);
        label.setText("返回");
        label.setTextSize(14);
        label.setTextColor(MUTED);
        label.setPadding(dp(12), 0, 0, 0);
        label.setOnClickListener(view -> action.run());
        row.addView(label, new LinearLayout.LayoutParams(-2, -2));
        row.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(4);
        parent.addView(row, params);
        return row;
    }

    /**
     * 设计稿列表行：34dp 圆角图标 + 标题(Medium 15sp) + 说明(12sp) + 右箭头。
     * glyph 为单字符文本图标；desc 为 null 时只显示标题。
     */
    LinearLayout listRow(LinearLayout parent, String glyph, int glyphColor, int iconBackground,
                         String title, String desc, Runnable action) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(11), dp(4), dp(11));
        row.setOnClickListener(view -> {
            if (action != null) action.run();
        });
        TextView icon = new TextView(activity);
        icon.setText(glyph);
        icon.setTextSize(15);
        icon.setTypeface(medium());
        icon.setTextColor(glyphColor);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(iconBackground, 10));
        row.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout textColumn = column();
        textColumn.setPadding(dp(12), 0, 0, 0);
        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextSize(15);
        titleView.setTypeface(medium());
        titleView.setTextColor(INK);
        textColumn.addView(titleView, new LinearLayout.LayoutParams(-2, -2));
        if (desc != null && !desc.isEmpty()) {
            TextView descView = new TextView(activity);
            descView.setText(desc);
            descView.setTextSize(12);
            descView.setTextColor(WEAK);
            descView.setPadding(0, dp(2), 0, 0);
            textColumn.addView(descView, new LinearLayout.LayoutParams(-2, -2));
        }
        row.addView(textColumn, new LinearLayout.LayoutParams(0, -2, 1));
        TextView chevron = new TextView(activity);
        chevron.setText("›");
        chevron.setTextSize(16);
        chevron.setTextColor(WEAK);
        chevron.setPadding(dp(8), 0, dp(4), 0);
        row.addView(chevron, new LinearLayout.LayoutParams(-2, -2));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    /** 列表行之间的 1dp 分割线，与图标左缘对齐。 */
    void divider(LinearLayout parent) {
        View line = new View(activity);
        line.setBackgroundColor(DIVIDER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        params.leftMargin = dp(50);
        parent.addView(line, params);
    }

    /** 步骤行：浅绿编号块 + 说明文字，用于首页「用起来，很简单」。 */
    LinearLayout stepRow(LinearLayout parent, String number, String description) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(8), dp(2), dp(8));
        TextView chip = new TextView(activity);
        chip.setText(number);
        chip.setTextSize(12);
        chip.setTypeface(medium());
        chip.setTextColor(BRAND);
        chip.setGravity(Gravity.CENTER);
        chip.setBackground(rounded(TINT, 8));
        row.addView(chip, new LinearLayout.LayoutParams(dp(30), dp(24)));
        TextView label = new TextView(activity);
        label.setText(description);
        label.setTextSize(14);
        label.setTextColor(INK);
        label.setPadding(dp(12), 0, 0, 0);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    /** 表单输入框：浅灰底 12dp 圆角，15sp 墨字。 */
    EditText formInput(String hint, String value, int inputType, int maximum) {
        android.text.InputFilter[] filters = maximum > 0
                ? new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(maximum)} : null;
        return formInput(hint, value, inputType, filters);
    }

    EditText formInput(String hint, String value, int inputType, android.text.InputFilter[] filters) {
        EditText field = new EditText(activity);
        field.setSingleLine(true);
        field.setHint(hint);
        field.setHintTextColor(WEAK);
        field.setInputType(inputType);
        if (filters != null) field.setFilters(filters);
        field.setText(value);
        field.setTextSize(15);
        field.setTextColor(INK);
        field.setBackground(rounded(FIELD, 12));
        field.setPadding(dp(12), dp(11), dp(12), dp(11));
        return field;
    }

    /** 表单字段标签：13sp 次级色。 */
    TextView fieldLabel(LinearLayout parent, String title) {
        TextView label = new TextView(activity);
        label.setText(title);
        label.setTextSize(13);
        label.setTextColor(MUTED);
        label.setPadding(dp(2), dp(10), dp(2), dp(6));
        parent.addView(label, new LinearLayout.LayoutParams(-2, -2));
        return label;
    }

    /** 旧按钮 API 保留：历史页面（排除规则、记忆、诊断）继续可用。 */
    Button button(LinearLayout parent, String title, boolean primary, Runnable action) {
        if (primary) return primaryButton(parent, title, action);
        Button button = new Button(activity);
        button.setText(title);
        button.setTextSize(15);
        button.setTypeface(medium());
        button.setAllCaps(false);
        button.setTextColor(BRAND);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setStateListAnimator(null);
        button.setBackground(rounded(TINT, 14));
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.topMargin = dp(8);
        parent.addView(button, params);
        return button;
    }
}
