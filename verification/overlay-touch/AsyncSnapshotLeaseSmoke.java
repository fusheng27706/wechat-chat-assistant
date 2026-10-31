package com.codextemp.wechatcaptureprobe;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Looper;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.text.TextRecognizer;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 父图在snapshot worker hold期间必须存活；撤权只在实际读取归还后完成。 */
public final class AsyncSnapshotLeaseSmoke extends Instrumentation {
    int checks;
    void require(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    public void onCreate(Bundle args){super.onCreate(args);start();}
    void scenario(String mode) throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(1);
        AtomicBoolean current=new AtomicBoolean(true),readEnded=new AtomicBoolean(),offMain=new AtomicBoolean();
        AtomicInteger sdk=new AtomicInteger(),callbacks=new AtomicInteger();AtomicReference<String> terminal=new AtomicReference<>();
        Bitmap image=Bitmap.createBitmap(180,100,Bitmap.Config.ARGB_8888);image.eraseColor(Color.WHITE);
        TextRecognizer recognizer=(TextRecognizer)Proxy.newProxyInstance(TextRecognizer.class.getClassLoader(),new Class<?>[]{TextRecognizer.class},(proxy,method,args)->{
            if(method.getName().equals("process")){sdk.incrementAndGet();return Tasks.forException(new IllegalStateException("fixed-sdk-failure"));}return null;
        });
        AsyncSnapshotBubbleOcr ocr=new AsyncSnapshotBubbleOcr(recognizer,new BubblePreparation(),worker,(screen,box,density)->{
            offMain.set(Looper.myLooper()!=Looper.getMainLooper());entered.countDown();
            try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("hold-timeout");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            try{if(mode.equals("factory"))throw new IllegalStateException("fixed-snapshot-failure");
                return AsyncSnapshotBubbleOcr.snapshot(screen,box,density);
            }finally{readEnded.set(true);}
        });
        try{
            runOnMainSync(()->ocr.read(image,Collections.singletonList(new ChatLayout.Box(0,0,180,100,false)),2.75f,()->{if(mode.equals("throwing")&&!current.get())throw new IllegalStateException("fixed-source-failure");return current.get();},new AsyncSnapshotBubbleOcr.Callback(){
                void end(String value){callbacks.incrementAndGet();terminal.set(value+":"+(Looper.myLooper()==Looper.getMainLooper())+":"+readEnded.get());image.recycle();done.countDown();}
                public void success(List<ChatMessage> m,AsyncSnapshotBubbleOcr.Stats s){end("success");}
                public void cancelled(){end("cancelled");}public void failure(){end("failure");}
            }));
            require(entered.await(5,TimeUnit.SECONDS)&&offMain.get(),"snapshot-off-main");
            runOnMainSync(()->{if(mode.equals("close"))ocr.close();else if(mode.equals("epoch"))ocr.clearCache();else if((mode.equals("current")||mode.equals("throwing")))current.set(false);});
            require(callbacks.get()==0&&sdk.get()==0&&!image.isRecycled(),"parent-owned-while-worker-held");
            release.countDown();require(done.await(5,TimeUnit.SECONDS),"settled");runOnMainSync(()->{});
            boolean revoke=mode.equals("close")||mode.equals("epoch")||mode.equals("current")||mode.equals("throwing");
            require(callbacks.get()==1&&((revoke?"cancelled":"failure")+":true:true").equals(terminal.get()),"terminal-after-read");
            require(sdk.get()==(mode.equals("sdk")?1:0)&&image.isRecycled(),"no-stale-sdk-parent-reclaimed");
        }finally{
            release.countDown();runOnMainSync(ocr::close);require(worker.awaitTermination(10,TimeUnit.SECONDS),"worker-ended");runOnMainSync(()->{});if(!image.isRecycled())image.recycle();
        }
    }
    public void onStart(){Bundle result=new Bundle();try{
        require(!AssistantCaptureService.running()&&!CaptureService.running()&&!ChatGateService.enabled(),"isolated");
        for(String mode:new String[]{"close","epoch","current","factory","sdk","throwing"})scenario(mode);
        result.putString("result","PASS asyncSnapshotLeaseChecks="+checks+" scenarios=6 realSdk=false realChat=false");
    }catch(Throwable e){result.putString("result","FAIL asyncSnapshotLeaseChecks="+checks+" type="+e.getClass().getSimpleName()+" check="+e.getMessage());}
    finish(result.getString("result").startsWith("PASS")?-1:0,result);}
}