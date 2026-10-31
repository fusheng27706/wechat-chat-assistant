package com.codextemp.wechatcaptureprobe;

import java.util.ArrayList;
import java.util.List;

final class MessageTracker {
    enum Kind { BASELINE, KNOWN, APPEND, EARLIER, GAP, AMBIGUOUS }

    static final class Update {
        final int additions;
        final Kind kind;

        Update(int additions, Kind kind) {
            this.additions = additions;
            this.kind = kind;
        }

        String description() {
            switch (kind) {
                case BASELINE: return "当前窗口作为基线，不推断新收到消息";
                case APPEND: return "新增可见 " + additions + " 条；不等于新收到";
                case EARLIER: return "更早的可见内容，不计新增";
                case GAP: return "上下文跳页或 OCR 变化，无法推断新增";
                case AMBIGUOUS: return "重复文字或多重定位，无法推断新增";
                default: return "已见窗口，不重复计数";
            }
        }
    }

    private static final class Alignment {
        final int segment;
        final int offset;
        final int overlap;

        Alignment(int segment, int offset, int overlap) {
            this.segment = segment;
            this.offset = offset;
            this.overlap = overlap;
        }
    }

    private String conversation = "";
    private final List<List<String>> segments = new ArrayList<>();

    Update update(String identity, List<ChatMessage> messages) {
        List<String> keys = new ArrayList<>();
        for (ChatMessage message : messages) {
            keys.add(message.key());
        }
        if (!conversation.equals(identity)) {
            clear();
            conversation = identity;
            remember(keys);
            return new Update(0, Kind.BASELINE);
        }
        if (keys.isEmpty()) {
            return new Update(0, Kind.GAP);
        }
        if (segments.isEmpty()) {
            remember(keys);
            return new Update(0, Kind.BASELINE);
        }
        List<Alignment> candidates = new ArrayList<>();
        int greatestOverlap = 0;
        for (int segment = 0; segment < segments.size(); segment++) {
            List<String> history = segments.get(segment);
            for (int offset = 1 - keys.size(); offset < history.size(); offset++) {
                int beginning = Math.max(0, offset);
                int ending = Math.min(history.size(), offset + keys.size());
                boolean agrees = true;
                for (int index = beginning; index < ending; index++) {
                    if (!history.get(index).equals(keys.get(index - offset))) {
                        agrees = false;
                        break;
                    }
                }
                if (!agrees || ending <= beginning) {
                    continue;
                }
                if (offset >= 0 && offset + keys.size() <= history.size()) {
                    return new Update(0, Kind.KNOWN);
                }
                int overlap = ending - beginning;
                greatestOverlap = Math.max(greatestOverlap, overlap);
                candidates.add(new Alignment(segment, offset, overlap));
            }
        }
        Alignment best = null;
        boolean ambiguous = false;
        for (Alignment candidate : candidates) {
            if (candidate.overlap == greatestOverlap) {
                if (best != null) {
                    ambiguous = true;
                }
                best = candidate;
            }
        }
        if (best == null || ambiguous) {
            remember(keys);
            return new Update(0, best == null ? Kind.GAP : Kind.AMBIGUOUS);
        }
        List<String> history = segments.get(best.segment);
        int beginning = Math.min(0, best.offset);
        int ending = Math.max(history.size(), best.offset + keys.size());
        List<String> merged = new ArrayList<>();
        for (int index = beginning; index < ending; index++) {
            merged.add(index >= 0 && index < history.size() ? history.get(index) : keys.get(index - best.offset));
        }
        int additions = best.offset >= 0 ? Math.max(0, best.offset + keys.size() - history.size()) : 0;
        boolean bridge = false;
        for (Alignment candidate : candidates) {
            if (candidate.segment != best.segment) {
                bridge = true;
                break;
            }
        }
        segments.remove(best.segment);
        remember(merged);
        if (bridge) {
            return new Update(0, Kind.AMBIGUOUS);
        }
        return new Update(additions, additions > 0 ? Kind.APPEND : Kind.EARLIER);
    }

    private void remember(List<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        int beginning = Math.max(0, keys.size() - 256);
        segments.add(new ArrayList<>(keys.subList(beginning, keys.size())));
        int total = 0;
        for (List<String> segment : segments) {
            total += segment.size();
        }
        while (segments.size() > 8 || total > 256) {
            total -= segments.remove(0).size();
        }
    }

    void clear() {
        conversation = "";
        segments.clear();
    }
}
