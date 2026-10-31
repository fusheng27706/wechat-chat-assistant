package com.codextemp.wechatcaptureprobe;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.google.android.gms.tasks.*;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 固定图测试：前台请求撤销空闲读取，只有真实终态后才能复用同一个OCR实例。 */
public final class OcrYieldSmoke extends Instrumentation {
    private int checks;
    private void require(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    public void onCreate(Bundle args){super.onCreate(args);start();}
    private Bitmap image(){
        Bitmap result=Bitmap.createBitmap(650,300,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(result);canvas.drawColor(Color.WHITE);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.BLACK);paint.setTextSize(32);
        canvas.drawText("明天下午三点确认时间",20,70,paint);return result;
    }
    private void scenario(TextRecognizer real,Text blank,boolean controlled,boolean sdkFirst,int round)throws Exception {
        Bitmap idleImage=image(),foregroundImage=image();
        ExecutorService snapshotWorker=Executors.newSingleThreadExecutor();
        CountDownLatch submitted=new CountDownLatch(1),nextEntered=new CountDownLatch(1),releaseNext=new CountDownLatch(1),done=new CountDownLatch(1);
        AtomicBoolean idleCurrent=new AtomicBoolean(true),nextEnded=new AtomicBoolean(),foregroundStarted=new AtomicBoolean();
        AtomicBoolean pendingAtRequest=new AtomicBoolean();
        AtomicInteger copies=new AtomicInteger(),calls=new AtomicInteger(),active=new AtomicInteger(),maximum=new AtomicInteger(),idleTerminals=new AtomicInteger(),foregroundTerminals=new AtomicInteger();
        AtomicLong requestAt=new AtomicLong(-1),sdkEndedAt=new AtomicLong(-1),idleEndedAt=new AtomicLong(-1),foregroundSdkAt=new AtomicLong(-1);
        AtomicReference<String> failure=new AtomicReference<>();
        TaskCompletionSource<Text> held=new TaskCompletionSource<>();
        Handler main=new Handler(Looper.getMainLooper());
        TextRecognizer proxy=(TextRecognizer)Proxy.newProxyInstance(TextRecognizer.class.getClassLoader(),new Class<?>[]{TextRecognizer.class},(object,method,args)->{
            if(!method.getName().equals("process"))return null;
            int call=calls.incrementAndGet();maximum.accumulateAndGet(active.incrementAndGet(),Math::max);
            if(call>1)foregroundSdkAt.compareAndSet(-1,SystemClock.elapsedRealtime());
            Task<Text> task;
            if(controlled)task=call==1?held.getTask():Tasks.forResult(blank);
            else {
                try {@SuppressWarnings("unchecked") Task<Text> value=(Task<Text>)method.invoke(real,args);task=value;}
                catch(InvocationTargetException error){throw error.getCause();}
            }
            task.addOnCompleteListener(Runnable::run,ignored->{active.decrementAndGet();if(call==1)sdkEndedAt.set(SystemClock.elapsedRealtime());});
            if(call==1){submitted.countDown();if(!controlled)main.post(()->{
                pendingAtRequest.set(!task.isComplete());requestAt.set(SystemClock.elapsedRealtime());idleCurrent.set(false);
            });}
            return task;
        });
        BubbleOcr ocr=new BubbleOcr(proxy,new BubblePreparation(),snapshotWorker,(screen,box,density)->{
            if(copies.incrementAndGet()==2){
                nextEntered.countDown();
                try {
                    if(controlled&&!releaseNext.await(10,TimeUnit.SECONDS))throw new IllegalStateException("fixed-next-timeout");
                    return BubbleOcr.snapshot(screen,box,density);
                }catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
                finally {nextEnded.set(true);}
            }
            return BubbleOcr.snapshot(screen,box,density);
        });
        BubbleOcr.Callback foreground=new BubbleOcr.Callback(){
            void end(String error){foregroundTerminals.incrementAndGet();if(error!=null)failure.set(error);foregroundImage.recycle();done.countDown();}
            public void success(List<ChatMessage> values,BubbleOcr.Stats stats){end(values.size()==1?null:"foreground-message-count");}
            public void cancelled(){end("foreground-cancelled");}public void failure(){end("foreground-failure");}
        };
        try {
            runOnMainSync(()->ocr.read(idleImage,Arrays.asList(new ChatLayout.Box(0,0,650,120,false),new ChatLayout.Box(0,150,650,280,true)),2.75f,idleCurrent::get,new BubbleOcr.Callback(){
                void end(String error){
                    idleTerminals.incrementAndGet();idleEndedAt.set(SystemClock.elapsedRealtime());
                    if(error!=null)failure.set(error);
                    if(active.get()!=0||!nextEnded.get())failure.set("idle-ended-before-sdk-or-parent-read");
                    idleImage.recycle();
                    foregroundStarted.set(true);
                    ocr.read(foregroundImage,Collections.singletonList(new ChatLayout.Box(0,0,650,120,false)),2.75f,()->true,foreground);
                }
                public void success(List<ChatMessage> values,BubbleOcr.Stats stats){end("idle-published-after-request");}
                public void cancelled(){end(null);}public void failure(){end("idle-failed");}
            }));
            require(submitted.await(5,TimeUnit.SECONDS),"idle-sdk-submitted");
            require(nextEntered.await(5,TimeUnit.SECONDS),"next-snapshot-submitted");
            if(controlled){
                runOnMainSync(()->{pendingAtRequest.set(!held.getTask().isComplete());requestAt.set(SystemClock.elapsedRealtime());idleCurrent.set(false);});
                SystemClock.sleep(400);
                require(active.get()==1&&!foregroundStarted.get()&&!idleImage.isRecycled(),"held-sdk-not-preemptively-released");
                if(sdkFirst){
                    runOnMainSync(()->held.setResult(blank));runOnMainSync(()->{});
                    require(!foregroundStarted.get()&&!idleImage.isRecycled(),"next-snapshot-still-owns-parent");
                    releaseNext.countDown();
                }else{
                    releaseNext.countDown();snapshotWorker.submit(()->{}).get(5,TimeUnit.SECONDS);runOnMainSync(()->{});
                    require(!foregroundStarted.get()&&!idleImage.isRecycled(),"sdk-still-owns-slot");
                    runOnMainSync(()->held.setResult(blank));
                }
            }
            require(done.await(15,TimeUnit.SECONDS),"foreground-completed");runOnMainSync(()->{});
            require(failure.get()==null,"handoff-"+failure.get());
            require(idleTerminals.get()==1&&foregroundTerminals.get()==1&&calls.get()==2&&maximum.get()==1&&active.get()==0,"unique-terminals-and-single-chinese-slot");
            require(pendingAtRequest.get()&&requestAt.get()>=0&&sdkEndedAt.get()>=requestAt.get()
                    &&idleEndedAt.get()>=sdkEndedAt.get()&&foregroundSdkAt.get()>=idleEndedAt.get(),"request-in-flight-and-foreground-after-terminal");
            require(idleImage.isRecycled()&&foregroundImage.isRecycled(),"parents-reclaimed");
            Bundle line=new Bundle();line.putString("yieldSample","controlled="+controlled+" sdkFirst="+sdkFirst+" round="+round
                    +" sdkResidualMs="+(sdkEndedAt.get()-requestAt.get())+" idleTerminalMs="+(idleEndedAt.get()-requestAt.get())
                    +" foregroundSdkDelayMs="+(foregroundSdkAt.get()-requestAt.get())+" pendingAtRequest="+pendingAtRequest.get()
                    +" maxChinese="+maximum.get());sendStatus(2,line);
        }finally{
            releaseNext.countDown();
            runOnMainSync(()->{idleCurrent.set(false);ocr.close();held.trySetException(new IllegalStateException("fixed-cleanup"));});
            require(snapshotWorker.awaitTermination(10,TimeUnit.SECONDS),"snapshot-worker-ended");runOnMainSync(()->{});
            // 失败时不得凭超时回收SDK仍持有的父图；进程终止负责剩余资源。
            if(active.get()==0&&idleTerminals.get()>0&&!idleImage.isRecycled())idleImage.recycle();
            if(active.get()==0&&foregroundTerminals.get()>0&&!foregroundImage.isRecycled())foregroundImage.recycle();
        }
    }
    public void onStart(){Bundle result=new Bundle();TextRecognizer real=null;Bitmap blankImage=null;
        try{
            require(!AssistantCaptureService.running()&&!CaptureService.running()&&!ChatGateService.enabled(),"isolated");
            real=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            blankImage=Bitmap.createBitmap(180,100,Bitmap.Config.ARGB_8888);blankImage.eraseColor(Color.WHITE);
            Text blank=Tasks.await(real.process(InputImage.fromBitmap(blankImage,0)));
            require(blank.getText().isEmpty(),"fixed-empty-result");
            scenario(real,blank,true,true,0);scenario(real,blank,true,false,1);
            for(int i=0;i<6;i++)scenario(real,blank,false,false,i);
            result.putString("result","PASS yieldChecks="+checks+" controlledCases=2 realFixedCases=6 realChat=false productionScheduler=false");
        }catch(Throwable failure){result.putString("result","FAIL yieldChecks="+checks+" type="+failure.getClass().getSimpleName()+" check="+failure.getMessage());}
        finally{if(real!=null)real.close();if(blankImage!=null)blankImage.recycle();}
        finish(result.getString("result").startsWith("PASS")?-1:0,result);
    }
}
