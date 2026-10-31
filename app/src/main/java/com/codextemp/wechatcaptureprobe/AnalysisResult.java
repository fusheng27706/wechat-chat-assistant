package com.codextemp.wechatcaptureprobe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class AnalysisResult {
    final boolean simulated;
    final String summary;
    final List<Intent> intents;
    final List<Reply> replies;
    final List<String> limitations;
    final GroupDiscussion discussion;
    // 逐条对方消息的就地叠加判断；为空表示模型未提供（旧协议或本地演示）。
    final List<MessageInsight> perMessage;

    /** 单条对方消息的就地判断：意图/需求摘要 + 0–100 准确率 + 一句依据。 */
    static final class MessageInsight {
        final String need;
        final int confidence;
        final String evidence;

        MessageInsight(String need, int confidence, String evidence) {
            this.need = need;
            this.confidence = confidence;
            this.evidence = evidence;
        }
    }

    static final class GroupDiscussion {
        final String topic;
        final List<Viewpoint> viewpoints;

        GroupDiscussion(String topic, List<Viewpoint> viewpoints) {
            this.topic = topic;
            this.viewpoints = Collections.unmodifiableList(viewpoints);
        }
    }

    static final class Viewpoint {
        final String speaker;
        final String position;
        final List<String> evidence;

        Viewpoint(String speaker, String position, List<String> evidence) {
            this.speaker = speaker;
            this.position = position;
            this.evidence = Collections.unmodifiableList(evidence);
        }
    }

    static final class Intent {
        final String explanation;
        final String level;
        final List<String> evidence;

        Intent(String explanation, String level, List<String> evidence) {
            this.explanation = explanation;
            this.level = level;
            this.evidence = Collections.unmodifiableList(evidence);
        }
    }

    static final class Reply {
        final String objective;
        final String text;
        // 单条建议置信度（0–100）；-1 表示模型未提供。prompt 不要求该字段，
        // 仅在响应附带时透出用于建议面板的把握度展示，缺失时不伪造数值。
        final int confidence;

        Reply(String objective, String text, int confidence) {
            this.objective = objective;
            this.text = text;
            this.confidence = confidence;
        }
    }

    private AnalysisResult(String summary, List<Intent> intents, List<Reply> replies, List<String> limitations, GroupDiscussion discussion) {
        this(summary, intents, replies, limitations, discussion, Collections.emptyList());
    }

    private AnalysisResult(String summary, List<Intent> intents, List<Reply> replies, List<String> limitations,
                           GroupDiscussion discussion, List<MessageInsight> perMessage) {
        this.simulated = false;
        this.summary = summary;
        this.intents = Collections.unmodifiableList(intents);
        this.replies = Collections.unmodifiableList(replies);
        this.limitations = Collections.unmodifiableList(limitations);
        this.discussion = discussion;
        this.perMessage = Collections.unmodifiableList(perMessage);
    }

    /** 固定本地演示，无聊天入参、网络或持久化；不能作为模型判断或聊天证据。 */
    static AnalysisResult localDemo() { return new AnalysisResult(); }

    private AnalysisResult() {
        simulated = true;
        summary = "本地模拟建议：固定示例，不是大模型分析，也不判断对方意图。";
        intents = List.of(new Intent("仅体验选择和确认流程，与聊天内容无关。", "信息不足", List.of()));
        replies = List.of(
                new Reply("简短回应", "收到，我先看一下，稍后回复你。", 92),
                new Reply("确认细节", "方便再说明一下具体要求吗？我确认清楚后回复。", 76),
                new Reply("稍后答复", "我需要一点时间确认，整理好后再联系你。", 61));
        limitations = List.of("固定示例，未调用模型；请自行核对对象和文字，不自动发送。");
        discussion = null;
        // 固定示例仅用于验证消息级叠加的展示形态；与聊天内容无关，也不代表真实判断。
        perMessage = List.of(new MessageInsight("了解具体需求", 82, "仅本地模拟"));
    }

    static AnalysisResult parse(String content, List<ChatMessage> messages) throws JSONException {
        return parse(content, messages, ConversationContext.Mode.UNKNOWN);
    }

    static AnalysisResult parse(String content, List<ChatMessage> messages, ConversationContext.Mode mode) throws JSONException {
        String source = content.trim();
        if (source.startsWith("```json\n") && source.endsWith("```")) {
            source = source.substring(8, source.length() - 3).trim();
        }
        // 模型偶发输出尾逗号（实测 "text":"…",} 会让整条有效结果被丢弃）。解析前先剥掉
        // 对象/数组末尾的悬挂逗号；这只放宽标点容错，不放宽任何字段与内容校验。
        source = source.replaceAll(",\\s*([}\\]])", "$1");
        JSONTokener parser = new JSONTokener(source);
        Object parsed = parser.nextValue();
        if (!(parsed instanceof JSONObject) || parser.nextClean() != 0) {
            throw new JSONException("Invalid analysis object");
        }
        JSONObject result = (JSONObject) parsed;
        String summary = text(result, "summary", 400);
        JSONArray explanations = result.getJSONArray("intents");
        JSONArray suggestions = result.getJSONArray("replies");
        if (explanations.length() < 1 || explanations.length() > 4 || suggestions.length() < 2 || suggestions.length() > 3) {
            throw new JSONException("Invalid result counts");
        }
        List<Intent> intents = new ArrayList<>();
        for (int index = 0; index < explanations.length(); index++) {
            JSONObject item = explanations.getJSONObject(index);
            String explanation = text(item, "explanation", 400);
            String level = text(item, "level", 10);
            if (!level.equals("高") && !level.equals("中") && !level.equals("低") && !level.equals("信息不足")) {
                throw new JSONException("Invalid confidence level");
            }
            List<String> evidence = strings(item.getJSONArray("evidence"), 0, 3, 300);
            if (evidence.isEmpty() && !level.equals("信息不足")) {
                throw new JSONException("Missing evidence");
            }
            // evidence 校验失败的 intent 单独丢弃而不是整份作废：模型偶发把客户端占位符或 OCR 之外
            // 的文字当依据，属单条质量问题；保留其余通过校验的意图，主结论仍然可用且不降低来源标准。
            List<String> verified = new ArrayList<>();
            boolean rejected = false;
            for (String quote : evidence) {
                if (quote.contains("[未解析表情/图形]") || quote.contains("[气泡内容未能识别]")) {
                    rejected = true;
                    break;
                }
                boolean found = false;
                for (ChatMessage message : messages) {
                    if (normalize(message.text).contains(normalize(quote))) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    rejected = true;
                    break;
                }
                verified.add(quote);
            }
            if (rejected) {
                android.util.Log.w("ChatAssistant", "INTENT_DROPPED evidence=" + evidence);
                continue;
            }
            intents.add(new Intent(explanation, level, verified));
        }
        if (intents.isEmpty()) {
            throw new JSONException("All intents evidence rejected");
        }
        List<Reply> replies = new ArrayList<>();
        Set<String> objectives = new HashSet<>();
        Set<String> replyTexts = new HashSet<>();
        for (int index = 0; index < suggestions.length(); index++) {
            JSONObject item = suggestions.getJSONObject(index);
            String objective = text(item, "objective", 60);
            String reply = text(item, "text", 600);
            if (!objectives.add(normalize(objective)) || !replyTexts.add(normalize(reply))) {
                throw new JSONException("Duplicate replies");
            }
            // confidence 为可选容错字段：缺省或越界时按未提供处理（-1），不影响主结果解析。
            int confidence = item.has("confidence") && !item.isNull("confidence")
                    && item.optInt("confidence", -1) >= 0 && item.optInt("confidence", -1) <= 100
                    ? item.optInt("confidence", -1) : -1;
            replies.add(new Reply(objective, reply, confidence));
        }
        GroupDiscussion discussion = null;
        if (mode == ConversationContext.Mode.GROUP) {
            JSONObject group = result.getJSONObject("discussion");
            String topic = text(group, "topic", 400);
            JSONArray positions = group.getJSONArray("viewpoints");
            if (positions.length() < 1 || positions.length() > 6) {
                throw new JSONException("Invalid viewpoint count");
            }
            List<Viewpoint> viewpoints = new ArrayList<>();
            for (int index = 0; index < positions.length(); index++) {
                JSONObject item = positions.getJSONObject(index);
                String speaker = text(item, "speaker", 20);
                if (!speaker.equals("自己") && !speaker.equals("成员未识别")) {
                    throw new JSONException("Unverified member identity");
                }
                List<String> evidence = strings(item.getJSONArray("evidence"), 1, 3, 300);
                for (String quote : evidence) {
                    boolean found = false;
                    for (ChatMessage message : messages) {
                        if (message.outgoing == speaker.equals("自己") && normalize(message.text).contains(normalize(quote))
                                && !quote.contains("[未解析表情/图形]") && !quote.contains("[气泡内容未能识别]")) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        throw new JSONException("Viewpoint evidence direction mismatch");
                    }
                }
                viewpoints.add(new Viewpoint(speaker, text(item, "position", 400), evidence));
            }
            discussion = new GroupDiscussion(topic, viewpoints);
        } else if (result.has("discussion") && !result.isNull("discussion")) {
            throw new JSONException("Unrequested group result");
        }
        // per_message 是可选的展示增强（消息级标注条），不是分析主体；OCR 条数与模型计数天然可能错位
        // （图形气泡、自己发送的消息都会干扰计数）。格式不符时只丢弃标注条，保留 summary/replies 主结果，
        // 避免一个附加字段的整体失败把整份已付费、已完成的模型响应作废。
        List<MessageInsight> perMessage;
        try {
            perMessage = parsePerMessage(result, messages);
        } catch (JSONException failure) {
            // 诊断：定位 per_message 被丢弃的具体原因（条数/字段/置信度），便于核对 prompt 约定。
            android.util.Log.w("ChatAssistant", "PER_MESSAGE_DROPPED " + failure.getMessage()
                    + " incoming=" + messages.stream().filter(message -> !message.outgoing).count()
                    + " provided=" + (result.has("per_message") && !result.isNull("per_message")
                    ? result.optJSONArray("per_message").length() : -1));
            perMessage = Collections.emptyList();
        }
        return new AnalysisResult(summary, intents, replies, strings(result.getJSONArray("limitations"), 1, 6, 300), discussion, perMessage);
    }

    /**
     * 解析可选的 per_message 就地判断。缺字段时返回空列表（旧协议/本地演示兼容），
     * 不因缺少该可选能力而拒绝整份结果；一旦提供则逐项严格校验，避免伪造或越界。
     */
    private static List<MessageInsight> parsePerMessage(JSONObject result, List<ChatMessage> messages) throws JSONException {
        if (!result.has("per_message") || result.isNull("per_message")) {
            return Collections.emptyList();
        }
        JSONArray items = result.getJSONArray("per_message");
        // 对方消息条数上限，避免模型返回无界列表；可见范围内没有对方消息时，空数组是合法输出。
        long incoming = messages.stream().filter(message -> !message.outgoing).count();
        if (items.length() < (incoming == 0 ? 0 : 1) || items.length() > Math.max(1, incoming) || items.length() > 20) {
            throw new JSONException("Invalid per_message count");
        }
        List<MessageInsight> insights = new ArrayList<>();
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.getJSONObject(index);
            String need = text(item, "need", 40);
            int confidence = item.getInt("confidence");
            if (confidence < 0 || confidence > 100) {
                throw new JSONException("Invalid confidence range");
            }
            // evidence 为可选字段：当前 prompt 不再要求逐条输出 evidence 以节省 token；
            // 旧协议若仍提供，则仍需逐字摘自已识别 incoming 原文，不得编造或使用客户端占位符。
            String evidence = item.optString("evidence", "");
            if (!evidence.isEmpty()) {
                if (evidence.contains("[未解析表情/图形]") || evidence.contains("[气泡内容未能识别]")) {
                    throw new JSONException("Client placeholder is not per-message evidence");
                }
                boolean found = false;
                for (ChatMessage message : messages) {
                    if (!message.outgoing && normalize(message.text).contains(normalize(evidence))) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    throw new JSONException("Per-message evidence not in incoming text");
                }
            }
            insights.add(new MessageInsight(need, confidence, evidence));
        }
        return insights;
    }

    String display() {
        StringBuilder output = new StringBuilder(summary).append("\n\n可能解释（不是事实或精确概率）\n");
        if (discussion != null) {
            output.append("群聊话题：").append(discussion.topic).append("\n成员身份未知；观点条数不等于人数。\n");
            for (Viewpoint viewpoint : discussion.viewpoints) {
                output.append(viewpoint.speaker).append("：").append(viewpoint.position).append('\n');
                for (String quote : viewpoint.evidence) {
                    output.append("依据：").append(quote).append('\n');
                }
            }
        }
        for (Intent intent : intents) {
            // 模型常把解释写成完整句子并以句号收尾，直接拼「：置信度」会出现「。：高」双标点。
            String explanation = intent.explanation;
            while (!explanation.isEmpty()
                    && "。．.！!？?".indexOf(explanation.charAt(explanation.length() - 1)) >= 0) {
                explanation = explanation.substring(0, explanation.length() - 1);
            }
            output.append("\n· ").append(explanation).append("：").append(intent.level);
            for (String quote : intent.evidence) {
                output.append("\n  依据：").append(quote);
            }
            output.append('\n');
        }
        output.append("\n回复方向（测试展示，不会填入或发送）\n");
        for (Reply reply : replies) {
            output.append("\n· ").append(reply.objective).append("\n").append(reply.text).append('\n');
        }
        output.append("\n限制\n");
        for (String limitation : limitations) {
            output.append("· ").append(limitation).append('\n');
        }
        return output.toString();
    }

    private static String text(JSONObject object, String field, int limit) throws JSONException {
        Object value = object.get(field);
        if (!(value instanceof String)) {
            throw new JSONException("Expected text");
        }
        return checked((String) value, limit);
    }

    private static String checked(String value, int limit) throws JSONException {
        String result = value.trim();
        if (result.isEmpty() || result.length() > limit || result.matches("(?s).*[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f].*")) {
            throw new JSONException("Invalid text length or characters");
        }
        return result;
    }

    private static List<String> strings(JSONArray values, int minimum, int maximum, int length) throws JSONException {
        if (values.length() < minimum || values.length() > maximum) {
            throw new JSONException("Invalid list length");
        }
        List<String> result = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            Object value = values.get(index);
            if (!(value instanceof String)) {
                throw new JSONException("Expected text list");
            }
            result.add(checked((String) value, length));
        }
        return result;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).replaceAll("\\s", "");
    }
}
