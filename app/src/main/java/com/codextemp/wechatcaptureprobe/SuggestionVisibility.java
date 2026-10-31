package com.codextemp.wechatcaptureprobe;

/** 收起改变遮罩后的第一份识别只建立新基线，不能当作新消息重新弹出。 */
final class SuggestionVisibility {
    private boolean dismissed;
    private boolean baselinePending;
    private long interaction;
    private long dismissedAtUptime;
    private long frame;
    private String signature = "";

    void dismiss(long interaction, long frame, long uptime) {
        dismissed = true;
        baselinePending = true;
        this.interaction = interaction;
        dismissedAtUptime = uptime;
        this.frame = frame;
        signature = "";
    }

    void open() { dismissed = false; baselinePending = false; signature = ""; }

    boolean visible(boolean quiet, long interaction, long frame, String signature, boolean valid, boolean appended,
                    long eventUptime) {
        if (!dismissed) return true;
        if (!quiet) return false;
        // 收起前已发送、收起后才到达的事件不是新的用户操作；同一毫秒也保守保持收起。
        if (interaction != this.interaction && eventUptime > dismissedAtUptime) {
            open();
        } else if (valid && frame > this.frame) {
            this.frame = frame;
            if (baselinePending) {
                baselinePending = false;
                this.signature = signature;
            } else if (appended && !this.signature.equals(signature)) {
                open();
            } else {
                this.signature = signature;
            }
        }
        return !dismissed;
    }
}
