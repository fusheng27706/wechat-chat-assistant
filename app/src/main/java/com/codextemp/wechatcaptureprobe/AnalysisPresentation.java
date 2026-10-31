package com.codextemp.wechatcaptureprobe;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class AnalysisPresentation {
    static final class Source {
        final String title;
        final int visibleCount;
        final long completedWallTime;
        final long completedElapsedTime;
        final CaptureCoverage coverage;

        Source(String title, int visibleCount, long completedWallTime, long completedElapsedTime) {
            this(title, visibleCount, completedWallTime, completedElapsedTime, CaptureCoverage.UNOBSTRUCTED);
        }

        Source(String title, int visibleCount, long completedWallTime, long completedElapsedTime, CaptureCoverage coverage) {
            this.title = title;
            this.visibleCount = visibleCount;
            this.completedWallTime = completedWallTime;
            this.completedElapsedTime = completedElapsedTime;
            this.coverage = coverage;
        }
    }

    static String brief(AnalysisResult result) {
        String summary = result.summary.replaceAll("\\s+", " ").trim();
        int count = summary.codePointCount(0, summary.length());
        return count <= 96 ? summary : summary.substring(0, summary.offsetByCodePoints(0, 96)) + "…";
    }

    static String brief(AnalysisResult result, Source source) {
        return (source != null && source.coverage.obscured() ? "局部分析 · 可能遗漏关键回复\n" : "") + brief(result);
    }

    static String source(Source source, long nowElapsed) {
        if (source == null) {
            return "结果来源或时间未确认，请重新分析";
        }
        long ageSeconds = Math.max(0, nowElapsed - source.completedElapsedTime) / 1000;
        String age = ageSeconds < 60 ? "不足 1 分钟前" : ageSeconds < 3600 ? ageSeconds / 60 + " 分钟前"
                : ageSeconds < 86400 ? ageSeconds / 3600 + " 小时前" : ageSeconds / 86400 + " 天前";
        String time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(source.completedWallTime));
        return "来源标题（OCR 推断，非账号身份）：" + source.title + "\n基于当时可见 " + source.visibleCount
                + " 条；请求会按条数/文字限额截取。\n分析完成 " + time + " · " + age
                + (source.coverage.obscured() ? "\n" + source.coverage.warning() + "\n已遮蔽 " + source.coverage.maskedWindows
                + " 个窗口区域、跳过 " + source.coverage.skippedBubbles + " 个邻近气泡候选（不是漏读消息总数）。" : "");
    }

    static String details(AnalysisResult result) {
        StringBuilder output = new StringBuilder("完整简述：").append(result.summary)
                .append("\n等级表示推测强弱，不是统计概率，也不证明真实意图。\n");
        if (result.discussion != null) {
            output.append("\n群聊话题：").append(result.discussion.topic)
                    .append("\n成员身份未识别；观点条数不等于人数，不把所有对方消息当同一个人。\n");
            for (AnalysisResult.Viewpoint viewpoint : result.discussion.viewpoints) {
                output.append('\n').append(viewpoint.speaker).append("：").append(viewpoint.position);
                appendEvidence(output, viewpoint.evidence);
            }
        }
        for (AnalysisResult.Intent intent : result.intents) {
            output.append('\n').append("信息不足".equals(intent.level) ? "暂无法判断" : intent.level)
                    .append("：").append(intent.explanation);
            appendEvidence(output, intent.evidence);
        }
        for (String limitation : result.limitations) {
            output.append("\n限制：").append(limitation);
        }
        return output.toString();
    }

    static String details(AnalysisResult result, Source source) {
        return (source != null && source.coverage.obscured() ? source.coverage.warning() + "\n\n" : "") + details(result);
    }

    private static void appendEvidence(StringBuilder output, java.util.List<String> evidence) {
        for (String quote : evidence) {
            output.append("\n依据（OCR，需核对）：").append(quote);
        }
        output.append('\n');
    }

    private AnalysisPresentation() {
    }
}
