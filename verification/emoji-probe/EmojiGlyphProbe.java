package com.codextemp.wechatcaptureprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.Image;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

public final class EmojiGlyphProbe extends Instrumentation {
    private static final int EXPECTED_FIXED_SAMPLE = 0x1f642;
    private final List<Template> templates = new ArrayList<>();
    private final List<Typeface> fonts = new ArrayList<>();
    private int checks;
    private Activity main;
    private Activity assistant;
    private boolean multiStyle;
    private boolean structural;
    private boolean controlsOnly;
    private boolean fixtureActive;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicLong heartbeatAt = new AtomicLong();
    private final AtomicBoolean observing = new AtomicBoolean();
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            heartbeatAt.set(SystemClock.elapsedRealtime());
            if (observing.get()) mainHandler.postDelayed(this, 300);
        }
    };

    private static final class Template {
        final int codepoint;
        final GlyphFeature feature;
        final Typeface font;
        final Bitmap artwork;
        final String style;

        Template(int codepoint, GlyphFeature feature, Typeface font) {
            this.codepoint = codepoint;
            this.feature = feature;
            this.font = font;
            artwork = null;
            style = "font";
        }

        Template(int codepoint, GlyphFeature feature, Bitmap artwork) {
            this.codepoint = codepoint;
            this.feature = feature;
            this.artwork = artwork;
            font = null;
            style = "twemoji";
        }
    }

    private static final class Match {
        final int codepoint;
        final double distance;
        final double margin;
        final int expectedRank;
        final boolean structural;

        Match(int codepoint, double distance, double margin, int expectedRank, boolean structural) {
            this.codepoint = codepoint;
            this.distance = distance;
            this.margin = margin;
            this.expectedRank = expectedRank;
            this.structural = structural;
        }

        boolean experimentalAcceptance() {
            return structural ? distance <= 0.20 && margin >= 0.08 : distance <= 0.045 && margin >= 0.015;
        }
    }

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        multiStyle = Boolean.parseBoolean(arguments.getString("multi_style", "false"));
        structural = Boolean.parseBoolean(arguments.getString("structure", "false"));
        controlsOnly = Boolean.parseBoolean(arguments.getString("controls_only", "false"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            startWatchdog();
            ModelSettingsStore settings = new ModelSettingsStore(getTargetContext());
            require(!settings.hasKey() && !settings.cloudAllowed() && !settings.partialAnalysisAllowed()
                    && !AssistantCaptureService.running() && !CaptureService.running(), "no-cloud-or-capture-precondition");
            fixtureActive = true;
            long started = SystemClock.elapsedRealtime();
            buildTemplates();
            require(templates.size() >= 70, "system-font-templates-rendered");
            controls();
            if (multiStyle) crossStyleControls();
            Log.i("EmojiProbe", "TEMPLATES count=" + templates.size() + " fonts=" + fonts.size()
                    + " multiStyle=" + multiStyle + " structural=" + structural
                    + " prepareMs=" + (SystemClock.elapsedRealtime() - started));
            if (controlsOnly) {
                result.putString("result", "MEASURED emojiProbeChecks=" + checks
                        + " syntheticOnly=true featureNotShipped=true modelRequests=0 projectionGrants=0 wechatWrites=0 rawChatFiles=0");
            } else {
            phase("WAITING_FOR_FIXTURE_ACTIVITY");
            main = startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            assistant = startActivitySync(new Intent(getTargetContext(), AssistantActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            phase("WAITING_FOR_NORMAL_GRANT_AND_FIXED_T08");
            await(() -> AssistantCaptureService.running() && fixedSampleVisible(), 240000, "normal-grant-and-confirmed-fixed-sample");
            AtomicReference<List<GlyphFeature>> captured = new AtomicReference<>();
            long deadline = SystemClock.elapsedRealtime() + 10000;
            while (captured.get() == null && SystemClock.elapsedRealtime() < deadline) {
                onMain(() -> captured.set(captureCandidates()));
                if (captured.get() == null) SystemClock.sleep(80);
            }
            require(captured.get() != null && !captured.get().isEmpty(), "unoccluded-graphic-candidates-present");
            require(captured.get().size() <= 4, "bounded-fixture-candidate-count");
            int index = 0;
            for (GlyphFeature candidate : captured.get()) {
                Match match = rank(candidate);
                Log.i("EmojiProbe", String.format(Locale.ROOT,
                        "FIXED_SAMPLE candidate=%d expectedTop1=%s expectedRank=%d bestDistance=%.5f uniqueLabelMargin=%.5f experimentalAccepted=%s",
                        index++, match.codepoint == EXPECTED_FIXED_SAMPLE, match.expectedRank, match.distance,
                        match.margin, match.experimentalAcceptance()));
            }
            require(!settings.hasKey() && !settings.cloudAllowed(), "real-model-remains-off");
            result.putString("result", "MEASURED emojiProbeChecks=" + checks + " actualCandidates=" + captured.get().size()
                    + " featureNotShipped=true fixedBubbleBound=true normalGrant=true multiStyle=" + multiStyle
                    + " structural=" + structural + " modelRequests=0 wechatWrites=0 rawChatFiles=0");
            }
        } catch (Throwable failure) {
            result.putString("result", "FAIL checks=" + checks + " type=" + failure.getClass().getSimpleName()
                    + (failure instanceof AssertionError ? " check=" + failure.getMessage() : ""));
        } finally {
            onMain(() -> {
                if (fixtureActive && !controlsOnly) CaptureControl.stopAll(getTargetContext());
                if (assistant != null) assistant.finish();
                if (main != null) main.finish();
            });
            for (Template template : templates) {
                if (template.artwork != null) template.artwork.recycle();
            }
            templates.clear();
            fonts.clear();
            observing.set(false);
            mainHandler.removeCallbacks(heartbeat);
        }
        Log.i("EmojiProbe", result.getString("result"));
        finish(result.getString("result").startsWith("MEASURED") ? -1 : 0, result);
    }

    private void buildTemplates() throws IOException {
        fonts.add(Typeface.DEFAULT);
        File systemFont = new File("/system/fonts/NotoColorEmoji.ttf");
        if (systemFont.isFile()) fonts.add(Typeface.createFromFile(systemFont));
        for (Typeface font : fonts) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            paint.setTypeface(font);
            for (int codepoint = 0x1f600; codepoint <= 0x1f64f; codepoint++) {
                String glyph = new String(Character.toChars(codepoint));
                if (paint.hasGlyph(glyph)) templates.add(new Template(codepoint, render(glyph, font, 96, Color.WHITE), font));
            }
        }
        if (multiStyle) {
            for (int codepoint = 0x1f600; codepoint <= 0x1f64f; codepoint++) {
                Bitmap artwork;
                try (InputStream input = getContext().getAssets().open("twemoji/" + Integer.toHexString(codepoint) + ".png")) {
                    artwork = BitmapFactory.decodeStream(input);
                }
                if (artwork == null || artwork.getWidth() != 72 || artwork.getHeight() != 72) {
                    if (artwork != null) artwork.recycle();
                    throw new IOException("Invalid pinned reference asset");
                }
                templates.add(new Template(codepoint, renderArtwork(artwork, 96, Color.WHITE), artwork));
            }
        }
    }

    private void startWatchdog() {
        heartbeatAt.set(SystemClock.elapsedRealtime());
        observing.set(true);
        mainHandler.post(heartbeat);
        Thread observer = new Thread(() -> {
            boolean reported = false;
            while (observing.get()) {
                SystemClock.sleep(500);
                long gap = SystemClock.elapsedRealtime() - heartbeatAt.get();
                if (gap < 1000) reported = false;
                if (gap >= 1500 && !reported) {
                    reported = true;
                    Log.i("EmojiProbe", "MAIN_HEARTBEAT_DELAY gapMs=" + gap);
                    for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                        if (entry.getKey().getName().equals("main")) {
                            for (int index = 0; index < Math.min(24, entry.getValue().length); index++) {
                                Log.i("EmojiProbe", "OWN_MAIN_STACK " + entry.getValue()[index]);
                            }
                        }
                    }
                }
            }
        }, "emoji-probe-main-observer");
        observer.setDaemon(true);
        observer.start();
    }

    private void controls() {
        int positive = 0;
        int correct = 0;
        int accepted = 0;
        int acceptedWrong = 0;
        double largestCorrect = 0;
        for (Template template : templates) {
            for (int size : new int[]{36, 54, 72}) {
                for (int background : new int[]{Color.WHITE, 0xff95ec69, 0xff303030}) {
                    Match match = rank(renderTemplate(template, size, background));
                    positive++;
                    if (match.codepoint == template.codepoint) {
                        correct++;
                        largestCorrect = Math.max(largestCorrect, match.distance);
                    }
                    if (match.experimentalAcceptance()) {
                        accepted++;
                        if (match.codepoint != template.codepoint) acceptedWrong++;
                    }
                }
            }
        }
        int negatives = 0;
        int negativeAccepted = 0;
        double nearestNegative = 1;
        for (Typeface font : fonts) {
            for (String text : new String[]{"0", "8", "O", ":)", "●", "AB", "中", "?"}) {
                Match match = rank(render(text, font, 54, Color.WHITE));
                negatives++;
                if (match.experimentalAcceptance()) negativeAccepted++;
                nearestNegative = Math.min(nearestNegative, match.distance);
            }
        }
        Log.i("EmojiProbe", String.format(Locale.ROOT,
                "SYNTHETIC positives=%d correctTop1=%d experimentalAccepted=%d acceptedWrong=%d largestCorrectDistance=%.5f negatives=%d negativeAccepted=%d nearestNegativeDistance=%.5f distanceIsNotProbability=true",
                positive, correct, accepted, acceptedWrong, largestCorrect, negatives, negativeAccepted, nearestNegative));
    }

    private GlyphFeature render(String text, Typeface font, int size, int background) {
        Bitmap bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(background);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            paint.setTypeface(font);
            paint.setColor(Color.BLACK);
            paint.setTextSize(size);
            canvas.drawText(text, 24, 135, paint);
            int[] pixels = new int[192 * 192];
            bitmap.getPixels(pixels, 0, 192, 0, 0, 192, 192);
            GlyphFeature feature = GlyphFeature.extract(pixels, 192, 192, background);
            if (feature == null) throw new AssertionError("rendered-glyph-empty");
            return feature;
        } finally {
            bitmap.recycle();
        }
    }

    private GlyphFeature renderTemplate(Template template, int size, int background) {
        return template.artwork == null
                ? render(new String(Character.toChars(template.codepoint)), template.font, size, background)
                : renderArtwork(template.artwork, size, background);
    }

    private GlyphFeature renderArtwork(Bitmap artwork, int size, int background) {
        Bitmap bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(background);
            canvas.drawBitmap(artwork, null, new android.graphics.Rect(24, 135 - size, 24 + size, 135),
                    new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
            int[] pixels = new int[192 * 192];
            bitmap.getPixels(pixels, 0, 192, 0, 0, 192, 192);
            GlyphFeature feature = GlyphFeature.extract(pixels, 192, 192, background);
            if (feature == null) throw new AssertionError("reference-artwork-empty");
            return feature;
        } finally {
            bitmap.recycle();
        }
    }

    private void crossStyleControls() {
        Map<String, int[]> groups = new LinkedHashMap<>();
        for (Template template : templates) {
            int[] counts = groups.computeIfAbsent(template.style, style -> new int[7]);
            for (int size : new int[]{36, 54, 72}) {
                for (int background : new int[]{Color.WHITE, 0xff95ec69, 0xff303030}) {
                    GlyphFeature candidate = renderTemplate(template, size, background);
                    Match raw = rank(candidate, false, template.style);
                    Match shape = rank(candidate, true, template.style);
                    counts[0]++;
                    if (raw.codepoint == template.codepoint) counts[1]++;
                    if (raw.experimentalAcceptance()) {
                        counts[5]++;
                        if (raw.codepoint != template.codepoint) counts[6]++;
                    }
                    if (shape.codepoint == template.codepoint) counts[2]++;
                    if (shape.experimentalAcceptance()) {
                        counts[3]++;
                        if (shape.codepoint != template.codepoint) counts[4]++;
                    }
                }
            }
        }
        for (Map.Entry<String, int[]> group : groups.entrySet()) {
            int[] counts = group.getValue();
            Log.i("EmojiProbe", "HELD_OUT_STYLE style=" + group.getKey() + " positives=" + counts[0]
                    + " rawCorrectTop1=" + counts[1] + " structureCorrectTop1=" + counts[2]
                    + " rawExperimentalAccepted=" + counts[5] + " rawAcceptedWrong=" + counts[6]
                    + " structureExperimentalAccepted=" + counts[3] + " structureAcceptedWrong=" + counts[4]
                    + " realWechatSamples=false distanceIsNotProbability=true");
        }
    }

    private Match rank(GlyphFeature candidate) {
        return rank(candidate, structural, "");
    }

    private Match rank(GlyphFeature candidate, boolean structure, String excludedStyle) {
        Map<Integer, Double> labels = new LinkedHashMap<>();
        for (Template template : templates) {
            if (template.style.equals(excludedStyle)) continue;
            labels.merge(template.codepoint, structure ? candidate.structureDistance(template.feature)
                    : candidate.distance(template.feature), Math::min);
        }
        List<Map.Entry<Integer, Double>> ordered = new ArrayList<>(labels.entrySet());
        ordered.sort(Comparator.comparingDouble(Map.Entry::getValue));
        int expectedRank = -1;
        for (int index = 0; index < ordered.size(); index++) {
            if (ordered.get(index).getKey() == EXPECTED_FIXED_SAMPLE) expectedRank = index + 1;
        }
        return new Match(ordered.get(0).getKey(), ordered.get(0).getValue(),
                ordered.get(1).getValue() - ordered.get(0).getValue(), expectedRank, structure);
    }

    private List<GlyphFeature> captureCandidates() {
        ChatGateService.Snapshot gate = ChatGateService.verify();
        AssistantState.Snapshot target = AssistantState.snapshot();
        if (!gate.valid() || !AssistantState.matches(target) || target.generation != gate.generation || !fixedSampleVisible()) return null;
        AssistantCaptureService service = (AssistantCaptureService) field(AssistantCaptureService.class, null, "instance");
        if (service == null || new ConversationRulesStore(getTargetContext()).excludes(target.title)) return null;
        Image image = (Image) field(AssistantCaptureService.class, service, "latest");
        if (image == null || image.getPlanes()[0].getPixelStride() != 4) return null;
        ScreenGeometry geometry = ScreenGeometryReader.read(getTargetContext());
        if (image.getWidth() != geometry.width || image.getHeight() != geometry.height) return null;
        long arrivedAt = (long) field(AssistantCaptureService.class, service, "arrivedAt");
        if (arrivedAt < gate.enteredAt + 250) return null;
        ChatViewport viewport = gate.viewport;
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        int stride = image.getPlanes()[0].getRowStride();
        ChatLayout.Pixels source = (horizontal, vertical) -> {
            if (viewport.masked(horizontal, vertical)) return 0xff777777;
            int offset = (vertical + viewport.bounds.top) * stride + (horizontal + viewport.bounds.left) * 4;
            return 0xff000000 | (buffer.get(offset) & 255) << 16 | (buffer.get(offset + 1) & 255) << 8 | (buffer.get(offset + 2) & 255);
        };
        if (FrameQuality.isMostlyBlack(viewport.width(), viewport.height(), source::colorAt)) throw new AssertionError("protected-frame-no-retry");
        float density = getTargetContext().getResources().getDisplayMetrics().density;
        ChatLayout layout = ChatLayout.detect(viewport.width(), viewport.height(), density, 0, 0, -1, source);
        if (layout == null) return null;
        layout = layout.withBubbles(viewport.width(), density, source);
        List<GlyphFeature> candidates = new ArrayList<>();
        for (ChatLayout.Box bubble : layout.bubbles) {
            if (viewport.occludes(bubble)) continue;
            boolean fixedBubble = target.messages.stream().anyMatch(message -> message.outgoing == bubble.outgoing
                    && message.top == bubble.top && ConversationSamples.matchedCodes(message.text).contains("T08"));
            if (!fixedBubble) continue;
            int background = background(source, bubble);
            for (GraphicDetector.Region region : GraphicDetector.detect(source, bubble, density)) {
                int left = Math.max(bubble.left, region.left - 4);
                int top = Math.max(bubble.top, region.top - 4);
                int right = Math.min(bubble.right, region.right + 4);
                int bottom = Math.min(bubble.bottom, region.bottom + 4);
                int width = right - left;
                int height = bottom - top;
                if (width > density * 64 || height > density * 64) continue;
                int[] pixels = new int[width * height];
                for (int vertical = 0; vertical < height; vertical++) {
                    for (int horizontal = 0; horizontal < width; horizontal++) pixels[vertical * width + horizontal] = source.colorAt(left + horizontal, top + vertical);
                }
                GlyphFeature feature = GlyphFeature.extract(pixels, width, height, background);
                if (feature != null) candidates.add(feature);
            }
        }
        if (!AssistantState.matches(target) || !gate.valid() || ChatGateService.snapshot().generation != gate.generation
                || !geometry.same(ScreenGeometryReader.read(getTargetContext()))) return null;
        return candidates.isEmpty() ? null : candidates;
    }

    private int background(ChatLayout.Pixels source, ChatLayout.Box bubble) {
        int[] histogram = new int[4096];
        int common = 0;
        for (int vertical = bubble.top + 4; vertical < bubble.bottom - 4; vertical += 8) {
            for (int horizontal = bubble.left + 4; horizontal < bubble.right - 4; horizontal += 8) {
                int color = source.colorAt(horizontal, vertical);
                int bucket = ((color >> 20) & 15) << 8 | ((color >> 12) & 15) << 4 | ((color >> 4) & 15);
                if (++histogram[bucket] > histogram[common]) common = bucket;
            }
        }
        return 0xff000000 | (((common >> 8) & 15) * 16 + 8) << 16
                | (((common >> 4) & 15) * 16 + 8) << 8 | (common & 15) * 16 + 8;
    }

    private boolean fixedSampleVisible() {
        if (AssistantState.token.isEmpty() || AssistantState.excluded) return false;
        StringBuilder text = new StringBuilder();
        for (ChatMessage message : AssistantState.messages) text.append(message.text).append('\n');
        return ConversationSamples.matchedCodes(text.toString()).contains("T08");
    }

    private Object field(Class<?> type, Object owner, String name) {
        try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("test-field-unavailable"); }
    }

    private void onMain(Runnable action) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new AssertionError("main-thread-probe-failed", error.get());
    }

    private void phase(String name) {
        Log.i("EmojiProbe", "PHASE " + name);
        Bundle status = new Bundle(); status.putString("phase", name); sendStatus(1, status);
    }

    private void await(BooleanSupplier condition, long timeout, String name) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
        require(condition.getAsBoolean(), name);
    }

    private void require(boolean condition, String name) { checks++; if (!condition) throw new AssertionError(name); }
}
