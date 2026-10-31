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

/** 固定绘制像素比较裁剪边距下的完整OCR与缓存结果；不采集微信。 */
public final class CropOcrSmoke extends Instrumentation {
    private int checks;
    private static final class Reading {
        List<ChatMessage> messages;
        BubbleOcr.Stats stats;
        long millis;
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private void require(boolean value, String name) { checks++; if (!value) throw new AssertionError(name); }
    private Reading read(BubbleOcr ocr, Bitmap image, List<ChatLayout.Box> boxes) throws Throwable {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Reading reading = new Reading(); long start = SystemClock.elapsedRealtime();
        runOnMainSync(() -> {
            try {
                ocr.read(image, boxes, 2.75f, () -> true, new BubbleOcr.Callback() {
                    public void success(List<ChatMessage> messages, BubbleOcr.Stats stats) {
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
    private static boolean same(ChatMessage a,ChatMessage b) {
        return a.text.equals(b.text)&&a.warning.equals(b.warning)&&a.outgoing==b.outgoing
                &&a.visualFingerprint==b.visualFingerprint;
    }
    @Override public void onStart() {
        Bundle result=new Bundle(); TextRecognizer chinese=null; BubbleOcr ocr=null;
        int cases=0,differences=0,cacheDifferences=0,wrong=0,hits=0;
        String[] samples={"测试订单AB123共8000元","明天下午三点确认时间"};
        try {
            require(!AssistantCaptureService.running()&&!CaptureService.running()&&!ChatGateService.enabled(),"isolated");
            chinese=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            ocr=new BubbleOcr(chinese); BubbleOcr current=ocr;
            for(int size:new int[]{32,44})for(boolean green:new boolean[]{false,true})for(int sample=0;sample<samples.length;sample++) {
                require(!Thread.currentThread().isInterrupted(),"not-interrupted");
                Bitmap image=Bitmap.createBitmap(680,220,Bitmap.Config.ARGB_8888);
                try {
                    Canvas canvas=new Canvas(image);canvas.drawColor(0xffededed);
                    Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(green?0xff95ec69:Color.WHITE);
                    canvas.drawRoundRect(20,20,660,200,8,8,paint);
                    paint.setColor(Color.BLACK);paint.setTextSize(size);
                    require(paint.measureText(samples[sample])<600,"text-fits");
                    canvas.drawText(samples[sample],40,100,paint);
                    Reading[] cold=new Reading[2],warm=new Reading[2];
                    for(int order=0;order<2;order++) {
                        int variant=(cases+order)%2,pad=variant*3;
                        runOnMainSync(current::clearCache);
                        List<ChatLayout.Box> box=java.util.Collections.singletonList(new ChatLayout.Box(20-pad,20-pad,660+pad,200+pad,green));
                        cold[variant]=read(ocr,image,box);warm[variant]=read(ocr,image,box);
                        require(cold[variant].messages.size()==1&&warm[variant].messages.size()==1,"one-message");
                        if(!same(cold[variant].messages.get(0),warm[variant].messages.get(0)))cacheDifferences++;
                        hits+=warm[variant].stats.cacheHits;
                        if(!cold[variant].messages.get(0).text.replaceAll("\\s+", "").equals(samples[sample]))wrong++;
                    }
                    boolean differs=!same(cold[0].messages.get(0),cold[1].messages.get(0));
                    if(differs)differences++;
                    Bundle line=new Bundle();line.putString("cropSample","case="+cases+" fontPx="+size+" green="+green+" differs="+differs
                        +" textDiffers="+!cold[0].messages.get(0).text.equals(cold[1].messages.get(0).text)
                        +" warningDiffers="+!cold[0].messages.get(0).warning.equals(cold[1].messages.get(0).warning)
                        +" oldGraphics="+cold[0].stats.graphics+" newGraphics="+cold[1].stats.graphics
                        +" oldChars="+cold[0].messages.get(0).text.length()+" newChars="+cold[1].messages.get(0).text.length()
                        +" oldMs="+cold[0].millis+" newMs="+cold[1].millis);
                    sendStatus(2,line);cases++;
                } finally { image.recycle(); }
            }
            result.putString("result","COMPLETED cropChecks="+checks+" cases="+cases+" differences="+differences+" cacheDifferences="+cacheDifferences
                +" warmHits="+hits+" strictSourceMismatches="+wrong+" realImages=0 productionEligible=false");
        } catch(Throwable failure) { result.putString("result","FAIL cropChecks="+checks+" type="+failure.getClass().getSimpleName()); }
        finally { if(ocr!=null){BubbleOcr current=ocr;runOnMainSync(current::close);}if(chinese!=null)chinese.close(); }
        finish(result.getString("result").startsWith("COMPLETED")?-1:0,result);
    }
}