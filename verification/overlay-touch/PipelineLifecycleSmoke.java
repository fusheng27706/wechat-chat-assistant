package com.codextemp.wechatcaptureprobe;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognizer;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 固定双气泡：SDK失败早于预取交付，验证终态必须等待私有图归还。 */
public final class PipelineLifecycleSmoke extends Instrumentation {
    private int checks;
    private void require(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    public void onCreate(Bundle args){super.onCreate(args);start();}
    private void scenario(boolean close,boolean failPreparation) throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(1);
        AtomicInteger factories=new AtomicInteger(),sdk=new AtomicInteger(),callbacks=new AtomicInteger();
        AtomicReference<BubblePreparation.Result> nextImage=new AtomicReference<>();
        AtomicReference<String> terminal=new AtomicReference<>();
        TaskCompletionSource<Text> task=new TaskCompletionSource<>();
        BubblePreparation preparation=new BubblePreparation(worker,new Handler(Looper.getMainLooper()),(pixels,box,density)->{
            int order=factories.incrementAndGet();
            BubblePreparation.Result result=BubblePreparation.create(pixels,box,density);
            if(order==2){
                nextImage.set(result);entered.countDown();
                try{if(!release.await(10,TimeUnit.SECONDS)){result.close();throw new IllegalStateException("hold-timeout");}}
                catch(InterruptedException e){result.close();Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                if(failPreparation){result.close();throw new IllegalStateException("fixed-preparation-failure");}
            }
            return result;
        });
        TextRecognizer recognizer=(TextRecognizer)Proxy.newProxyInstance(TextRecognizer.class.getClassLoader(),new Class<?>[]{TextRecognizer.class},(proxy,method,args)->{
            if(method.getName().equals("process")){sdk.incrementAndGet();return task.getTask();}return null;
        });
        PipelinedBubbleOcr ocr=new PipelinedBubbleOcr(recognizer,preparation);
        Bitmap image=Bitmap.createBitmap(180,240,Bitmap.Config.ARGB_8888);image.eraseColor(Color.WHITE);
        List<ChatLayout.Box> boxes=Arrays.asList(new ChatLayout.Box(0,0,180,100,false),new ChatLayout.Box(0,120,180,220,true));
        try{
            runOnMainSync(()->ocr.read(image,boxes,2.75f,()->true,new PipelinedBubbleOcr.Callback(){
                private void end(String value){callbacks.incrementAndGet();terminal.set(value+":"+(Looper.myLooper()==Looper.getMainLooper()));done.countDown();}
                public void success(List<ChatMessage> messages,PipelinedBubbleOcr.Stats stats){end("success");}
                public void cancelled(){end("cancelled");}
                public void failure(){end("failure");}
            }));
            require(entered.await(5,TimeUnit.SECONDS),"next-preparation-entered");
            require(sdk.get()==1&&callbacks.get()==0,"one-sdk-before-failure");
            runOnMainSync(()->{if(close)ocr.close();task.setException(new IllegalStateException("fixed-sdk-failure"));});
            runOnMainSync(()->{});
            require(callbacks.get()==0&&!nextImage.get().bitmap.isRecycled(),"no-early-terminal-or-recycle");
            release.countDown();
            require(done.await(5,TimeUnit.SECONDS),"terminal-after-preparation");
            runOnMainSync(()->{});
            require(callbacks.get()==1&&"failure:true".equals(terminal.get()),"one-main-failure");
            require(nextImage.get().bitmap.isRecycled()&&sdk.get()==1,"late-image-reclaimed-no-next-sdk");
        }finally{
            release.countDown();runOnMainSync(()->{
                ocr.close();
                // 断言可能在显式SDK失败前退出，仍须结束假任务以归还当前识别图。
                task.trySetException(new IllegalStateException("fixture-cleanup"));
            });
            require(worker.awaitTermination(10,TimeUnit.SECONDS),"worker-ended");
            runOnMainSync(()->{});image.recycle();
        }
    }
    public void onStart(){Bundle result=new Bundle();try{
        require(!AssistantCaptureService.running()&&!CaptureService.running()&&!ChatGateService.enabled(),"isolated");
        scenario(false,false);scenario(true,false);scenario(false,true);scenario(true,true);
        result.putString("result","PASS pipelineLifecycleChecks="+checks+" scenarios=4 realSdk=false realChat=false");
    }catch(Throwable error){result.putString("result","FAIL pipelineLifecycleChecks="+checks+" type="+error.getClass().getSimpleName()+" check="+error.getMessage());}
    finish(result.getString("result").startsWith("PASS")?-1:0,result);}
}
