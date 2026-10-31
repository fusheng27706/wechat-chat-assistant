package com.codextemp.wechatcaptureprobe;

import java.net.URI;
import java.net.URISyntaxException;

final class ModelConfig {
    final String endpoint;
    final String model;
    final int timeoutSeconds;
    final int outputTokens;
    final boolean jsonMode;
    final boolean completionTokens;

    ModelConfig(String endpoint, String model, int timeoutSeconds, int outputTokens,
                boolean jsonMode, boolean completionTokens) {
        this.endpoint = endpoint.trim();
        this.model = model.trim();
        this.timeoutSeconds = timeoutSeconds;
        this.outputTokens = outputTokens;
        this.jsonMode = jsonMode;
        this.completionTokens = completionTokens;
    }

    void validate(boolean ready) {
        if (ready || !endpoint.isEmpty()) {
            try {
                URI address = new URI(endpoint);
                if (!"https".equalsIgnoreCase(address.getScheme()) || address.getHost() == null
                        || address.getRawUserInfo() != null || address.getRawQuery() != null
                        || address.getRawFragment() != null || address.getPort() == 0 || address.getPort() > 65535
                        || address.getPath() == null || !address.getPath().endsWith("/chat/completions")) {
                    throw new IllegalArgumentException("请填写完整 HTTPS 接口，路径以 /chat/completions 结尾；不要带密钥、查询参数或片段");
                }
            } catch (URISyntaxException exception) {
                throw new IllegalArgumentException("接口地址格式无效，请检查空格和 HTTPS 地址");
            }
        }
        if ((ready && model.isEmpty()) || model.length() > 200 || model.matches("(?s).*[\\p{Cntrl}].*")) {
            throw new IllegalArgumentException("请填写服务商实际支持的模型 ID（最多 200 字符）");
        }
        if (timeoutSeconds < 5 || timeoutSeconds > 120) {
            throw new IllegalArgumentException("超时应在 5–120 秒之间");
        }
        if (outputTokens < 128 || outputTokens > 4096) {
            throw new IllegalArgumentException("输出上限应在 128–4096 tokens 之间");
        }
    }

    static void validateKey(String key) {
        if (key.length() > 4096 || (!key.isEmpty() && !key.matches("[\\x21-\\x7e]+"))) {
            throw new IllegalArgumentException("密钥不能包含空格、换行或非 ASCII 字符，最多 4096 字符");
        }
    }
}
