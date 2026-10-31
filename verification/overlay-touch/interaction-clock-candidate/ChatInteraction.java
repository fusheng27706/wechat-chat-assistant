package com.codextemp.wechatcaptureprobe;

/** 仅用于延后展示，不授予采集或复制权限；不保存事件文本或节点。 */
final class ChatInteraction {
    private static long revision;
    private static long quietUntil;

    static void observed(long now) { observed(now, 0, 0); }

    /** 事件发送使用uptime；只抵扣同一时钟已过去的时间，跨休眠只会保守多等。 */
    static void observed(long now, long uptimeNow, long eventUptime) {
        revision++;
        long remaining=450;
        if(eventUptime>0 && eventUptime<=uptimeNow)remaining-=Math.min(450,uptimeNow-eventUptime);
        long deadline=now>Long.MAX_VALUE-remaining?Long.MAX_VALUE:now+remaining;
        // 乱序旧事件不得缩短较新的交互等待；缺失/未来时间保留原450ms等待。
        quietUntil=Math.max(quietUntil,deadline);
    }

    static long revision() { return revision; }
    static boolean quiet(long now) { return now >= quietUntil; }
    static long quietRemaining(long now) { return Math.max(0, quietUntil - now); }
}
