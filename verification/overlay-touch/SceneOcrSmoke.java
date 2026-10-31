package com.codextemp.wechatcaptureprobe;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.util.ArrayList;
import java.util.List;

/** 只绘制固定语料，筛查原始布局整页OCR的质量；不采集、不发布，也不替代正式英文复核。 */
public final class SceneOcrSmoke extends Instrumentation {
    private static final String[] SOURCE = {
        "希望下周五18:30前上线，别后面再加钱。", "基础版8000元，额外需求另行确认。",
        "那先做安卓版本，iOS以后再说。", "好的，今天发范围清单，你确认后排期。",
        "行，我再跟同事商量一下，不是现在就定。", "明白，你们周三中午前反馈就好"
    };
    private int checks;
    private void require(boolean value) { checks++; if (!value) throw new AssertionError("scene-precondition"); }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    /** 中断时仍等真实SDK结束才允许上层回收图片，之后拒绝继续下一项。 */
    private Text recognize(TextRecognizer reader, Bitmap bitmap) throws Exception {
        Task<Text> task = reader.process(InputImage.fromBitmap(bitmap, 0));
        boolean interrupted = false;
        Text result;
        try {
            for (;;) {
                try { result = Tasks.await(task); break; }
                catch (InterruptedException ignored) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
        if (interrupted) throw new InterruptedException();
        return result;
    }

    private static final class Reading {
        final List<String> text = new ArrayList<>();
        long millis;
        int ambiguous;
    }

    private Reading serial(TextRecognizer reader, Bitmap image, List<Rect> boxes) throws Exception {
        Reading result = new Reading();
        long began = SystemClock.elapsedRealtime();
        for (Rect box : boxes) {
            require(!Thread.currentThread().isInterrupted());
            Bitmap crop = Bitmap.createBitmap(box.width() + 24, box.height() + 24, Bitmap.Config.ARGB_8888);
            try {
                Canvas canvas = new Canvas(crop); canvas.drawColor(Color.WHITE);
                canvas.drawBitmap(image, box, new Rect(12, 12, box.width() + 12, box.height() + 12), null);
                result.text.add(recognize(reader, crop).getText());
            } finally { crop.recycle(); }
        }
        result.millis = SystemClock.elapsedRealtime() - began;
        return result;
    }

    private Reading scene(TextRecognizer reader, Bitmap image, List<Rect> boxes) throws Exception {
        require(!Thread.currentThread().isInterrupted());
        Reading result = new Reading();
        long began = SystemClock.elapsedRealtime();
        Text recognized = recognize(reader, image);
        List<StringBuilder> content = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) content.add(new StringBuilder());
        for (Text.TextBlock block : recognized.getTextBlocks()) for (Text.Line line : block.getLines()) {
            Rect bounds = line.getBoundingBox();
            int owner = -1, matches = 0;
            if (bounds != null && !bounds.isEmpty()) for (int i = 0; i < boxes.size(); i++) {
                if (boxes.get(i).contains(bounds)) { owner = i; matches++; }
            }
            if (matches != 1) { result.ambiguous++; continue; }
            content.get(owner).append(line.getText()).append('\n');
        }
        for (StringBuilder text : content) result.text.add(text.toString());
        result.millis = SystemClock.elapsedRealtime() - began;
        return result;
    }

    private Bitmap fixture(int width, int size, List<Rect> boxes) {
        Bitmap image = Bitmap.createBitmap(1080, 1800, Bitmap.Config.ARGB_8888);
        boolean complete = false;
        try {
        Canvas canvas = new Canvas(image); canvas.drawColor(Color.rgb(237,237,237));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setTextSize(size);
        for (int i = 0; i < SOURCE.length; i++) {
            int left = i % 2 == 0 ? 80 : 1000 - width, top = 100 + i * 260;
            Rect box = new Rect(left, top, left + width, top + 180); boxes.add(box);
            paint.setColor(i % 2 == 0 ? Color.WHITE : Color.rgb(149,236,105)); canvas.drawRect(box, paint);
            paint.setColor(Color.BLACK);
            int offset = 0, row = 0;
            while (offset < SOURCE[i].length()) {
                int count = paint.breakText(SOURCE[i], offset, SOURCE[i].length(), true, width - 40, null);
                require(count > 0 && 55 + row * (size + 12) < box.height());
                canvas.drawText(SOURCE[i], offset, offset + count, left + 20, top + 55 + row * (size + 12), paint);
                offset += count; row++;
            }
        }
        complete = true;
        return image;
        } finally { if (!complete) image.recycle(); }
    }

    private static String compact(String text) { return text.replaceAll("\\s+", ""); }

    @Override public void onStart() {
        Bundle output = new Bundle(); TextRecognizer reader = null;
        try {
            require(!AssistantCaptureService.running() && !CaptureService.running() && !ChatGateService.enabled());
            reader = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            int lost = 0, wrong = 0, differences = 0, ambiguous = 0, exactErrors = 0;
            for (int round = 0; round < 4; round++) {
                require(!Thread.currentThread().isInterrupted());
                List<Rect> boxes = new ArrayList<>();
                Bitmap image = fixture(round % 2 == 0 ? 620 : 780, round < 2 ? 44 : 48, boxes);
                try {
                    Reading before, after;
                    if (round % 2 == 0) { before = serial(reader, image, boxes); after = scene(reader, image, boxes); }
                    else { after = scene(reader, image, boxes); before = serial(reader, image, boxes); }
                    int baselineCorrect = 0, sceneCorrect = 0, sceneExact = 0;
                    for (int i = 0; i < SOURCE.length; i++) {
                        String expected = String.format(java.util.Locale.ROOT, "T%02d", i + 3);
                        boolean original = ConversationSamples.matchedCodes(before.text.get(i)).contains(expected);
                        boolean next = ConversationSamples.matchedCodes(after.text.get(i)).contains(expected);
                        if (original) baselineCorrect++;
                        if (next) sceneCorrect++; else wrong++;
                        if (compact(SOURCE[i]).equals(compact(after.text.get(i)))) sceneExact++;
                        else exactErrors++;
                        if (original && !next) lost++;
                        if (!compact(before.text.get(i)).equals(compact(after.text.get(i)))) differences++;
                    }
                    require(baselineCorrect > 0);
                    ambiguous += after.ambiguous;
                    Bundle status = new Bundle();
                    status.putString("scene", "round=" + round + " baselineCodes=" + baselineCorrect + " sceneCodes=" + sceneCorrect
                            + " sceneExact=" + sceneExact + " serialMs=" + before.millis + " sceneMs=" + after.millis + " ambiguous=" + after.ambiguous);
                    sendStatus(2, status);
                } finally { image.recycle(); }
            }
            output.putString("result", (lost == 0 && wrong == 0 && ambiguous == 0 && exactErrors == 0 ? "PASS" : "FAIL")
                    + " sceneScreeningChecks=" + checks + " lostFixedCodes=" + lost + " wrongFixedCodes=" + wrong
                    + " textDifferences=" + differences + " ambiguous=" + ambiguous + " exactErrors=" + exactErrors
                    + " syntheticOnly=true realSdk=true productionCandidate=false latinReview=false");
        } catch (Throwable failure) {
            output.putString("result", "FAIL sceneScreeningChecks=" + checks + " type=" + failure.getClass().getSimpleName());
        } finally {
            if (reader != null) reader.close();
            finish(output.getString("result", "FAIL").startsWith("PASS") ? -1 : 0, output);
        }
    }
}
