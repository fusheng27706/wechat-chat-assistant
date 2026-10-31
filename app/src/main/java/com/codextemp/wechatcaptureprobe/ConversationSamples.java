package com.codextemp.wechatcaptureprobe;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class ConversationSamples {
    private static final String[] BODIES = {
            "预算最多8000元，能做吗？",
            "可以先确认必须交付的功能。",
            "希望下周五18:30前上线，别后面再加钱。",
            "基础版8000元，额外需求另行确认。",
            "那先做安卓版本，iOS以后再说。",
            "好的，今天发范围清单，你确认后排期。",
            "行，我再跟同事商量一下，不是现在就定。",
            "明白，你们周三中午前反馈就好"
    };

    public static Set<String> matchedCodes(String text) {
        String normalized = normalize(text);
        Set<String> matches = new LinkedHashSet<>();
        for (int index = 0; index < BODIES.length; index++) {
            if (normalized.contains(normalize(BODIES[index]))) {
                matches.add(String.format(Locale.ROOT, "T%02d", index + 1));
            }
        }
        return matches;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("[\\p{P}\\p{Z}\\p{S}\\s]", "").toLowerCase(Locale.ROOT);
    }

    private ConversationSamples() {
    }
}
