package com.codextemp.wechatcaptureprobe;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.SystemClock;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.util.Arrays;

/** 固定虚构语料的缩图筛查；不采集、不访问缓存，不代表正式中英文复核质量。 */
public final class OcrScaleSmoke extends Instrumentation {
    private static final String[] SOURCE = {
        "明天下午三点开会，请提前确认时间。",
        "测试订单AB123共8000元，周五18:30前确认。",
        "先做安卓版本，iOS以后再说。",
        "不是现在就定，我还需要再考虑一下。"
    };
    private static final int[] SIZES = {24, 32, 44, 48};
    private int checks;
    private void require(boolean value) { checks++; if (!value) throw new AssertionError("scale-precondition"); }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    /** SDK完成前不回收输入；中断只阻止后续样本，不能提前释放真实调用。 */
    private Text recognize(TextRecognizer reader, Bitmap image) throws Exception {
        Task<Text> task = reader.process(InputImage.fromBitmap(image, 0));
        boolean interrupted = false;
        try {
            Text result;
            for (;;) {
                try { result = Tasks.await(task); break; }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted) throw new InterruptedException();
            return result;
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private Bitmap fixture(String text, int size, boolean green) {
        Bitmap image = Bitmap.createBitmap(644, 264, Bitmap.Config.ARGB_8888);
        boolean complete = false;
        try {
            Canvas canvas = new Canvas(image); canvas.drawColor(Color.WHITE);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(green ? Color.rgb(149,236,105) : Color.WHITE);
            canvas.drawRect(12, 12, 632, 252, paint);
            paint.setColor(Color.BLACK); paint.setTextSize(size);
            int offset = 0, row = 0;
            while (offset < text.length()) {
                int count = paint.breakText(text, offset, text.length(), true, 580, null);
                int baseline = 32 + size + row * (size + 12);
                require(count > 0 && baseline < 240);
                canvas.drawText(text, offset, offset + count, 32, baseline, paint);
                offset += count; row++;
            }
            complete = true; return image;
        } finally { if (!complete) image.recycle(); }
    }

    private static String compact(String text) { return text.replaceAll("\\s+", ""); }

    @Override public void onStart() {
        Bundle output = new Bundle(); TextRecognizer reader = null;
        try {
            require(!AssistantCaptureService.running() && !CaptureService.running() && !ChatGateService.enabled());
            reader = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            Bitmap warm = fixture(SOURCE[0], 44, false);
            try { recognize(reader, warm); recognize(reader, warm); } finally { warm.recycle(); }
            long[] originalTimes = new long[48], scaledTimes = new long[48];
            int differences = 0, originalErrors = 0, scaledErrors = 0, index = 0;
            for (int round = 0; round < 3; round++) for (int size : SIZES) for (int sample = 0; sample < SOURCE.length; sample++) {
                require(!Thread.currentThread().isInterrupted());
                Bitmap original = fixture(SOURCE[sample], size, sample % 2 == 1);
                Bitmap scaled = null;
                try {
                    String[] texts = new String[2];
                    for (int order = 0; order < 2; order++) {
                        boolean reduced = ((round + sample + order) & 1) == 1;
                        long start = SystemClock.elapsedRealtimeNanos();
                        if (reduced) scaled = Bitmap.createScaledBitmap(original, 483, 198, true);
                        texts[reduced ? 1 : 0] = compact(recognize(reader, reduced ? scaled : original).getText());
                        long elapsed = SystemClock.elapsedRealtimeNanos() - start;
                        if (reduced) scaledTimes[index] = elapsed; else originalTimes[index] = elapsed;
                    }
                    boolean differs = !texts[0].equals(texts[1]);
                    boolean originalWrong = !texts[0].equals(compact(SOURCE[sample]));
                    boolean scaledWrong = !texts[1].equals(compact(SOURCE[sample]));
                    if (differs) differences++;
                    if (originalWrong) originalErrors++;
                    if (scaledWrong) scaledErrors++;
                    Bundle sampleResult = new Bundle();
                    sampleResult.putString("scaleSample", "round="+round+" sample="+sample+" fontPx="+size
                            +" originalUs="+originalTimes[index]/1000+" scaledUs="+scaledTimes[index]/1000
                            +" differs="+differs+" originalError="+originalWrong+" scaledError="+scaledWrong);
                    sendStatus(2, sampleResult); index++;
                } finally { if (scaled != null) scaled.recycle(); original.recycle(); }
            }
            Arrays.sort(originalTimes); Arrays.sort(scaledTimes);
            output.putString("result", "COMPLETED scaleChecks="+checks+" samples="+index+" differences="+differences
                    +" originalErrors="+originalErrors+" scaledErrors="+scaledErrors
                    +" originalMedianUs="+originalTimes[24]/1000+" scaledMedianUs="+scaledTimes[24]/1000
                    +" originalMaxUs="+originalTimes[47]/1000+" scaledMaxUs="+scaledTimes[47]/1000
                    +" productionEligible=false realImages=0");
        } catch (Throwable failure) {
            output.putString("result", "FAIL scaleChecks="+checks+" type="+failure.getClass().getSimpleName());
        } finally { if (reader != null) reader.close(); }
        finish(output.getString("result").startsWith("COMPLETED") ? -1 : 0, output);
    }
}
