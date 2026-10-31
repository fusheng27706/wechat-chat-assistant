package com.codextemp.wechatcaptureprobe;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Looper;
import com.google.android.gms.tasks.*;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 当前SDK与下一snapshot并存，终态callback立即回收父图以暴露提前结束。 */
public final class AsyncSnapshotOverlapSmoke extends Instrumentation {
    int checks;
    void require(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    public void onCreate(Bundle args){super.onCreate(args);start();}
    void scenario(String mode,boolean snapshotFirst,Text fixed) throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(1);
        AtomicBoolean current=new AtomicBoolean(true),nextReadEnded=new AtomicBoolean();
        AtomicInteger copies=new AtomicInteger(),sdk=new AtomicInteger(),active=new AtomicInteger(),maximum=new AtomicInteger(),callbacks=new AtomicInteger();
        AtomicReference<String> terminal=new AtomicReference<>();TaskCompletionSource<Text> firstTask=new TaskCompletionSource<>();
        Bitmap image=Bitmap.createBitmap(180,240,Bitmap.Config.ARGB_8888);image.eraseColor(Color.WHITE);
        TextRecognizer recognizer=(TextRecognizer)Proxy.newProxyInstance(TextRecognizer.class.getClassLoader(),new Class<?>[]{TextRecognizer.class},(proxy,method,args)->{
            if(method.getName().equals("process")){
                int call=sdk.incrementAndGet();maximum.accumulateAndGet(active.incrementAndGet(),Math::max);
                Task<Text> task=call==1?firstTask.getTask():Tasks.forResult(fixed);
                task.addOnCompleteListener(Runnable::run,ignored->active.decrementAndGet());return task;
            }return null;
        });
        AsyncSnapshotBubbleOcr ocr=new AsyncSnapshotBubbleOcr(recognizer,new BubblePreparation(),worker,(screen,box,density)->{
            if(copies.incrementAndGet()!=2)return AsyncSnapshotBubbleOcr.snapshot(screen,box,density);
            entered.countDown();
            try{
                if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("hold-timeout");
                if(mode.equals("snapshotFailure"))throw new IllegalStateException("fixed-snapshot-failure");
                return AsyncSnapshotBubbleOcr.snapshot(screen,box,density);
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            finally{nextReadEnded.set(true);}
        });
        try{
            runOnMainSync(()->ocr.read(image,Arrays.asList(new ChatLayout.Box(0,0,180,100,false),new ChatLayout.Box(0,120,180,220,true)),2.75f,current::get,new AsyncSnapshotBubbleOcr.Callback(){
                void end(String value){callbacks.incrementAndGet();terminal.set(value+":"+(Looper.myLooper()==Looper.getMainLooper())+":"+nextReadEnded.get());image.recycle();done.countDown();}
                public void success(List<ChatMessage> m,AsyncSnapshotBubbleOcr.Stats s){end("success"+m.size());}
                public void cancelled(){end("cancelled");}public void failure(){end("failure");}
            }));
            require(entered.await(5,TimeUnit.SECONDS),"next-snapshot-entered");
            require(sdk.get()==1&&active.get()==1&&!image.isRecycled()&&callbacks.get()==0,"sdk-and-snapshot-overlap");
            if(snapshotFirst){release.countDown();worker.submit(()->{}).get(5,TimeUnit.SECONDS);runOnMainSync(()->{});
                require(nextReadEnded.get()&&!image.isRecycled()&&callbacks.get()==0,"snapshot-done-sdk-still-owned");}
            runOnMainSync(()->{
                if(mode.equals("close"))ocr.close();else if(mode.equals("epoch"))ocr.clearCache();else if(mode.equals("current"))current.set(false);
                if(mode.equals("sdkFailure"))firstTask.setException(new IllegalStateException("fixed-sdk-failure"));else firstTask.setResult(fixed);
            });runOnMainSync(()->{});
            if(!snapshotFirst){require(callbacks.get()==0&&!image.isRecycled(),"sdk-done-parent-still-owned");release.countDown();}
            require(done.await(5,TimeUnit.SECONDS),"terminal-after-both");runOnMainSync(()->{});
            String expected=mode.equals("success")?"success2":mode.endsWith("Failure")?"failure":"cancelled";
            require(callbacks.get()==1&&(expected+":true:true").equals(terminal.get()),"unique-main-terminal-"+mode);
            require(sdk.get()==(mode.equals("success")?2:1)&&active.get()==0&&maximum.get()==1&&image.isRecycled(),"sdk-single-slot-parent-reclaimed");
        }finally{
            release.countDown();runOnMainSync(()->{ocr.close();firstTask.trySetException(new IllegalStateException("fixture-cleanup"));});
            require(worker.awaitTermination(10,TimeUnit.SECONDS),"snapshot-worker-ended");runOnMainSync(()->{});if(!image.isRecycled())image.recycle();
        }
    }
    public void onStart(){Bundle result=new Bundle();TextRecognizer real=null;Bitmap sample=null;
        try{
            require(!AssistantCaptureService.running()&&!CaptureService.running()&&!ChatGateService.enabled(),"isolated");
            real=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());sample=Bitmap.createBitmap(180,100,Bitmap.Config.ARGB_8888);sample.eraseColor(Color.WHITE);
            Text fixed=Tasks.await(real.process(InputImage.fromBitmap(sample,0)));require(fixed!=null&&fixed.getText().isEmpty(),"fixed-empty-text");
            for(String mode:new String[]{"success","sdkFailure","snapshotFailure","close","epoch","current"})for(boolean first:new boolean[]{false,true})scenario(mode,first,fixed);
            result.putString("result","PASS asyncSnapshotOverlapChecks="+checks+" scenarios=12 controlledSdk=true realFixture=true realChat=false");
        }catch(Throwable e){result.putString("result","FAIL asyncSnapshotOverlapChecks="+checks+" type="+e.getClass().getSimpleName()+" check="+e.getMessage());}
        finally{if(real!=null)real.close();if(sample!=null)sample.recycle();}
        finish(result.getString("result").startsWith("PASS")?-1:0,result);
    }
}