package com.codextemp.wechatcaptureprobe;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ConversationRulesStore {
    private final SharedPreferences preferences;

    ConversationRulesStore(Context context) {
        preferences = context.getSharedPreferences("assistant", Context.MODE_PRIVATE);
    }

    static String identity(String title) {
        return ConversationSession.normalize(title).replaceFirst("\\([0-9]+\\)$", "");
    }

    boolean excludes(String title) {
        String identity = identity(title);
        for (String entry : preferences.getStringSet("excluded", Collections.emptySet())) {
            if (identity(entry).equals(identity)) {
                return true;
            }
        }
        return false;
    }

    List<String> entries() {
        List<String> entries = new ArrayList<>(preferences.getStringSet("excluded", Collections.emptySet()));
        Collections.sort(entries);
        return entries;
    }

    long revision() {
        return preferences.getLong("rules_revision", 0);
    }

    boolean add(String title) {
        String identity = identity(title);
        if (identity.isEmpty() || identity.length() > 80) {
            throw new IllegalArgumentException("标题应为 1–80 个有效字符");
        }
        Set<String> entries = new HashSet<>(preferences.getStringSet("excluded", Collections.emptySet()));
        if (entries.size() >= 100 && !entries.contains(identity)) {
            throw new IllegalArgumentException("最多保存 100 条排除规则，请先删除不再需要的规则");
        }
        entries.add(identity);
        return write(entries);
    }

    boolean remove(String title) {
        String identity = identity(title);
        Set<String> entries = new HashSet<>(preferences.getStringSet("excluded", Collections.emptySet()));
        entries.removeIf(entry -> identity(entry).equals(identity));
        return write(entries);
    }

    private boolean write(Set<String> entries) {
        return preferences.edit().putStringSet("excluded", entries)
                .putLong("rules_revision", revision() + 1).commit();
    }
}
