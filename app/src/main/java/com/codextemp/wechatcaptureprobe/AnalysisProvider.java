package com.codextemp.wechatcaptureprobe;

import java.io.IOException;
import java.util.List;

interface AnalysisProvider {
    AnalysisResult analyze(ModelConfig config, String credential, List<ChatMessage> messages) throws IOException;

    default AnalysisResult analyze(ModelConfig config, String credential, AnalysisInput input) throws IOException {
        if (input.memory != null || input.mode == ConversationContext.Mode.GROUP || input.coverage.obscured()) {
            throw new IOException("当前分析适配器未实现已确认背景、群聊或遮挡信息，未降级为忽略限制的分析");
        }
        return analyze(config, credential, input.messages);
    }

    void cancel();
}
