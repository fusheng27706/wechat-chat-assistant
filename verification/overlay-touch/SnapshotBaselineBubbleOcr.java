package com.codextemp.wechatcaptureprobe;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

final class SnapshotBaselineBubbleOcr implements AutoCloseable {
    interface Callback {
        void success(List<ChatMessage> messages, Stats stats);
        void cancelled();
        void failure();
    }

    static final class Stats {
        int cacheHits;
        int freshBubbles;
        int latinCalls;
        // 命中缓存仍消耗原复核额度，避免改变后续气泡的预算；latinCalls仅计真实SDK请求。
        int latinBudgetUsed;
        int graphics;
        // SDK墙钟时间包含回调排队；只作数字诊断，不等同模型内部计算时间。
        long chineseWallMs;
        long latinWallMs;
        long prepareMs;
        long chineseQueueMs;
        long latinQueueMs;
        // 逐气泡来源校验的同步墙钟；prepareMs内也可能包含校验，二者不能相加。
        long validationMs;
        int validationCalls;
    }

    private static final class Cached {
        final String text;
        final String warning;
        final long visual;
        final int graphics;
        final int latinBudgetCost;

        Cached(String text, String warning, long visual, int graphics, int latinBudgetCost) {
            this.text = text;
            this.warning = warning;
            this.visual = visual;
            this.graphics = graphics;
            this.latinBudgetCost = latinBudgetCost;
        }
    }

    private static final class Glyph {
        final Rect bounds;
        String text;
        final int group;

        Glyph(Rect bounds, String text, int group) {
            this.bounds = new Rect(bounds);
            this.text = text;
            this.group = group;
        }
    }

    private static final class Run {
        final List<Glyph> glyphs = new ArrayList<>();
        final Rect bounds = new Rect();

        void add(Glyph glyph) {
            if (glyphs.isEmpty()) {
                bounds.set(glyph.bounds);
            } else {
                bounds.union(glyph.bounds);
            }
            glyphs.add(glyph);
        }

        String text() {
            StringBuilder text = new StringBuilder();
            for (Glyph glyph : glyphs) {
                text.append(glyph.text);
            }
            return text.toString();
        }
    }

    private static final class Parsed {
        final List<Glyph> glyphs = new ArrayList<>();
        final List<Run> runs = new ArrayList<>();
        final Set<String> warnings = new LinkedHashSet<>();
        boolean han;
        boolean latin;
        boolean reusable = true;
    }

    private final TextRecognizer chinese;
    private final TextRecognizer latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    // 四MiB仅指无损像素存储预算，最多32个结果；不保存来源或会话证明。
    private final ExactPixelCache<Cached> cache = new ExactPixelCache<>(1024 * 1024, 32);
    private final BubblePreparation preparation;
    private long cacheEpoch;
    private boolean closed;

    SnapshotBaselineBubbleOcr(TextRecognizer chinese) {
        this(chinese, new BubblePreparation());
    }

    SnapshotBaselineBubbleOcr(TextRecognizer chinese, BubblePreparation preparation) {
        this.chinese = chinese;
        this.preparation = preparation;
    }

    void read(Bitmap screen, List<ChatLayout.Box> boxes, float density, BooleanSupplier current, Callback callback) {
        Stats stats = new Stats();
        BooleanSupplier measuredCurrent = () -> {
            long started = android.os.SystemClock.elapsedRealtime();
            try {
                return current.getAsBoolean();
            } finally {
                stats.validationCalls++;
                stats.validationMs += android.os.SystemClock.elapsedRealtime() - started;
            }
        };
        readAt(screen, boxes, density, measuredCurrent, callback, new ArrayList<>(), stats, 0);
    }

    private void readAt(Bitmap screen, List<ChatLayout.Box> boxes, float density, BooleanSupplier current,
                        Callback callback, List<ChatMessage> messages, Stats stats, int index) {
        if (closed || !current.getAsBoolean()) {
            callback.cancelled();
            return;
        }
        if (index >= boxes.size()) {
            callback.success(messages, stats);
            return;
        }
        ChatLayout.Box box = boxes.get(index);
        long prepareStarted = android.os.SystemClock.elapsedRealtime();
        int width = box.right - box.left;
        int height = box.bottom - box.top;
        int[] pixels = new int[width * height];
        screen.getPixels(pixels, 0, width, box.left, box.top, width, height);
        long fingerprint = 1469598103934665603L;
        for (int color : pixels) {
            fingerprint = (fingerprint ^ color) * 1099511628211L;
        }
        long attemptEpoch = cacheEpoch;
        int budgetBefore = stats.latinBudgetUsed;
        String key = width + ":" + height + ":" + box.outgoing + ":" + Float.floatToIntBits(density)
                + ":" + fingerprint;
        Cached previous = cache.get(key, pixels, android.os.SystemClock.elapsedRealtime());
        // 完整复核结果不依赖其在上一帧的顺序，但本轮必须仍有足够额度。
        if (previous != null && previous.latinBudgetCost > 8 - budgetBefore) previous = null;
        if (previous != null) {
            if (!current.getAsBoolean() || cacheEpoch != attemptEpoch) { callback.cancelled(); return; }
            if (!cache.activate(key, pixels, previous, android.os.SystemClock.elapsedRealtime())) previous = null;
        }
        if (previous != null) {
            stats.latinBudgetUsed += previous.latinBudgetCost;
            stats.prepareMs += android.os.SystemClock.elapsedRealtime() - prepareStarted;
            stats.cacheHits++;
            append(previous, box, messages, stats);
            readAt(screen, boxes, density, current, callback, messages, stats, index + 1);
            return;
        }
        stats.freshBubbles++;
        preparation.prepare(pixels, box, density, built -> {
        boolean accepted;
        try { accepted = !closed && current.getAsBoolean() && cacheEpoch == attemptEpoch; }
        catch (RuntimeException failure) {
            if (built != null) built.close();
            callback.failure();
            return;
        }
        // 准备任务结束前不回调、不释放本帧；关闭/换会话后的晚到图只回收。
        if (!accepted) {
            if (built != null) built.close();
            callback.cancelled();
            return;
        }
        if (built == null) { callback.failure(); return; }
        Bitmap prepared = built.bitmap;
        List<Rect> graphicBounds = built.graphics;
        long visualFingerprint = built.visual;
        stats.prepareMs += android.os.SystemClock.elapsedRealtime() - prepareStarted;
        long chineseStarted = android.os.SystemClock.elapsedRealtime();
        try {
            OcrCallbackClock chineseClock = new OcrCallbackClock();
            chineseClock.watch(chinese.process(InputImage.fromBitmap(prepared, 0))).addOnSuccessListener(result -> {
                stats.chineseQueueMs += chineseClock.queueMs();
                stats.chineseWallMs += android.os.SystemClock.elapsedRealtime() - chineseStarted;
                if (closed || !current.getAsBoolean() || cacheEpoch != attemptEpoch) {
                    prepared.recycle();
                    callback.cancelled();
                    return;
                }
                Parsed parsed = parse(result);
                Runnable finish = () -> {
                    if (closed || !current.getAsBoolean() || cacheEpoch != attemptEpoch) {
                        prepared.recycle();
                        callback.cancelled();
                        return;
                    }
                    for (Rect rectangle : graphicBounds) {
                        parsed.glyphs.add(new Glyph(rectangle, "[未解析表情/图形]", -1));
                    }
                    if (!graphicBounds.isEmpty()) {
                        parsed.warnings.add("含未解析表情/图形，不能据此判断具体情绪");
                    }
                    String text = compose(parsed.glyphs);
                    if (text.isEmpty()) {
                        parsed.reusable = false;
                        text = "[气泡内容未能识别]";
                        parsed.warnings.add("可见气泡未读出正文");
                    }
                    Cached decoded = new Cached(text, String.join("；", parsed.warnings), visualFingerprint,
                            graphicBounds.size(), stats.latinBudgetUsed - budgetBefore);
                    if (parsed.reusable) cache.put(key, pixels, decoded, android.os.SystemClock.elapsedRealtime());
                    prepared.recycle();
                    append(decoded, box, messages, stats);
                    readAt(screen, boxes, density, current, callback, messages, stats, index + 1);
                };
                if (parsed.latin && !parsed.han && stats.latinBudgetUsed >= 8) parsed.reusable = false;
                if (parsed.latin && !parsed.han && stats.latinBudgetUsed < 8) {
                    stats.latinCalls++; stats.latinBudgetUsed++;
                    long latinStarted = android.os.SystemClock.elapsedRealtime();
                    OcrCallbackClock latinClock = new OcrCallbackClock();
                    latinClock.watch(latin.process(InputImage.fromBitmap(prepared, 0))).addOnSuccessListener(reread -> {
                        stats.latinQueueMs += latinClock.queueMs();
                        stats.latinWallMs += android.os.SystemClock.elapsedRealtime() - latinStarted;
                        Parsed alternate = parse(reread);
                        if (alternate.glyphs.isEmpty()) parsed.reusable = false;
                        if (!alternate.glyphs.isEmpty()) {
                            if (!compose(parsed.glyphs).equals(compose(alternate.glyphs))) {
                                parsed.warnings.add("中英文识别结果存在分歧，请核对");
                            }
                            parsed.glyphs.clear();
                            parsed.glyphs.addAll(alternate.glyphs);
                            parsed.warnings.addAll(alternate.warnings);
                        }
                        finish.run();
                    }).addOnFailureListener(failure -> {
                        stats.latinWallMs += android.os.SystemClock.elapsedRealtime() - latinStarted;
                        parsed.warnings.add("英文复核未完成，保留初次结果");
                        parsed.reusable = false;
                        finish.run();
                    });
                } else {
                    rereadRuns(prepared, parsed, stats, current, callback, finish, 0);
                }
            }).addOnFailureListener(failure -> {
                prepared.recycle();
                callback.failure();
            });
        } catch (RuntimeException failure) {
            prepared.recycle();
            callback.failure();
        }
        });
    }

    private void rereadRuns(Bitmap prepared, Parsed parsed, Stats stats, BooleanSupplier current,
                            Callback callback, Runnable finish, int index) {
        if (closed || !current.getAsBoolean()) {
            prepared.recycle();
            callback.cancelled();
            return;
        }
        if (index >= parsed.runs.size()) {
            finish.run();
            return;
        }
        if (stats.latinBudgetUsed >= 8) {
            parsed.reusable = false;
            parsed.warnings.add("本轮英文复核达到上限，部分片段需核对");
            finish.run();
            return;
        }
        Run run = parsed.runs.get(index);
        int left = Math.max(0, run.bounds.left - 6);
        int top = Math.max(0, run.bounds.top - 6);
        int right = Math.min(prepared.getWidth(), run.bounds.right + 6);
        int bottom = Math.min(prepared.getHeight(), run.bounds.bottom + 6);
        if (right <= left || bottom <= top) {
            parsed.reusable = false;
            parsed.warnings.add("英文文字框不可用，保留初次结果");
            rereadRuns(prepared, parsed, stats, current, callback, finish, index + 1);
            return;
        }
        Bitmap token = Bitmap.createBitmap(Math.max(32, right - left + 24), Math.max(32, bottom - top + 24), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(token);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(prepared, new Rect(left, top, right, bottom), new Rect(12, 12, right - left + 12, bottom - top + 12), null);
        stats.latinCalls++; stats.latinBudgetUsed++;
        long latinStarted = android.os.SystemClock.elapsedRealtime();
        OcrCallbackClock latinClock = new OcrCallbackClock();
        latinClock.watch(latin.process(InputImage.fromBitmap(token, 0))).addOnSuccessListener(result -> {
            stats.latinQueueMs += latinClock.queueMs();
            stats.latinWallMs += android.os.SystemClock.elapsedRealtime() - latinStarted;
            token.recycle();
            String text = OcrTextRules.compact(result.getText());
            if (text.length() <= 128 && OcrTextRules.alphanumeric(text) && OcrTextRules.hasLetter(text)) {
                if (!text.equals(run.text())) {
                    parsed.warnings.add("英文片段与中文模型存在分歧，请核对");
                }
                Glyph first = run.glyphs.get(0);
                parsed.glyphs.removeAll(run.glyphs);
                parsed.glyphs.add(new Glyph(run.bounds, text, first.group));
                checkConfidence(result, parsed.warnings);
            } else {
                parsed.reusable = false;
                parsed.warnings.add("英文片段未能可靠复核，保留初次结果");
            }
            rereadRuns(prepared, parsed, stats, current, callback, finish, index + 1);
        }).addOnFailureListener(failure -> {
            stats.latinWallMs += android.os.SystemClock.elapsedRealtime() - latinStarted;
            token.recycle();
            parsed.warnings.add("英文片段复核失败，保留初次结果");
            parsed.reusable = false;
            rereadRuns(prepared, parsed, stats, current, callback, finish, index + 1);
        });
    }

    private static void append(Cached result, ChatLayout.Box box, List<ChatMessage> messages, Stats stats) {
        messages.add(new ChatMessage(box.outgoing, result.text, box.top, result.warning, result.visual));
        stats.graphics += result.graphics;
    }

    private static Parsed parse(Text result) {
        Parsed parsed = new Parsed();
        checkConfidence(result, parsed.warnings);
        int group = 0;
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                for (Text.Element element : line.getElements()) {
                    group++;
                    Run run = new Run();
                    List<Glyph> elementGlyphs = new ArrayList<>();
                    for (Text.Symbol symbol : element.getSymbols()) {
                        if (symbol.getBoundingBox() != null && !symbol.getBoundingBox().isEmpty() && !symbol.getText().isEmpty()) {
                            elementGlyphs.add(new Glyph(symbol.getBoundingBox(), symbol.getText(), group));
                        }
                    }
                    if (elementGlyphs.isEmpty() && element.getBoundingBox() != null && !element.getBoundingBox().isEmpty()) {
                        elementGlyphs.add(new Glyph(element.getBoundingBox(), element.getText(), group));
                    }
                    for (Glyph glyph : elementGlyphs) {
                        parsed.glyphs.add(glyph);
                        parsed.han |= glyph.text.codePoints().anyMatch(value -> Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN);
                        parsed.latin |= OcrTextRules.hasLetter(glyph.text);
                        if (OcrTextRules.alphanumeric(glyph.text)) {
                            run.add(glyph);
                        } else {
                            if (OcrTextRules.hasLetter(run.text())) {
                                parsed.runs.add(run);
                            }
                            run = new Run();
                        }
                    }
                    if (OcrTextRules.hasLetter(run.text())) {
                        parsed.runs.add(run);
                    }
                }
            }
        }
        return parsed;
    }

    private static void checkConfidence(Text text, Set<String> warnings) {
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                if (line.getConfidence() == 0) {
                    warnings.add("文字识别置信度不可用，需核对");
                } else if (line.getConfidence() < 0.75f) {
                    warnings.add("部分文字识别分数较低，需核对");
                }
            }
        }
    }

    private static String compose(List<Glyph> input) {
        List<List<Glyph>> rows = new ArrayList<>();
        List<Glyph> ordered = new ArrayList<>(input);
        ordered.sort(Comparator.comparingInt((Glyph glyph) -> glyph.bounds.top).thenComparingInt(glyph -> glyph.bounds.left));
        for (Glyph glyph : ordered) {
            List<Glyph> selected = null;
            for (List<Glyph> row : rows) {
                Rect anchor = row.get(0).bounds;
                int overlap = Math.min(anchor.bottom, glyph.bounds.bottom) - Math.max(anchor.top, glyph.bounds.top);
                if (overlap > Math.min(anchor.height(), glyph.bounds.height()) * 0.35f) {
                    selected = row;
                    break;
                }
            }
            if (selected == null) {
                selected = new ArrayList<>();
                rows.add(selected);
            }
            selected.add(glyph);
        }
        StringBuilder output = new StringBuilder();
        for (List<Glyph> row : rows) {
            row.sort(Comparator.comparingInt(glyph -> glyph.bounds.left));
            if (output.length() > 0) {
                output.append('\n');
            }
            Glyph previous = null;
            for (Glyph glyph : row) {
                if (previous != null && previous.group != glyph.group && OcrTextRules.endsAlphanumeric(previous.text)
                        && OcrTextRules.startsAlphanumeric(glyph.text)
                        && glyph.bounds.left - previous.bounds.right > Math.min(previous.bounds.height(), glyph.bounds.height()) * 0.15f) {
                    output.append(' ');
                }
                output.append(glyph.text);
                previous = glyph;
            }
        }
        return output.toString().trim();
    }

    void clearCache() {
        cacheEpoch++;
        cache.clear();
    }

    void quarantineCache() { cache.quarantine(android.os.SystemClock.elapsedRealtime()); }

    void pruneCache() { cache.prune(android.os.SystemClock.elapsedRealtime()); }

    @Override
    public void close() {
        closed = true;
        preparation.close();
        clearCache();
        latin.close();
    }
}
