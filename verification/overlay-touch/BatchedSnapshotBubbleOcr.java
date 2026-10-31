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

final class BatchedSnapshotBubbleOcr implements AutoCloseable {
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
        // 性能诊断：同read像素快照的worker墙钟/CPU纳秒，不等于帧时间。
        long snapshotWallNs, snapshotCpuNs, snapshotMaxWallNs, copyWallNs, hashWallNs;
        int snapshotCalls;
        int snapshotBatches;
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
    private final TextRecognizer latin;
    // 四MiB仅指无损像素存储预算，最多32个结果；不保存来源或会话证明。
    private final ExactPixelCache<Cached> cache = new ExactPixelCache<>(1024 * 1024, 32);
    private final BubblePreparation preparation;
    private long cacheEpoch;
    private boolean closed;
    private ReadSession session;
    private final java.util.concurrent.ExecutorService snapshotWorker;
    private final SnapshotFactory snapshotFactory;
    private final android.os.Handler snapshotDelivery = new android.os.Handler(android.os.Looper.getMainLooper());
    interface SnapshotFactory { PixelSnapshot create(Bitmap screen, ChatLayout.Box box, float density); }

    BatchedSnapshotBubbleOcr(TextRecognizer chinese) {
        this(chinese, new BubblePreparation());
    }

    BatchedSnapshotBubbleOcr(TextRecognizer chinese, BubblePreparation preparation) {
        this(chinese, preparation, java.util.concurrent.Executors.newSingleThreadExecutor(), BatchedSnapshotBubbleOcr::snapshot);
    }

    BatchedSnapshotBubbleOcr(TextRecognizer chinese, BubblePreparation preparation,
            java.util.concurrent.ExecutorService snapshotWorker, SnapshotFactory snapshotFactory) {
        this(chinese, preparation, snapshotWorker, snapshotFactory,
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS));
    }

    /** 测试可注入受控Latin；实例负责关闭它，Chinese仍由服务拥有。 */
    BatchedSnapshotBubbleOcr(TextRecognizer chinese, BubblePreparation preparation,
            java.util.concurrent.ExecutorService snapshotWorker, SnapshotFactory snapshotFactory, TextRecognizer latin) {
        this.latin = latin;
        this.snapshotWorker = snapshotWorker;
        this.snapshotFactory = snapshotFactory;
        this.chinese = chinese;
        this.preparation = preparation;
    }

    void read(Bitmap screen, List<ChatLayout.Box> boxes, float density, BooleanSupplier current, Callback callback) {
        if (session != null) throw new IllegalStateException("Read already active");
        Stats stats = new Stats();
        List<ChatLayout.Box> frameBoxes = new ArrayList<>(boxes);
        session = new ReadSession(callback, stats, frameBoxes);
        Callback settled = session.callback;
        BooleanSupplier measuredCurrent = () -> {
            long started = android.os.SystemClock.elapsedRealtime();
            try {
                return current.getAsBoolean();
            } catch (RuntimeException unavailable) {
                // 异步交付后来源查询也可能失败；按来源不可确认撤销，不能逃逸主Handler。
                return false;
            } finally {
                stats.validationCalls++;
                stats.validationMs += android.os.SystemClock.elapsedRealtime() - started;
            }
        };
        readAt(screen, frameBoxes, density, measuredCurrent, settled, new ArrayList<>(), stats, 0);
    }

    private void readAt(Bitmap screen, List<ChatLayout.Box> boxes, float density, BooleanSupplier current,
                        Callback callback, List<ChatMessage> messages, Stats stats, int index) {
        if (closed || session.epoch != cacheEpoch || !current.getAsBoolean()) {
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
        if (session.awaitSnapshot(index, screen, box, density,
                () -> readAt(screen, boxes, density, current, callback, messages, stats, index))) return;
        PixelSnapshot snapshot = session.slot.snapshot;
        if (snapshot == null) { callback.failure(); return; }
        int[] pixels = snapshot.pixels;
        long attemptEpoch = cacheEpoch;
        int budgetBefore = stats.latinBudgetUsed;
        String key = snapshot.key;
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
            session.discardThen(() -> readAt(screen, boxes, density, current, callback, messages, stats, index + 1));
            return;
        }
        stats.freshBubbles++;
        session.take(index, snapshot, box, density, built -> {
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
            com.google.android.gms.tasks.Task<Text> task = chineseClock.watch(chinese.process(InputImage.fromBitmap(prepared, 0)));
            // 仅提前做私有图像准备，真实SDK仍由原递归顺序串行启动。
            session.prefetch(screen, boxes, density, index + 1);
            task.addOnSuccessListener(result -> {
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

    static final class PixelSnapshot {
        final int[] pixels;
        final String key;
        long wallNs, cpuNs, copyNs, hashNs;
        PixelSnapshot(int[] pixels, String key) { this.pixels = pixels; this.key = key; }
    }

    /** 同一read持有不变screen；只复用该帧的私有像素，不跨帧复用来源证明。 */
    static PixelSnapshot snapshot(Bitmap screen, ChatLayout.Box box, float density) {
        long started = System.nanoTime(), cpuStarted = android.os.Debug.threadCpuTimeNanos();
        int width = box.right - box.left, height = box.bottom - box.top;
        int[] pixels = new int[width * height];
        screen.getPixels(pixels, 0, width, box.left, box.top, width, height);
        long copied = System.nanoTime();
        long fingerprint = 1469598103934665603L;
        for (int color : pixels) fingerprint = (fingerprint ^ color) * 1099511628211L;
        PixelSnapshot result = new PixelSnapshot(pixels, width + ":" + height + ":" + box.outgoing + ":"
                + Float.floatToIntBits(density) + ":" + fingerprint);
        long finished = System.nanoTime();
        result.wallNs = finished - started;
        result.cpuNs = android.os.Debug.threadCpuTimeNanos() - cpuStarted;
        result.copyNs = copied - started;
        result.hashNs = finished - copied;
        return result;
    }

    /** 单槽和终态在主线程；snapshot worker只读被本read持有的父图，不读业务状态。 */
    private final class ReadSession {
        private final Callback destination;
        private final long epoch = cacheEpoch;
        private final Stats stats;
        private final List<ChatLayout.Box> boxes;
        private final boolean batchEnabled;
        private PixelSnapshot spare;
        private int spareIndex = -1;
        private Slot slot;
        private int pending;
        private Runnable terminal;
        private boolean delivered;
        private final class Slot {
            final int index;
            PixelSnapshot snapshot;
            boolean snapshotFailed;
            Runnable snapshotConsumer;
            BubblePreparation.Result result;
            boolean ready;
            boolean attempted;
            java.util.function.Consumer<BubblePreparation.Result> consumer;
            Slot(int index, PixelSnapshot snapshot) { this.index = index; this.snapshot = snapshot; }
        }
        ReadSession(Callback destination, Stats stats, List<ChatLayout.Box> boxes) {
            this.destination = destination; this.stats = stats; this.boxes = boxes;
            batchEnabled = cache.size() >= boxes.size() && boxes.size() > 1;
        }
        final Callback callback = new Callback() {
            public void success(List<ChatMessage> messages, Stats stats) { finish(() -> destination.success(messages, stats)); }
            public void cancelled() { finish(destination::cancelled); }
            public void failure() { finish(destination::failure); }
        };
        void finish(Runnable notification) {
            if (terminal != null || delivered) return;
            terminal = notification;
            spare = null; spareIndex = -1;
            if (slot != null && slot.ready) {
                if (slot.result != null) slot.result.close();
                slot = null;
            }
            flush();
        }
        void flush() {
            if (terminal == null || pending != 0 || delivered) return;
            delivered = true;
            session = null;
            terminal.run();
        }
        void start(int index, PixelSnapshot snapshot, ChatLayout.Box box, float density) {
            if (slot != null) throw new IllegalStateException("Preparation slot occupied");
            Slot next = new Slot(index, snapshot);
            next.attempted = true;
            slot = next;
            pending++;
            preparation.prepare(snapshot.pixels, box, density, built -> {
                pending--;
                next.ready = true;
                next.result = built;
                if (terminal != null) {
                    if (built != null) built.close();
                    slot = null;
                    flush();
                } else if (next.consumer != null) {
                    slot = null;
                    next.consumer.accept(built);
                }
            });
        }
        void take(int index, PixelSnapshot snapshot, ChatLayout.Box box, float density,
                  java.util.function.Consumer<BubblePreparation.Result> consumer) {
            // 缓存提示不代表命中；精确查询或预算检查未通过时仍走正常准备。
            if (slot != null && slot.index == index && !slot.attempted) slot = null;
            if (slot == null) start(index, snapshot, box, density);
            Slot next = slot;
            if (next.index != index) throw new IllegalStateException("Preparation index mismatch");
            if (next.ready) {
                slot = null;
                consumer.accept(next.result);
            } else next.consumer = consumer;
        }
        void discardThen(Runnable continuation) {
            if (slot == null) { continuation.run(); return; }
            Slot unused = slot;
            java.util.function.Consumer<BubblePreparation.Result> discard = result -> {
                if (result != null) result.close();
                continuation.run();
            };
            if (unused.ready) {
                slot = null;
                discard.accept(unused.result);
            } else unused.consumer = discard;
        }
        boolean awaitSnapshot(int index, Bitmap screen, ChatLayout.Box box, float density, Runnable continuation) {
            if (slot == null) startSnapshot(index, screen, box, density);
            if (slot.index != index) throw new IllegalStateException("Snapshot index mismatch");
            if (slot.snapshot != null || slot.snapshotFailed) return false;
            slot.snapshotConsumer = continuation;
            return true;
        }
        void startSnapshot(int index, Bitmap screen, ChatLayout.Box box, float density) {
            if (slot != null) throw new IllegalStateException("Snapshot slot occupied");
            if (spareIndex == index) {
                slot = new Slot(index, spare);
                slot.snapshotFailed = spare == null;
                slot.ready = true;
                spare = null; spareIndex = -1;
                return;
            }
            Slot next = new Slot(index, null);
            slot = next;
            pending++;
            stats.snapshotBatches++;
            // 缓存数量仅作批处理提示；最多两条合计一百万像素，实际使用仍逐条校验。
            ChatLayout.Box extraBox = batchEnabled && index + 1 < boxes.size() ? boxes.get(index + 1) : null;
            long pixels = (long)(box.right-box.left)*(box.bottom-box.top);
            if (extraBox != null && pixels + (long)(extraBox.right-extraBox.left)*(extraBox.bottom-extraBox.top) > 1024*1024)
                extraBox = null;
            ChatLayout.Box secondBox = extraBox;
            try {
                snapshotWorker.execute(() -> {
                    PixelSnapshot value;
                    try { value = snapshotFactory.create(screen, box, density); }
                    catch (RuntimeException failure) { value = null; }
                    PixelSnapshot additional = null;
                    boolean readExtra = value != null && secondBox != null;
                    if (readExtra) try { additional = snapshotFactory.create(screen, secondBox, density); }
                    catch (RuntimeException failure) { additional = null; }
                    PixelSnapshot ready = value, extra = additional;
                    // pending直到主线程收到交付才减少；服务不能在worker仍读父图时结束本帧。
                    snapshotDelivery.post(() -> {
                        if (terminal == null && readExtra) {
                            spareIndex = index + 1; spare = extra; recordSnapshot(extra);
                        }
                        receiveSnapshot(next, ready, box, density);
                    });
                });
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                receiveSnapshot(next, null, box, density);
            }
        }
        void receiveSnapshot(Slot next, PixelSnapshot value, ChatLayout.Box box, float density) {
            pending--;
            next.snapshot = value;
            next.snapshotFailed = value == null;
            next.ready = true;
            recordSnapshot(value);
            if (terminal != null) { slot = null; flush(); return; }
            if (next.snapshotConsumer != null) { next.snapshotConsumer.run(); return; }
            if (value != null && !closed && !cache.mayContain(value.key)) {
                slot = null;
                start(next.index, value, box, density);
            }
        }
        void recordSnapshot(PixelSnapshot value) {
            if (value == null) return;
            stats.snapshotCalls++;
            stats.snapshotWallNs += value.wallNs;
            stats.snapshotCpuNs += value.cpuNs;
            stats.snapshotMaxWallNs = Math.max(stats.snapshotMaxWallNs, value.wallNs);
            stats.copyWallNs += value.copyNs;
            stats.hashWallNs += value.hashNs;
        }
        void prefetch(Bitmap screen, List<ChatLayout.Box> boxes, float density, int index) {
            if (closed || terminal != null || slot != null || index >= boxes.size()) return;
            startSnapshot(index, screen, boxes.get(index), density);
        }
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
        snapshotWorker.shutdown();
        clearCache();
        latin.close();
    }
}
