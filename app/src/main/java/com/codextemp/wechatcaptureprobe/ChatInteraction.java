package com.codextemp.wechatcaptureprobe;

/** 仅用于延后展示，不授予采集或复制权限；不保存事件文本或节点。 */
final class ChatInteraction {
    private static long revision;
    private static long quietUntil;
    private static long latestEventUptime = -1;

    static void observed(long now) {
        defer(now, 450);
    }

    /** 文字与窗口事件保留完整等待；发送时间仅用于判断收起后是否真的发生了新事件。 */
    static void observedEvent(long now, long uptimeNow, long eventUptime) {
        rememberEvent(uptimeNow, eventUptime);
        observed(now);
    }

    /** 仅滚动抵扣有限的事件传输时间；收到旧事件后仍至少等待350ms，不把迟到事件视为已经安静。 */
    static void observedScroll(long now, long uptimeNow, long eventUptime) {
        rememberEvent(uptimeNow, eventUptime);
        long credit = eventUptime > 0 && uptimeNow >= eventUptime
                ? Math.min(100, uptimeNow - eventUptime) : 0;
        defer(now, 450 - credit);
    }

    private static void rememberEvent(long uptimeNow, long eventUptime) {
        // 无效/未来时间不能触发自动重开；乱序到达不能覆盖已知较新的事件。
        if (eventUptime > 0 && eventUptime <= uptimeNow)
            latestEventUptime = Math.max(latestEventUptime, eventUptime);
    }

    private static void defer(long now, long delay) {
        revision++;
        long deadline = now > Long.MAX_VALUE - delay ? Long.MAX_VALUE : now + delay;
        // 乱序事件不能缩短此前仍有效的等待；期限只使用elapsedRealtime，uptime仅计算有界年龄。
        quietUntil = Math.max(quietUntil, deadline);
    }

    static long revision() { return revision; }
    static long latestEventUptime() { return latestEventUptime; }
    static boolean quiet(long now) { return now >= quietUntil; }
    static long quietRemaining(long now) { return Math.max(0, quietUntil - now); }
}
