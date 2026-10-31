package com.codextemp.wechatcaptureprobe;

import java.util.UUID;

final class MemoryProfile {
    final String id;
    final ConversationContext.Mode kind;
    final String label;
    final String relationship;
    final String style;
    final String facts;

    MemoryProfile(String id, ConversationContext.Mode kind, String label, String relationship, String style, String facts) {
        if (id == null || !UUID.fromString(id).toString().equals(id)
                || kind == null || kind == ConversationContext.Mode.UNKNOWN) {
            throw new IllegalArgumentException("记忆标识或私聊/群聊类型无效");
        }
        this.id = id;
        this.kind = kind;
        this.label = checked(label, 80);
        this.relationship = checked(relationship, 400);
        this.style = checked(style, 400);
        this.facts = checked(facts, 1200);
        if (this.label.isEmpty() || (this.relationship + this.style + this.facts).isEmpty()) {
            throw new IllegalArgumentException("请填写本机别名，以及至少一项已确认背景、偏好或重要事项");
        }
    }

    static MemoryProfile create(ConversationContext.Mode kind, String label, String relationship, String style, String facts) {
        return new MemoryProfile(UUID.randomUUID().toString(), kind, label, relationship, style, facts);
    }

    String review() {
        return "类型：" + kind.label + "\n本机别名：" + label + "\n背景/关系：" + relationship
                + "\n表达偏好：" + style + "\n重要事项：" + facts;
    }

    private static String checked(String value, int maximum) {
        if (value == null || value.length() > maximum || value.matches("(?s).*[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f].*")) {
            throw new IllegalArgumentException("记忆字段过长或含不支持的控制字符，未保存");
        }
        return value.trim();
    }
}
