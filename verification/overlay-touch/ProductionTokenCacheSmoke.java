package com.codextemp.wechatcaptureprobe;

import android.app.Instrumentation;
import android.graphics.*;
import android.os.*;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 固定图真实SDK对照；改变气泡远端单点，只检验完整英文输入是否可精确复用。 */
public final class ProductionTokenCacheSmoke extends Instrumentation {
    private int checks;
    private void require(boolean value, String name) { checks++; if (!value) throw new AssertionError(name); }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private static final class Reading {
        List<ChatMessage> messages;
        int fresh, calls, hits, budget;
        long millis;
    }
    private Bitmap image(int variant, boolean changedToken) {
        Bitmap image = Bitmap.createBitmap(720, 220, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image); canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.BLACK); paint.setTextSize(32);
        canvas.drawText(changedToken ? "测试订单 AB124 共 8000 元" : "测试订单 AB123 共 8000 元", 24, 75, paint);
        image.setPixel(650 + variant, 190, Color.BLACK);
        return image;
    }
    private Reading read(PreTokenBubbleOcr baseline, BubbleOcr candidate, Bitmap image) throws Exception {
        Reading value = new Reading(); AtomicReference<String> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1); long start = SystemClock.elapsedRealtime();
        List<ChatLayout.Box> boxes = Collections.singletonList(new ChatLayout.Box(0, 0, 720, 220, false));
        runOnMainSync(() -> {
            if (candidate == null) baseline.read(image, boxes, 2.75f, () -> true, new PreTokenBubbleOcr.Callback() {
                public void success(List<ChatMessage> messages, PreTokenBubbleOcr.Stats stats) {
                    value.messages=messages; value.fresh=stats.freshBubbles; value.calls=stats.latinCalls;
                    value.budget=stats.latinBudgetUsed; done.countDown();
                }
                public void cancelled(){error.set("baseline-cancelled");done.countDown();}
                public void failure(){error.set("baseline-failed");done.countDown();}
            });
            else candidate.read(image, boxes, 2.75f, () -> true, new BubbleOcr.Callback() {
                public void success(List<ChatMessage> messages, BubbleOcr.Stats stats) {
                    value.messages=messages; value.fresh=stats.freshBubbles; value.calls=stats.latinCalls;
                    value.hits=stats.latinTokenHits; value.budget=stats.latinBudgetUsed; done.countDown();
                }
                public void cancelled(){error.set("candidate-cancelled");done.countDown();}
                public void failure(){error.set("candidate-failed");done.countDown();}
            });
        });
        // 不能因观测超时回收仍由SDK/快照持有的父图；由外部记录活跃进程并决定停止。
        boolean interrupted = false;
        for (;;) {
            try { done.await(); break; }
            catch (InterruptedException cancelled) { interrupted = true; }
        }
        if (interrupted) { Thread.currentThread().interrupt(); throw new InterruptedException(); }
        if(error.get()!=null) throw new IllegalStateException(error.get());
        value.millis=SystemClock.elapsedRealtime()-start;
        return value;
    }
    private boolean same(Reading a, Reading b) {
        if(a.messages.size()!=b.messages.size())return false;
        for(int i=0;i<a.messages.size();i++) {
            ChatMessage x=a.messages.get(i), y=b.messages.get(i);
            if(!x.text.equals(y.text)||!x.warning.equals(y.warning)||x.top!=y.top
                    ||x.outgoing!=y.outgoing||x.visualFingerprint!=y.visualFingerprint)return false;
        }
        return true;
    }
    @Override public void onStart() {
        Bundle result=new Bundle(); TextRecognizer chinese=null; PreTokenBubbleOcr baseline=null; BubbleOcr candidate=null;
        long baselineMs=0,candidateMs=0; int hits=0, calls=0, differences=0;
        try {
            require(!AssistantCaptureService.running()&&!CaptureService.running()&&!ChatGateService.enabled(),"isolated-no-capture");
            chinese=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            baseline=new PreTokenBubbleOcr(chinese); candidate=new BubbleOcr(chinese);
            for(int i=0;i<13;i++) {
                if(i==9) { BubbleOcr instance=candidate;runOnMainSync(instance::clearCache); }
                if(i==10||i==11) {
                    BubbleOcr instance=candidate;runOnMainSync(instance::quarantineCache);
                    if(i==11)SystemClock.sleep(5100);
                }
                Bitmap image=image(i==12?11:i,i==8);
                try {
                    Reading a,b;
                    if(i%2==0) {a=read(baseline,null,image);b=read(null,candidate,image);}
                    else {b=read(null,candidate,image);a=read(baseline,null,image);}
                    require(a.fresh==(i==12?0:1)&&b.fresh==(i==12?0:1),"full-bubble-cache-state-"+i);
                    require(a.messages.size()==1&&b.messages.size()==1,"one-message-"+i);
                    require(a.budget==b.budget,"logical-latin-budget-"+i);
                    if(!same(a,b))differences++;
                    if(i>=2&&i<8){baselineMs+=a.millis;candidateMs+=b.millis;hits+=b.hits;calls+=a.calls;}
                    if(i==8||i==9)require(b.hits==0&&b.calls>0,"changed-token-or-clear-misses-"+i);
                    if(i==10)require(b.hits>0&&b.calls==0,"quarantined-exact-token-reactivates");
                    if(i==11)require(b.hits==0&&b.calls>0,"expired-token-never-reactivates");
                    if(i==12)require(a.calls==0&&b.calls==0&&b.budget>0,"whole-hit-preserves-logical-budget");
                    if(i==7) {
                        Reading again=read(null,candidate,image);
                        require(b.hits>0&&again.fresh==0&&again.calls==0&&again.budget==b.budget&&same(b,again),
                                "token-derived-whole-result-keeps-budget-and-output");
                    }
                    Bundle sample=new Bundle();sample.putString("tokenSample","case="+i+" baseMs="+a.millis
                            +" candidateMs="+b.millis+" baseLatin="+a.calls+" candidateLatin="+b.calls
                            +" tokenHits="+b.hits+" equal="+same(a,b));sendStatus(2,sample);
                } finally { image.recycle(); }
            }
            require(hits>0&&calls>0,"actual-token-reuse-exercised");
            require(differences==0,"complete-output-equality");
            result.putString("result","PASS tokenChecks="+checks+" measuredCases=6 baselineMs="+baselineMs
                    +" candidateMs="+candidateMs+" tokenHits="+hits+" differences="+differences
                    +" fixedSource=true production=true baselineFrozen=true");
        } catch(Throwable failure) {
            result.putString("result","FAIL tokenChecks="+checks+" differences="+differences
                    +" type="+failure.getClass().getSimpleName()+" check="+failure.getMessage());
        } finally {
            PreTokenBubbleOcr old=baseline;BubbleOcr next=candidate;
            runOnMainSync(()->{if(old!=null)old.close();if(next!=null)next.close();});
            if(chinese!=null)chinese.close();
        }
        finish(result.getString("result").startsWith("PASS")?-1:0,result);
    }
}
