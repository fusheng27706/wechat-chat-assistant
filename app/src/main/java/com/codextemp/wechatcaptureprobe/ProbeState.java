package com.codextemp.wechatcaptureprobe;

import android.graphics.Bitmap;

final class ProbeState {
    static final class Result {
        final Bitmap image;
        final String summary;
        final String lines;
        final int lineCount;
        final boolean contentUnavailable;

        Result(Bitmap image, String summary, String lines, int lineCount, boolean contentUnavailable) {
            this.image = image;
            this.summary = summary;
            this.lines = lines;
            this.lineCount = lineCount;
            this.contentUnavailable = contentUnavailable;
        }
    }

    private static volatile String status = "准备就绪，尚未采集";
    private static volatile Result result;

    static String status() {
        return status;
    }

    static Result result() {
        return result;
    }

    static void begin() {
        result = null;
        status = "会话已开启，8 秒后仅采集一帧。请切到要测试的页面。";
    }

    static void status(String value) {
        status = value;
    }

    static void finish(Bitmap image, String summary, String lines, int lineCount, boolean contentUnavailable) {
        result = new Result(image, summary, lines, lineCount, contentUnavailable);
        status = contentUnavailable
                ? "未通过：主要内容区近乎黑色，未读到正文。采集会话已结束。"
                : lineCount > 0
                ? "已识别 " + lineCount + " 行文字，需核对目标正文。采集会话已结束。"
                : "采集已结束，但未识别到文字。请检查原图是否正确或为黑屏。";
    }

    static void fail(String value) {
        result = null;
        status = value;
    }

    static void clear() {
        result = null;
        status = "结果已从本机内存清空";
    }

    private ProbeState() {
    }
}
