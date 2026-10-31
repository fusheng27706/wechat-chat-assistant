package com.codextemp.wechatcaptureprobe;

import java.text.Normalizer;
import java.util.Locale;

final class ConversationSession {
    private long revision;
    private long window = -1;
    private long header;
    private boolean observed;
    private String pending = "";
    private String title = "";
    private int confirmations;
    private boolean confirmationFramePrepared;
    private boolean initialHeaderPrepared;

    boolean observe(long currentWindow, long headerFingerprint) {
        if (!observed || window != currentWindow || header != headerFingerprint) {
            close();
            window = currentWindow;
            header = headerFingerprint;
            observed = true;
            return true;
        }
        return false;
    }

    boolean confirm(long ticket, String candidate) {
        if (!observed || ticket != revision) {
            return false;
        }
        String identity = normalize(candidate);
        if (identity.isEmpty() || identity.length() > 80) {
            pending = "";
            title = "";
            confirmations = 0;
            return false;
        }
        if (!identity.equals(pending)) {
            pending = identity;
            confirmations = 0;
            title = "";
        }
        confirmations++;
        if (confirmations >= 2) {
            title = candidate.trim();
        }
        return !title.isEmpty();
    }

    /** 计算缓存不得补足首两次真实标题确认。 */
    boolean confirmed(long ticket, long currentWindow) {
        return current(ticket, currentWindow) && confirmations >= 2 && !title.isEmpty();
    }

    boolean current(long ticket, long currentWindow) {
        return observed && ticket == revision && window == currentWindow;
    }

    /** 只用于提前准备第二张真实画面，不代表标题或正文已获确认。 */
    boolean awaitingConfirmation(long currentWindow) {
        return observed && window == currentWindow && confirmations == 1
                && !pending.isEmpty() && title.isEmpty();
    }

    /** 正文尚在稳定等待时，首张标题可提前一次；仅领取准备资格，不确认会话。 */
    boolean claimInitialHeader(long currentWindow) {
        if (!canPrepareInitialHeader(currentWindow)) return false;
        initialHeaderPrepared = true;
        return true;
    }

    /** 调度只查看资格，不消费额度；真正进入识别仍由claim及完整来源检查决定。 */
    boolean canPrepareInitialHeader(long currentWindow) {
        return observed && window == currentWindow && !initialHeaderPrepared && confirmations == 0
                && pending.isEmpty() && title.isEmpty();
    }

    /** 同一ticket只加速一次真实产帧；失败或零帧不得重新领取机会。 */
    boolean canPrepareConfirmationFrame(long currentWindow) {
        return awaitingConfirmation(currentWindow) && !confirmationFramePrepared;
    }

    boolean claimConfirmationFrame(long currentWindow) {
        if (!canPrepareConfirmationFrame(currentWindow)) return false;
        confirmationFramePrepared = true;
        return true;
    }

    long ticket() {
        return revision;
    }

    String token() {
        return window + ":" + revision;
    }

    void close() {
        suspend();
        observed = false;
    }

    void suspend() {
        revision++;
        confirmationFramePrepared = false;
        initialHeaderPrepared = false;
        pending = "";
        title = "";
        confirmations = 0;
    }

    static String normalize(String title) {
        return Normalizer.normalize(title, Normalizer.Form.NFKC).replaceAll("\\s", "").toLowerCase(Locale.ROOT);
    }
}
