package com.codextemp.wechatcaptureprobe;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 用固定图的真实Text对象驱动受控成功回调，覆盖撤权和两种预取交付顺序。 */
public final class PipelineRevocationSmoke extends Instrumentation {
    private int checks;
    private void require(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    public void onCreate(Bundle args){super.onCreate(args);start();}
    private void scenario(String revoke,boolean readyFirst,Text fixed) throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(1);
        AtomicInteger factories=new AtomicInteger(),sdk=new AtomicInteger(),callbacks=new AtomicInteger();
        AtomicBoolean current=new AtomicBoolean(true);
        AtomicReference<BubblePreparation.Result> first=new AtomicReference<>(),next=new AtomicReference<>();
        AtomicReference<String> terminal=new AtomicReference<>();
        TaskCompletionSource<Text> task=new TaskCompletionSource<>();
        BubblePreparation preparation=new BubblePreparation(worker,new Handler(Looper.getMainLooper()),(pixels,box,density)->{
            BubblePreparation.Result result=BubblePreparation.create(pixels,box,density);
            if(factories.incrementAndGet()==1) first.set(result);
            else {
                next.set(result);entered.countDown();
                try{if(!release.await(10,TimeUnit.SECONDS)){result.close();throw new IllegalStateException("hold-timeout");}}
                catch(InterruptedException e){result.close();Thread.currentThread().interrupt();throw new IllegalStateException(e);}
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
            runOnMainSync(()->ocr.read(image,boxes,2.75f,current::get,new PipelinedBubbleOcr.Callback(){
                private void end(String value){callbacks.incrementAndGet();terminal.set(value+":"+(Looper.myLooper()==Looper.getMainLooper()));done.countDown();}
                public void success(List<ChatMessage> messages,PipelinedBubbleOcr.Stats stats){end("success");}
                public void cancelled(){end("cancelled");}
                public void failure(){end("failure");}
            }));
            require(entered.await(5,TimeUnit.SECONDS),"next-entered");
            require(sdk.get()==1&&callbacks.get()==0,"one-sdk-active");
            if(readyFirst){
                release.countDown();
                // 串行worker屏障确保交付已post，随后主线程屏障确保交付已执行。
                worker.submit(()->{}).get(5,TimeUnit.SECONDS);runOnMainSync(()->{});
                require(!next.get().bitmap.isRecycled()&&callbacks.get()==0,"ready-retained-before-revocation");
            }
            runOnMainSync(()->{
                if(revoke.equals("close"))ocr.close();
                else if(revoke.equals("epoch"))ocr.clearCache();
                else current.set(false);
                task.setResult(fixed);
            });
            runOnMainSync(()->{});
            if(!readyFirst){
                require(callbacks.get()==0&&!next.get().bitmap.isRecycled(),"pending-blocks-terminal");
                release.countDown();
            }
            require(done.await(5,TimeUnit.SECONDS),"terminal");runOnMainSync(()->{});
            require(callbacks.get()==1&&"cancelled:true".equals(terminal.get()),"one-main-cancel");
            require(first.get().bitmap.isRecycled()&&next.get().bitmap.isRecycled()&&sdk.get()==1,"images-reclaimed-no-next-sdk");
        }finally{
            release.countDown();runOnMainSync(()->{ocr.close();task.trySetException(new IllegalStateException("fixture-cleanup"));});
            require(worker.awaitTermination(10,TimeUnit.SECONDS),"worker-ended");
            runOnMainSync(()->{});image.recycle();
        }
    }
    public void onStart(){Bundle result=new Bundle();TextRecognizer real=null;Bitmap fixedImage=null;
        try{
            require(!AssistantCaptureService.running()&&!CaptureService.running()&&!ChatGateService.enabled(),"isolated");
            real=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            fixedImage=Bitmap.createBitmap(180,100,Bitmap.Config.ARGB_8888);fixedImage.eraseColor(Color.WHITE);
            Text fixed=Tasks.await(real.process(InputImage.fromBitmap(fixedImage,0)));
            require(fixed!=null,"fixed-sdk-text");
            for(String revoke:new String[]{"close","epoch","current"})for(boolean ready:new boolean[]{false,true})scenario(revoke,ready,fixed);
            result.putString("result","PASS pipelineRevocationChecks="+checks+" scenarios=6 controlledSdkCompletion=true realSdkFixture=true realChat=false");
        }catch(Throwable error){result.putString("result","FAIL pipelineRevocationChecks="+checks+" type="+error.getClass().getSimpleName()+" check="+error.getMessage());}
        finally{if(real!=null)real.close();if(fixedImage!=null)fixedImage.recycle();}
        finish(result.getString("result").startsWith("PASS")?-1:0,result);
    }
}