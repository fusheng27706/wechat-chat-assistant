package com.codextemp.wechatcaptureprobe;

import android.app.Instrumentation;
import android.os.Bundle;

import java.util.Arrays;
import java.util.List;

/** 纯逻辑验证 AnalysisResult.perMessage 解析与边界校验，不启动投射、不读微信。 */
public final class MessageInsightParseSmoke extends Instrumentation {
    private int checks;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private void require(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }

    private String json(String perMessage) {
        return "{\"summary\":\"对方在争取降价。\","
                + "\"intents\":[{\"explanation\":\"争取优惠\",\"level\":\"高\",\"evidence\":[\"八千能做的话\"]}],"
                + "\"replies\":[{\"objective\":\"守价\",\"text\":\"我再核对一下\"},{\"objective\":\"推进\",\"text\":\"我们先对齐范围\"}],"
                + "\"limitations\":[\"价格待确认\",\"上下文不完整\"]"
                + (perMessage == null ? "" : ",\"per_message\":" + perMessage)
                + "}";
    }

    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            List<ChatMessage> messages = Arrays.asList(
                    new ChatMessage(false, "你们这个有点贵，别人家便宜不少。八千能做的话，我今天就定。", 0),
                    new ChatMessage(true, "我理解，我们先把范围对齐。", 0));

            // 正常解析：per_message 含一条 incoming 洞察，evidence 逐字摘自 incoming 正文
            AnalysisResult ok = AnalysisResult.parse(json("[{\"need\":\"争取降价\",\"confidence\":85,"
                    + "\"evidence\":\"八千能做的话\"}]"), messages);
            require(ok.perMessage.size() == 1, "per-message-count");
            require("争取降价".equals(ok.perMessage.get(0).need), "per-message-need");
            require(ok.perMessage.get(0).confidence == 85, "per-message-confidence");
            require("八千能做的话".equals(ok.perMessage.get(0).evidence), "per-message-evidence");
            require(ok.intents.size() == 1 && ok.replies.size() == 2, "legacy-fields-preserved");

            // 缺 per_message 字段 → 空列表（旧协议向后兼容）
            AnalysisResult legacy = AnalysisResult.parse(json(null), messages);
            require(legacy.perMessage.isEmpty(), "missing-per-message-empty");

            // confidence 越界 → 抛异常
            try {
                AnalysisResult.parse(json("[{\"need\":\"降价\",\"confidence\":101,\"evidence\":\"八千能做的话\"}]"), messages);
                require(false, "confidence-over-100-rejected");
            } catch (org.json.JSONException expected) { require(true, "confidence-over-100-rejected"); }

            // confidence 负数 → 抛异常
            try {
                AnalysisResult.parse(json("[{\"need\":\"降价\",\"confidence\":-1,\"evidence\":\"八千能做的话\"}]"), messages);
                require(false, "confidence-negative-rejected");
            } catch (org.json.JSONException expected) { require(true, "confidence-negative-rejected"); }

            // evidence 不是 incoming 原文 → 抛异常（不能编造依据）
            try {
                AnalysisResult.parse(json("[{\"need\":\"降价\",\"confidence\":80,\"evidence\":\"不存在的话\"}]"), messages);
                require(false, "fabricated-evidence-rejected");
            } catch (org.json.JSONException expected) { require(true, "fabricated-evidence-rejected"); }

            // evidence 含客户端占位符 → 抛异常
            try {
                AnalysisResult.parse(json("[{\"need\":\"降价\",\"confidence\":80,\"evidence\":\"[未解析表情/图形]\"}]"), messages);
                require(false, "placeholder-evidence-rejected");
            } catch (org.json.JSONException expected) { require(true, "placeholder-evidence-rejected"); }

            // need 超长（>40）→ 抛异常
            try {
                AnalysisResult.parse(json("[{\"need\":\"" + repeat("长", 50) + "\",\"confidence\":80,\"evidence\":\"八千能做的话\"}]"), messages);
                require(false, "long-need-rejected");
            } catch (org.json.JSONException expected) { require(true, "long-need-rejected"); }

            // 条数超过 incoming 条数 → 抛异常
            try {
                AnalysisResult.parse(json("[{\"need\":\"a\",\"confidence\":1,\"evidence\":\"八千能做的话\"},"
                        + "{\"need\":\"b\",\"confidence\":2,\"evidence\":\"八千能做的话\"}]"), messages);
                require(false, "over-count-rejected");
            } catch (org.json.JSONException expected) { require(true, "over-count-rejected"); }

            // localDemo 的 perMessage 非空（供本地演示展示消息级叠加）
            require(!AnalysisResult.localDemo().perMessage.isEmpty(), "demo-has-per-message");
            require(AnalysisResult.localDemo().simulated, "demo-simulated-flag");

            output.putString("result", "PASS messageInsightParseChecks=" + checks);
        } catch (Throwable failure) {
            output.putString("result", "FAIL messageInsightParseChecks=" + checks
                    + " type=" + failure.getClass().getSimpleName() + " check=" + failure.getMessage());
        }
        finish(output.getString("result").startsWith("PASS") ? -1 : 0, output);
    }

    private static String repeat(String value, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
