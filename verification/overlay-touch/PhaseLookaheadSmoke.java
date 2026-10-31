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

/** 固定六气泡验证相位预计算成本与未来精确命中；不采集微信、不实现生产调度。 */
public final class PhaseLookaheadSmoke extends Instrumentation {
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
    private List<ChatLayout.Box> boxes(Bitmap image,int phase) throws Exception {
        var method=ChatLayout.class.getDeclaredMethod("findBubbles",int.class,int.class,int.class,float.class,ChatLayout.Pixels.class,boolean.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked") List<ChatLayout.Box> found=(List<ChatLayout.Box>)method.invoke(null,1080,80+phase,1500,2.75f,(ChatLayout.Pixels)image::getPixel,false);
        return found;
    }
    private Bitmap image(int shift) {
        Bitmap image=Bitmap.createBitmap(1080,1600,Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas=new Canvas(image);canvas.drawColor(0xffededed);
            Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            String[] text={"测试订单AB123共8000元","明天下午三点确认时间","先确认需要交付的功能","我整理一下需求发给你","好的，收到后我们再沟通","请把时间和费用一起说明"};
            for(int i=0;i<6;i++) {
                int x=210,y=180+i*190+shift;
                paint.setColor(i%2==0?Color.WHITE:0xff95ec69);
                canvas.drawRoundRect(x,y,x+650,y+170,8,8,paint);
                paint.setColor(Color.BLACK);paint.setTextSize(32);
                require(paint.measureText(text[i])<600,"fixed-text-fits");
                canvas.drawText(text[i],x+22,y+80,paint);
                if(i==4){paint.setColor(0xffff2020);canvas.drawRect(x+260,y+110,x+300,y+150,paint);}
            }
            return image;
        } catch(Throwable failure) {image.recycle();throw failure;}
    }
    private boolean sameReading(Reading a,Reading b) {
        if(a.messages.size()!=b.messages.size())return false;
        for(int i=0;i<a.messages.size();i++)if(!same(a.messages.get(i),b.messages.get(i))
                ||a.messages.get(i).top!=b.messages.get(i).top)return false;
        return true;
    }
    @Override public void onStart() {
        Bundle result=new Bundle();TextRecognizer chinese=null;BubbleOcr warm=null,cold=null,ordinary=null;Bitmap base=null;
        int futureCases=0,differences=0,hits=0,fresh=0;long initialMs=0,lookaheadMs=0,coldMs=0,warmMs=0;
        int ordinaryHits=0,ordinaryFresh=0,ordinaryDifferences=0;long ordinaryMs=0,ordinaryInitialMs=0,scanMs=0;
        try {
            require(!AssistantCaptureService.running()&&!CaptureService.running()&&!ChatGateService.enabled(),"isolated");
            chinese=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            warm=new BubbleOcr(chinese);cold=new BubbleOcr(chinese);ordinary=new BubbleOcr(chinese);BubbleOcr baseline=cold;
            base=image(0);
            int predictedFresh=0,predictedHits=0;
            for(int phase=0;phase<3;phase++) {
                long scanAt=SystemClock.elapsedRealtime();
                List<ChatLayout.Box> selected=boxes(base,phase);
                if(phase>0)scanMs+=SystemClock.elapsedRealtime()-scanAt;
                require(selected.size()==6,"six-predicted-bubbles");
                Reading prepared=read(warm,base,selected);require(prepared.messages.size()==6,"six-prepared-results");
                if(phase==0) {
                    initialMs=prepared.millis;
                    Reading normal=read(ordinary,base,selected);require(normal.messages.size()==6,"six-ordinary-prime-results");
                    ordinaryInitialMs=normal.millis;
                } else lookaheadMs+=prepared.millis;
                predictedFresh+=prepared.stats.freshBubbles;predictedHits+=prepared.stats.cacheHits;
                Bundle line=new Bundle();line.putString("phasePrepare","phase="+phase+" millis="+prepared.millis
                        +" fresh="+prepared.stats.freshBubbles+" hits="+prepared.stats.cacheHits);sendStatus(2,line);
            }
            for(int shift=0;shift<6;shift++) {
                Bitmap future=image(shift);
                try {
                    List<ChatLayout.Box> selected=boxes(future,0);require(selected.size()==6,"six-future-bubbles");
                    runOnMainSync(baseline::clearCache);
                    Reading a,b;
                    if(shift%2==0){a=read(cold,future,selected);b=read(warm,future,selected);}
                    else {b=read(warm,future,selected);a=read(cold,future,selected);}
                    Reading normal=read(ordinary,future,selected);
                    require(a.messages.size()==6&&b.messages.size()==6&&normal.messages.size()==6,"six-future-results-all-paths");
                    require(a.stats.freshBubbles==6,"cold-reference-fully-fresh");
                    ordinaryHits+=normal.stats.cacheHits;ordinaryFresh+=normal.stats.freshBubbles;ordinaryMs+=normal.millis;
                    boolean normalDiffers=!sameReading(a,normal);if(normalDiffers)ordinaryDifferences++;
                    boolean differs=!sameReading(a,b);if(differs)differences++;
                    hits+=b.stats.cacheHits;fresh+=b.stats.freshBubbles;coldMs+=a.millis;warmMs+=b.millis;futureCases++;
                    Bundle line=new Bundle();line.putString("phaseFuture","shift="+shift+" differs="+differs+" coldMs="+a.millis
                            +" warmMs="+b.millis+" hits="+b.stats.cacheHits+" fresh="+b.stats.freshBubbles
                            +" coldMessages="+a.messages.size()+" warmMessages="+b.messages.size()
                            +" ordinaryMs="+normal.millis+" ordinaryHits="+normal.stats.cacheHits+" ordinaryFresh="+normal.stats.freshBubbles
                            +" ordinaryDiffers="+normalDiffers);sendStatus(2,line);
                } finally {future.recycle();}
            }
            result.putString("result","COMPLETED phaseChecks="+checks+" futureCases="+futureCases+" differences="+differences
                    +" futureHits="+hits+" futureFresh="+fresh+" initialMs="+initialMs+" extraLookaheadMs="+lookaheadMs
                    +" predictedFresh="+predictedFresh+" predictedHits="+predictedHits+" totalColdMs="+coldMs+" totalWarmMs="+warmMs
                    +" extraScanMs="+scanMs+" ordinaryInitialMs="+ordinaryInitialMs+" ordinaryTotalMs="+ordinaryMs
                    +" ordinaryHits="+ordinaryHits+" ordinaryFresh="+ordinaryFresh+" ordinaryDifferences="+ordinaryDifferences
                    +" noFutureLearning="+(fresh==0)
                    +" realImages=0 sourceAlwaysTrue=true productionEligible=false");
        } catch(Throwable failure) {result.putString("result","FAIL phaseChecks="+checks+" futureCases="+futureCases+" type="+failure.getClass().getSimpleName());}
        finally {
            if(warm!=null){BubbleOcr value=warm;runOnMainSync(value::close);}if(cold!=null){BubbleOcr value=cold;runOnMainSync(value::close);}
            if(ordinary!=null){BubbleOcr value=ordinary;runOnMainSync(value::close);}
            if(chinese!=null)chinese.close();if(base!=null)base.recycle();
        }
        finish(result.getString("result").startsWith("COMPLETED")?-1:0,result);
    }
}
