package com.codextemp.wechatcaptureprobe;

final class ConversationContext {
    enum Mode {
        UNKNOWN("未确认私聊/群聊"), PRIVATE("私聊"), GROUP("群聊");

        final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    final String sessionToken;
    final Mode mode;
    final MemoryProfile memory;
    final long memoryRevision;
    final boolean shareMemory;

    ConversationContext(String sessionToken, Mode mode, MemoryProfile memory, long memoryRevision, boolean shareMemory) {
        if (mode == null || memory != null && memory.kind != mode) {
            throw new IllegalArgumentException("私聊记忆与群聊记忆不能混用");
        }
        this.sessionToken = sessionToken;
        this.mode = mode;
        this.memory = memory;
        this.memoryRevision = memory == null ? -1 : memoryRevision;
        this.shareMemory = memory != null && shareMemory;
    }

    static ConversationContext empty() {
        return new ConversationContext("", Mode.UNKNOWN, null, -1, false);
    }
}
