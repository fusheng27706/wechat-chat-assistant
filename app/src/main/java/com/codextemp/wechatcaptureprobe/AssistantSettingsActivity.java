package com.codextemp.wechatcaptureprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 收纳可选能力；进入设置不改变用户已有的共享或云端许可。 */
public final class AssistantSettingsActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ProductUi ui = new ProductUi(this);
        LinearLayout page = ui.page();
        ui.backRow(page, this::finish);
        ui.heading(page, "设置与帮助", 26);
        ui.text(page, "日常使用只需首页的助手开关。", 14, ProductUi.MUTED);
        ui.sectionLabel(page, "按需设置");
        LinearLayout preferences = ui.card(page);
        ui.listRow(preferences, "✦", ProductUi.BRAND, ProductUi.TINT, "AI 服务",
                "配置模型与连接检测", () -> open(ModelSettingsActivity.class));
        ui.divider(preferences);
        ui.listRow(preferences, "⊘", ProductUi.BRAND, ProductUi.TINT, "不辅助的聊天",
                "选择不生成建议的会话", () -> open(ConversationRulesActivity.class));
        ui.divider(preferences);
        // 记忆入口使用琥珀色图标底，与定稿 03 屏一致。
        ui.listRow(preferences, "❖", rgb(0xB8, 0x6A, 0x1F), rgb(0xFB, 0xF0, 0xE2), "我的记忆",
                "仅保存你确认的信息，聊天中手动关联", () -> open(MemoryActivity.class));
        ui.sectionLabel(page, "使用帮助");
        LinearLayout help = ui.card(page);
        ui.listRow(help, "?", ProductUi.BRAND, ProductUi.TINT, "如何使用与常见问题", null, () -> show("使用助手",
                "开启：在首页打开开关，首次按引导开启辅助服务，并在系统提示中同意屏幕共享。每次新的共享仍需要系统确认。\n\n使用：打开微信聊天，助手在输入栏上方给出回复建议；点一条建议即复制，再到微信粘贴并自行发送。未配置模型时自动显示明确标记的本地固定示例，不调用模型。离开微信、遮挡或无法确认聊天范围时会暂停。\n\n回复：点击建议文本即可复制并替换剪贴板内容；不会自动发送。保留原输入法；只有发现安全可写输入框才可尝试填入。请核对实际聊天对象。\n\n没有分析：未配置 AI 时只做本机识别和固定示例展示；配置后仍需单独允许上传才会调用模型。无需为了本机识别填写密钥。\n\n没有悬浮球：确认辅助服务和共享仍开启，回到无遮挡的微信聊天页；仍无响应时关闭助手后重新开启。预览版的连续使用、键盘场景仍在完善。"));
        ui.divider(help);
        ui.listRow(help, "✓", ProductUi.BRAND, ProductUi.TINT, "隐私与能力说明", null, () -> show("你掌控聊天内容",
                "屏幕图片在本机识别，原图和识别内容不写入文件；关闭助手清空当前内容。AI 上传默认关闭，首页开关不会开启上传。\n\n配置服务并另行同意后，会发送当前可见聊天文字用于分析，可能收费；服务商的数据留存由其决定，已发送数据无法撤回。\n\n按 Google 说明，ML Kit 不向 Google 发送输入图片和识别文字，但可能联网发送设备、应用及性能统计；本机识别不等于完全不联网。\n\n记忆由你手工确认，本机加密保存、不备份；给 AI 使用还需对当前文字快照单独允许。\n\n识别可能出错，表情含义和群成员身份尚未可靠识别。排除按识别到的标题匹配，不是账号级隔离；仍需读取必要标题。AI 解释仅供参考，不能证明对方真实意图。"));
        ui.divider(help);
        ui.listRow(help, "⚙", ProductUi.BRAND, ProductUi.TINT, "开发诊断", null, () -> new AlertDialog.Builder(this)
                .setTitle("打开开发诊断？").setMessage("这里用于单帧和限时采集测试，日常使用不需要。打开页面不会自动开始检测；检测与聊天助手不能同时运行。")
                .setNegativeButton("取消", null).setPositiveButton("打开", (dialog, which) -> open(MainActivity.class)).show());
        // 版本号取自真实包信息，不写死，避免与构建脱节。
        TextView footer = new TextView(this);
        footer.setText("微信聊天助手 · 预览版 v" + versionName());
        footer.setTextSize(12);
        footer.setTextColor(ProductUi.WEAK);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(20), 0, 0);
        page.addView(footer, new LinearLayout.LayoutParams(-1, -2));
    }

    private String versionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            return "";
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int rgb(int red, int green, int blue) {
        return Color.rgb(red, green, blue);
    }

    private void open(Class<?> target) { startActivity(new Intent(this, target)); }
    private void show(String title, String content) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(content).setPositiveButton("知道了", null).show();
    }
}
