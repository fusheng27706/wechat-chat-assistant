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

/** 固定同图/相同停留的交替配对，使用正式OCR与队列；不模拟真实来源或完整服务。 */
public final class PairedIdleOcrSmoke extends Instrumentation {
    private int checks;
    private long idleDelayMs = 600;
    private int idlePhaseLimit = 2;
    private static final class Reading {
        List<ChatMessage> messages;
        BubbleOcr.Stats stats;
        long millis;
    }
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        idleDelayMs=Long.parseLong(args.getString("idle_delay_ms","600"));
        idlePhaseLimit=Integer.parseInt(args.getString("idle_phase_limit","2"));
        if(idleDelayMs<600||idleDelayMs>5000||idlePhaseLimit<1||idlePhaseLimit>2)
            throw new IllegalArgumentException("idle-test-bounds");
        start();
    }
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
    private static final class Arm {
        Reading prime, future;
        long waitMs, scanMs, idleMs;
        int idleFresh, idleHits, phases;
        boolean cancelled;
        int chineseCalls;
    }
    private Arm arm(BubbleOcr ocr, java.util.concurrent.atomic.AtomicInteger calls,
                    Bitmap base, int[] pixels, Bitmap future, boolean predict, long dwell) throws Throwable {
        runOnMainSync(ocr::clearCache);
        Arm arm=new Arm();
        arm.prime=read(ocr,base,boxes(base,0));
        require(arm.prime.stats.freshBubbles==6,"independent-cold-prime");
        int before=calls.get();
        long due=SystemClock.elapsedRealtime()+dwell;
        if(predict) {
            var constructor=ChatLayout.class.getDeclaredConstructor(int.class,int.class,int.class,List.class,boolean.class);
            constructor.setAccessible(true);
            ChatLayout layout=constructor.newInstance(0,80,1500,java.util.Collections.emptyList(),false);
            CountDownLatch released=new CountDownLatch(1);
            AtomicReference<Throwable> error=new AtomicReference<>();
            android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
            AtomicReference<IdlePhaseQueue> queue=new AtomicReference<>();
            Runnable poll=new Runnable(){public void run(){IdlePhaseQueue q=queue.get();q.poll();if(!q.finished())handler.postDelayed(this,50);}};
            Runnable deadline=()->{IdlePhaseQueue q=queue.get();if(!q.finished()){arm.cancelled=true;q.cancel();}};
            try {
                runOnMainSync(()-> {
                    queue.set(new IdlePhaseQueue(SystemClock::elapsedRealtime,()->true,(phase,ticket)->{
                        long scanAt=SystemClock.elapsedRealtime();
                        List<ChatLayout.Box> selected=layout.phaseBubbles(base.getWidth(),2.75f,(x,y)->pixels[y*base.getWidth()+x],phase);
                        arm.scanMs+=SystemClock.elapsedRealtime()-scanAt;
                        long started=SystemClock.elapsedRealtime();
                        arm.phases++;
                        ocr.read(base,selected,2.75f,ticket::current,new BubbleOcr.Callback(){
                            private void finish(){arm.idleMs+=SystemClock.elapsedRealtime()-started;ticket.complete();}
                            public void success(List<ChatMessage> messages,BubbleOcr.Stats stats){arm.idleFresh+=stats.freshBubbles;arm.idleHits+=stats.cacheHits;finish();}
                            public void cancelled(){finish();}
                            public void failure(){error.set(new AssertionError("idle-sdk-failure"));finish();}
                        });
                    },released::countDown,idleDelayMs,idlePhaseLimit));
                    handler.post(poll);handler.postDelayed(deadline,Math.max(0,due-SystemClock.elapsedRealtime()));
                });
                boolean interrupted=false;
                for(;;){try{released.await();break;}catch(InterruptedException ignored){interrupted=true;}}
                if(interrupted){Thread.currentThread().interrupt();throw new InterruptedException();}
                if(error.get()!=null)throw error.get();
            } finally {
                runOnMainSync(()->handler.removeCallbacksAndMessages(null));
            }
        }
        SystemClock.sleep(Math.max(0,due-SystemClock.elapsedRealtime()));
        arm.future=read(ocr,future,boxes(future,0));
        arm.waitMs=SystemClock.elapsedRealtime()-due;
        arm.chineseCalls=calls.get()-before;
        return arm;
    }

    private TextRecognizer counting(TextRecognizer real, java.util.concurrent.atomic.AtomicInteger calls) {
        return (TextRecognizer)java.lang.reflect.Proxy.newProxyInstance(TextRecognizer.class.getClassLoader(),
                new Class<?>[]{TextRecognizer.class},(proxy,method,args)->{
                    if(method.getName().equals("process"))calls.incrementAndGet();
                    try{return method.invoke(real,args);}
                    catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                });
    }
    @Override public void onStart() {
        Bundle result=new Bundle();TextRecognizer chinese=null;BubbleOcr normal=null,predict=null;Bitmap base=null;
        int[] pixels=null;
        int pairs=0,differences=0;long ordinaryWait=0,predictedWait=0,extraMs=0;
        try {
            require(!AssistantCaptureService.running()&&!CaptureService.running()&&!ChatGateService.enabled(),"isolated");
            chinese=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            java.util.concurrent.atomic.AtomicInteger normalCalls=new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger predictedCalls=new java.util.concurrent.atomic.AtomicInteger();
            normal=new BubbleOcr(counting(chinese,normalCalls));predict=new BubbleOcr(counting(chinese,predictedCalls));base=image(0);
            // 正式服务在正文识别前已持有像素。两组共用同一固定只读源，避免仅候选在future到达时清零整图。
            pixels=new int[base.getWidth()*base.getHeight()];base.getPixels(pixels,0,base.getWidth(),0,0,base.getWidth(),base.getHeight());
            // 两个Latin实例与共享Chinese均先运行完整图，预热不计入配对。
            read(normal,base,boxes(base,0));read(predict,base,boxes(base,0));
            for(int pass=0;pass<2;pass++)for(int index=0;index<4;index++) {
                int shift=new int[]{1,2,4,5}[index];long dwell=pass==0?2000:800;
                Bitmap future=image(shift);
                try {
                    boolean predictionFirst=(index+pass)%2==0;
                    Arm a,b;
                    if(predictionFirst){b=arm(predict,predictedCalls,base,pixels,future,true,dwell);a=arm(normal,normalCalls,base,pixels,future,false,dwell);}
                    else{a=arm(normal,normalCalls,base,pixels,future,false,dwell);b=arm(predict,predictedCalls,base,pixels,future,true,dwell);}
                    require(a.future.messages.size()==6&&b.future.messages.size()==6,"six-complete-future-results");
                    boolean equal=sameReading(a.future,b.future);if(!equal)differences++;
                    ordinaryWait+=a.waitMs;predictedWait+=b.waitMs;extraMs+=b.idleMs+b.scanMs;pairs++;
                    Bundle line=new Bundle();line.putString("pairedIdle","pair="+pairs+" shift="+shift+" dwellMs="+dwell
                        +" idleDelayMs="+idleDelayMs+" idlePhaseLimit="+idlePhaseLimit
                        +" predictionFirst="+predictionFirst+" baselineWaitMs="+a.waitMs+" candidateWaitMs="+b.waitMs
                        +" baselinePrimeMs="+a.prime.millis+" candidatePrimeMs="+b.prime.millis
                        +" baselineFutureOcrMs="+a.future.millis+" candidateFutureOcrMs="+b.future.millis
                        +" idleMs="+b.idleMs+" scanMs="+b.scanMs+" phases="+b.phases+" cancelled="+b.cancelled
                        +" baselineWorkMs="+a.future.millis+" candidateWorkMs="+(b.idleMs+b.scanMs+b.future.millis)
                        +" baselineFutureFresh="+a.future.stats.freshBubbles+" candidateFutureFresh="+b.future.stats.freshBubbles
                        +" baselineChineseCalls="+a.chineseCalls+" candidateChineseCalls="+b.chineseCalls
                        +" idleCompletedFresh="+b.idleFresh+" idleCompletedHits="+b.idleHits+" equal="+equal);
                    sendStatus(2,line);
                } finally {future.recycle();}
            }
            result.putString("result","COMPLETED pairedIdleChecks="+checks+" pairs="+pairs+" differences="+differences
                +" idleDelayMs="+idleDelayMs+" idlePhaseLimit="+idlePhaseLimit
                +" baselineWaitMs="+ordinaryWait+" candidateWaitMs="+predictedWait+" extraIdleAndScanMs="+extraMs
                +" productionQueue=true sourceAlwaysTrue=true fixedImages=true fullService=false");
        }catch(Throwable failure){result.putString("result","FAIL pairedIdleChecks="+checks+" pairs="+pairs
                +" idleDelayMs="+idleDelayMs+" idlePhaseLimit="+idlePhaseLimit+" type="+failure.getClass().getSimpleName());}
        finally {
            if(normal!=null){BubbleOcr value=normal;runOnMainSync(value::close);}
            if(predict!=null){BubbleOcr value=predict;runOnMainSync(value::close);}
            if(chinese!=null)chinese.close();if(base!=null)base.recycle();
            if(pixels!=null)java.util.Arrays.fill(pixels,0);
        }
        finish(result.getString("result").startsWith("COMPLETED")?-1:0,result);
    }
}
