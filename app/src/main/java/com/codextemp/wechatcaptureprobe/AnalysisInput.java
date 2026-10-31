package com.codextemp.wechatcaptureprobe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class AnalysisInput {
    final List<ChatMessage> messages;
    final ConversationContext.Mode mode;
    final MemoryProfile memory;
    final CaptureCoverage coverage;

    AnalysisInput(List<ChatMessage> messages, ConversationContext context) {
        this(messages, context, CaptureCoverage.UNOBSTRUCTED);
    }

    AnalysisInput(List<ChatMessage> messages, ConversationContext context, CaptureCoverage coverage) {
        this(messages, context.mode, context.shareMemory ? context.memory : null, coverage);
    }

    private AnalysisInput(List<ChatMessage> messages, ConversationContext.Mode mode, MemoryProfile memory, CaptureCoverage coverage) {
        if (coverage == null) {
            throw new IllegalArgumentException("Capture coverage required");
        }
        this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
        this.mode = mode;
        this.memory = memory;
        this.coverage = coverage;
    }

    AnalysisInput withMessages(List<ChatMessage> messages) {
        return new AnalysisInput(messages, mode, memory, coverage);
    }

    static AnalysisInput generic(List<ChatMessage> messages) {
        return new AnalysisInput(messages, ConversationContext.empty());
    }
}
