package com.codextemp.wechatcaptureprobe;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.SystemClock;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** 固定绘制像素走完整中英文OCR，校验缓存预算语义；不采集微信。 */
public final class AsyncSnapshotCacheSmoke extends Instrumentation {
    private int checks;
    private static final class Reading {
        List<ChatMessage> messages;
        AsyncSnapshotBubbleOcr.Stats stats;
        long millis;
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private void require(boolean value, String name) { checks++; if (!value) throw new AssertionError(name); }
    private Reading read(AsyncSnapshotBubbleOcr ocr, Bitmap image, List<ChatLayout.Box> boxes) throws Throwable {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Reading reading = new Reading(); long start = SystemClock.elapsedRealtime();
        runOnMainSync(() -> {
            try {
                ocr.read(image, boxes, 1f, () -> true, new AsyncSnapshotBubbleOcr.Callback() {
                    public void success(List<ChatMessage> messages, AsyncSnapshotBubbleOcr.Stats stats) {
                        reading.messages = messages; reading.stats = stats; done.countDown();
                    }
                    public void cancelled() { error.set(new AssertionError("unexpected-cancel")); done.countDown(); }
                    public void failure() { error.set(new AssertionError("sdk-failure")); done.countDown(); }
                });
            } catch (Throwable failure) { error.set(failure); done.countDown(); }
        });
        boolean interrupted = false;
        for (;;) {
            try { done.await(); break; } catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) { Thread.currentThread().interrupt(); throw new InterruptedException(); }
        if (error.get() != null) throw error.get();
        reading.millis = SystemClock.elapsedRealtime() - start;
        return reading;
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        TextRecognizer chinese = null; AsyncSnapshotBubbleOcr ocr = null; Bitmap image = null;
        try {
            require(!AssistantCaptureService.running() && !CaptureService.running() && !ChatGateService.enabled(), "isolated");
            chinese = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            ocr = new AsyncSnapshotBubbleOcr(chinese);
            image = Bitmap.createBitmap(600, 1600, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(image); canvas.drawColor(Color.WHITE);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.BLACK); paint.setTextSize(42);
            List<ChatLayout.Box> boxes = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                int top = 20 + i * 130;
                canvas.drawText("SAMPLE " + (100 + i), 40, top + 65, paint);
                boxes.add(new ChatLayout.Box(20, top, 570, top + 100, false));
            }
            paint.setTextSize(32);
            canvas.drawText("确认 Alpha 项目 Beta", 40, 1420, paint);
            ChatLayout.Box mixedBox = new ChatLayout.Box(20, 1350, 570, 1490, false);
            Reading cold = read(ocr, image, boxes);
            require(cold.stats.latinCalls == 8 && cold.stats.latinBudgetUsed == 8, "fixed-fixture-reaches-eight-review-budget");
            AsyncSnapshotBubbleOcr current = ocr;
            runOnMainSync(current::quarantineCache);
            Reading warm = read(ocr, image, boxes);
            require(warm.stats.cacheHits > 0, "actual-cache-hit-after-isolation");
            require(warm.stats.latinCalls < cold.stats.latinCalls, "actual-sdk-calls-reduced");
            require(warm.stats.latinBudgetUsed == cold.stats.latinBudgetUsed, "logical-budget-preserved");
            require(warm.messages.size() == cold.messages.size(), "same-message-count");
            for (int i = 0; i < cold.messages.size(); i++) {
                ChatMessage before = cold.messages.get(i), after = warm.messages.get(i);
                require(before.text.equals(after.text) && before.warning.equals(after.warning)
                        && before.outgoing == after.outgoing && before.top == after.top
                        && before.visualFingerprint == after.visualFingerprint, "full-result-equivalence");
            }
            List<ChatLayout.Box> suffix = new ArrayList<>(boxes.subList(3, 8));
            Reading moved = read(ocr, image, suffix);
            require(moved.stats.cacheHits == 5 && moved.stats.latinCalls == 0
                    && moved.stats.latinBudgetUsed == 5, "complete-results-reused-at-new-budget-position");
            List<ChatLayout.Box> repeated = new ArrayList<>();
            for (int i = 0; i < 10; i++) repeated.add(boxes.get(3));
            Reading limited = read(ocr, image, repeated);
            require(limited.stats.cacheHits == 8 && limited.stats.freshBubbles == 2
                    && limited.stats.latinBudgetUsed == 8, "same-cached-result-cannot-exceed-budget");
            Reading afterLimit = read(ocr, image, java.util.Collections.singletonList(boxes.get(3)));
            require(afterLimit.stats.cacheHits == 1 && afterLimit.stats.latinCalls == 0
                    && afterLimit.stats.latinBudgetUsed == 1, "truncated-reread-does-not-poison-complete-cache");
            runOnMainSync(current::clearCache);
            Reading suffixCold = read(ocr, image, suffix);
            require(suffixCold.stats.cacheHits == 0 && suffixCold.stats.latinCalls == 5, "independent-suffix-cold-control");
            for (int i = 0; i < suffix.size(); i++) {
                ChatMessage before = suffixCold.messages.get(i), after = moved.messages.get(i);
                require(before.text.equals(after.text) && before.warning.equals(after.warning)
                        && before.outgoing == after.outgoing && before.top == after.top
                        && before.visualFingerprint == after.visualFingerprint, "new-budget-position-cold-equivalence");
            }
            runOnMainSync(current::clearCache);
            Reading cleared = read(ocr, image, boxes);
            require(cleared.stats.cacheHits == 0, "hard-clear-forces-fresh-sdk");
            Reading mixedCold = read(ocr, image, java.util.Collections.singletonList(mixedBox));
            int mixedCost = mixedCold.stats.latinBudgetUsed;
            require(mixedCost > 1 && mixedCost < 8 && mixedCold.stats.latinCalls == mixedCost,
                    "mixed-fixture-has-multiple-real-reviews");
            List<ChatLayout.Box> exactBudget = new ArrayList<>();
            for (int i = 0; i < 8 - mixedCost; i++) exactBudget.add(boxes.get(3));
            exactBudget.add(mixedBox);
            Reading exactFit = read(ocr, image, exactBudget);
            require(exactFit.stats.cacheHits == exactBudget.size() && exactFit.stats.latinCalls == 0
                    && exactFit.stats.latinBudgetUsed == 8, "multi-review-cost-exactly-fits");
            List<ChatLayout.Box> shortBudget = new ArrayList<>(exactBudget);
            shortBudget.add(0, boxes.get(3));
            Reading shortFit = read(ocr, image, shortBudget);
            require(shortFit.stats.cacheHits == shortBudget.size() - 1 && shortFit.stats.freshBubbles == 1
                    && shortFit.stats.latinCalls == mixedCost - 1 && shortFit.stats.latinBudgetUsed == 8,
                    "multi-review-one-short-does-not-hit");
            Reading mixedAfter = read(ocr, image, java.util.Collections.singletonList(mixedBox));
            require(mixedAfter.stats.cacheHits == 1 && mixedAfter.stats.latinCalls == 0
                    && mixedAfter.stats.latinBudgetUsed == mixedCost, "partial-mixed-review-does-not-poison-full-cache");
            require(mixedCold.messages.get(0).text.equals(mixedAfter.messages.get(0).text)
                    && mixedCold.messages.get(0).warning.equals(mixedAfter.messages.get(0).warning),
                    "mixed-full-result-retained");
            runOnMainSync(current::clearCache);
            Reading shortCold = read(ocr, image, shortBudget);
            require(shortCold.stats.latinBudgetUsed == 8 && shortCold.messages.size() == shortFit.messages.size(),
                    "independent-short-budget-control");
            for (int i = 0; i < shortCold.messages.size(); i++) {
                ChatMessage before = shortCold.messages.get(i), after = shortFit.messages.get(i);
                require(before.text.equals(after.text) && before.warning.equals(after.warning)
                        && before.top == after.top && before.outgoing == after.outgoing
                        && before.visualFingerprint == after.visualFingerprint, "mixed-short-budget-cold-equivalence");
            }
            result.putString("result", "PASS asyncSnapshotCacheChecks=" + checks + " coldMs=" + cold.millis
                    + " warmMs=" + warm.millis + " warmHits=" + warm.stats.cacheHits
                    + " actualLatinCalls=" + warm.stats.latinCalls + " logicalLatinBudget=" + warm.stats.latinBudgetUsed
                    + " movedHits=" + moved.stats.cacheHits + " movedMs=" + moved.millis + " suffixColdMs=" + suffixCold.millis
                    + " mixedReviewCost=" + mixedCost
                    + " syntheticOnly=true realSdk=true realChat=false");
        } catch (Throwable failure) {
            result.putString("result", "FAIL asyncSnapshotCacheChecks=" + checks + " type=" + failure.getClass().getSimpleName()
                    + (failure instanceof AssertionError ? " check=" + failure.getMessage() : ""));
        } finally {
            if (ocr != null) { AsyncSnapshotBubbleOcr current = ocr; runOnMainSync(current::close); }
            if (chinese != null) chinese.close();
            if (image != null) image.recycle();
            finish(result.getString("result", "FAIL").startsWith("PASS") ? -1 : 0, result);
        }
    }
}
