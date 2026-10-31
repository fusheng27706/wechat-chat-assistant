package com.codextemp.wechatcaptureprobe;

import java.text.Normalizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class ChatMessage {
    final boolean outgoing;
    final String text;
    final boolean uncertain;
    final int top;
    // 气泡在屏幕共享帧内的完整几何（left/right/bottom），供消息级就地叠加定位；
    // top 保留为兼容字段，其余字段缺失时回退为 0（表示无可靠几何，不能用于叠加）。
    final int left;
    final int right;
    final int bottom;
    final String warning;
    final long visualFingerprint;

    ChatMessage(boolean outgoing, String text, int top) {
        this(outgoing, text, 0, top, 0, 0, "", 0);
    }

    ChatMessage(boolean outgoing, String text, int top, String warning, long visualFingerprint) {
        this(outgoing, text, 0, top, 0, 0, warning, visualFingerprint);
    }

    /** 携带完整气泡几何的消息；几何仅作就地展示定位，不参与来源许可或指纹。 */
    ChatMessage(boolean outgoing, String text, int left, int top, int right, int bottom,
                String warning, long visualFingerprint) {
        this.outgoing = outgoing;
        this.text = text;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.warning = warning;
        this.visualFingerprint = visualFingerprint;
        uncertain = !warning.isEmpty() || text.matches("(?s).*\\b(?=[A-Za-z0-9]*[A-Za-z])(?=[A-Za-z0-9]*[0-9])[A-Za-z0-9]{2,}\\b.*");
    }

    /** 是否携带可用于消息级叠加的完整气泡几何。 */
    boolean hasGeometry() {
        return right > left && bottom > top;
    }

    String key() {
        // 视觉指纹不参与消息同一性判断：它是对图形区域精确像素的哈希，媒体投影每帧
        // 都带编码噪声，图像类气泡的指纹帧间必抖，会导致 publish 签名永远不一致、
        // 刚落地的分析在约 1 秒内被「当前文字变化」清空、MessageTracker 对齐退化为 GAP。
        // 与 AssistantState.publish 的锚点注释同源：指纹随抗锯齿抖动，不该参与同一条消息判断。
        String normalized = (outgoing ? "SELF:" : "OTHER:") + Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ").trim();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(64);
            for (byte value : digest) {
                output.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
            }
            return output.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
