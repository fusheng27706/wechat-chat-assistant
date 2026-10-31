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

/** 隔离token缓存：受控mixed-run Latin与下一snapshot交错，不代表真实来源或文本准确性。 */
public final class ProductionTokenLifecycleSmoke extends Instrumentation {
    int checks;
    void require(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    public void onCreate(Bundle args){super.onCreate(args);start();}
    void synchronousFailure(Text fixed, boolean revoke, boolean mixed) throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1), done=new CountDownLatch(1);
        AtomicInteger copies=new AtomicInteger(), calls=new AtomicInteger(), callbacks=new AtomicInteger();
        AtomicBoolean current=new AtomicBoolean(true), snapshotEnded=new AtomicBoolean();
        AtomicReference<Bitmap> token=new AtomicReference<>();
        AtomicReference<String> terminal=new AtomicReference<>();
        Bitmap image=Bitmap.createBitmap(650,240,Bitmap.Config.ARGB_8888);image.eraseColor(Color.WHITE);
        TextRecognizer chinese=(TextRecognizer)Proxy.newProxyInstance(TextRecognizer.class.getClassLoader(),new Class<?>[]{TextRecognizer.class},(p,m,a)->m.getName().equals("process")?Tasks.forResult(fixed):null);
        TextRecognizer latin=(TextRecognizer)Proxy.newProxyInstance(TextRecognizer.class.getClassLoader(),new Class<?>[]{TextRecognizer.class},(p,m,a)->{
            if(m.getName().equals("process")) {
                calls.incrementAndGet();token.set(((InputImage)a[0]).getBitmapInternal());
                throw new IllegalStateException("fixed-synchronous-rejection");
            }return null;
        });
        BubbleOcr ocr=new BubbleOcr(chinese,new BubblePreparation(),worker,(screen,box,density)->{
            if(copies.incrementAndGet()!=2)return BubbleOcr.snapshot(screen,box,density);
            entered.countDown();
            try { if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("hold-timeout");
                return BubbleOcr.snapshot(screen,box,density);
            } catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            finally {snapshotEnded.set(true);}
        },latin);
        try {
            runOnMainSync(()->ocr.read(image,Arrays.asList(new ChatLayout.Box(0,0,650,100,false),new ChatLayout.Box(0,120,650,220,true)),2.75f,current::get,new BubbleOcr.Callback(){
                void end(String value){callbacks.incrementAndGet();terminal.set(value+":"+snapshotEnded.get()+":"+(Looper.myLooper()==Looper.getMainLooper()));image.recycle();done.countDown();}
                public void success(List<ChatMessage> messages,BubbleOcr.Stats stats){
                    boolean fallback=messages.size()==2&&messages.stream().allMatch(message->message.warning.contains(mixed?"英文片段复核失败":"英文复核未完成")&&OcrTextRules.compact(message.text).equals(OcrTextRules.compact(fixed.getText())));
                    end(fallback?"fallback":"wrong");
                }
                public void cancelled(){end("cancelled");}public void failure(){end("failure");}
            }));
            require(entered.await(5,TimeUnit.SECONDS),"sync-next-snapshot-entered");
            long deadline=android.os.SystemClock.elapsedRealtime()+5000;
            while((token.get()==null||!token.get().isRecycled())&&android.os.SystemClock.elapsedRealtime()<deadline){runOnMainSync(()->{});Thread.sleep(10);}
            require(calls.get()==1&&token.get()!=null&&token.get().isRecycled(),"sync-token-released");
            require(callbacks.get()==0&&!image.isRecycled(),"sync-parent-retained-for-snapshot");
            if(revoke)runOnMainSync(()->current.set(false));
            release.countDown();
            require(done.await(5,TimeUnit.SECONDS),"sync-terminal-without-watchdog");runOnMainSync(()->{});
            require(callbacks.get()==1&&((revoke?"cancelled":"fallback")+":true:true").equals(terminal.get()),"sync-terminal-and-fallback");
            require(calls.get()==(revoke?1:2)&&image.isRecycled()&&token.get().isRecycled(),"sync-no-retry-and-resources-returned");
        } finally {
            release.countDown();runOnMainSync(ocr::close);
            require(worker.awaitTermination(10,TimeUnit.SECONDS),"sync-worker-ended");runOnMainSync(()->{});
        }
    }
    void scenario(String mode,boolean snapshotFirst,Text fixed,Text latinFixed) throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(1),latinEntered=new CountDownLatch(1);
        AtomicBoolean current=new AtomicBoolean(true),nextReadEnded=new AtomicBoolean();
        AtomicInteger copies=new AtomicInteger(),sdk=new AtomicInteger(),latinCalls=new AtomicInteger(),active=new AtomicInteger(),maximum=new AtomicInteger(),callbacks=new AtomicInteger();
        AtomicReference<String> terminal=new AtomicReference<>();TaskCompletionSource<Text> firstTask=new TaskCompletionSource<>();
        AtomicReference<Bitmap> tokenImage=new AtomicReference<>();
        Bitmap image=Bitmap.createBitmap(650,240,Bitmap.Config.ARGB_8888);image.eraseColor(Color.WHITE);
        TextRecognizer recognizer=(TextRecognizer)Proxy.newProxyInstance(TextRecognizer.class.getClassLoader(),new Class<?>[]{TextRecognizer.class},(proxy,method,args)->{
            if(method.getName().equals("process")){
                int call=sdk.incrementAndGet();maximum.accumulateAndGet(active.incrementAndGet(),Math::max);
                Task<Text> task=Tasks.forResult(fixed);
                task.addOnCompleteListener(Runnable::run,ignored->active.decrementAndGet());return task;
            }return null;
        });
        TextRecognizer latin=(TextRecognizer)Proxy.newProxyInstance(TextRecognizer.class.getClassLoader(),new Class<?>[]{TextRecognizer.class},(proxy,method,args)->{
            if(method.getName().equals("process")){
                int call=latinCalls.incrementAndGet();maximum.accumulateAndGet(active.incrementAndGet(),Math::max);
                if(call==1)tokenImage.set(((InputImage)args[0]).getBitmapInternal());
                Task<Text> task=call==1?firstTask.getTask():Tasks.forResult(latinFixed);
                task.addOnCompleteListener(Runnable::run,ignored->active.decrementAndGet());latinEntered.countDown();return task;
            }return null;
        });
        BubbleOcr ocr=new BubbleOcr(recognizer,new BubblePreparation(),worker,(screen,box,density)->{
            if(copies.incrementAndGet()!=2)return BubbleOcr.snapshot(screen,box,density);
            entered.countDown();
            try{
                if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("hold-timeout");
                if(mode.equals("snapshotFailure"))throw new IllegalStateException("fixed-snapshot-failure");
                return BubbleOcr.snapshot(screen,box,density);
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            finally{nextReadEnded.set(true);}
        },latin);
        try{
            runOnMainSync(()->ocr.read(image,Arrays.asList(new ChatLayout.Box(0,0,650,100,false),new ChatLayout.Box(0,120,650,220,true)),2.75f,current::get,new BubbleOcr.Callback(){
                void end(String value){callbacks.incrementAndGet();terminal.set(value+":"+(Looper.myLooper()==Looper.getMainLooper())+":"+nextReadEnded.get());image.recycle();done.countDown();}
                public void success(List<ChatMessage> m,BubbleOcr.Stats s){end("success"+m.size());}
                public void cancelled(){end("cancelled");}public void failure(){end("failure");}
            }));
            require(entered.await(5,TimeUnit.SECONDS)&&latinEntered.await(5,TimeUnit.SECONDS),"next-snapshot-and-latin-entered");
            require(sdk.get()==1&&latinCalls.get()==1&&active.get()==1&&!image.isRecycled()&&callbacks.get()==0,"sdk-and-snapshot-overlap");
            require(tokenImage.get()!=null&&!tokenImage.get().isRecycled(),"token-owned-by-active-latin");
            if(snapshotFirst){release.countDown();worker.submit(()->{}).get(5,TimeUnit.SECONDS);runOnMainSync(()->{});
                require(nextReadEnded.get()&&!image.isRecycled()&&callbacks.get()==0,"snapshot-done-sdk-still-owned");}
            runOnMainSync(()->{
                if(mode.equals("close"))ocr.close();else if(mode.equals("epoch"))ocr.clearCache();else if(mode.equals("current"))current.set(false);
                require(callbacks.get()==0&&active.get()==1&&!image.isRecycled()
                        &&!tokenImage.get().isRecycled(),"revocation-keeps-actual-latin-owner");
                if(mode.equals("latinFailure"))firstTask.setException(new IllegalStateException("fixed-sdk-failure"));else firstTask.setResult(latinFixed);
            });runOnMainSync(()->{});
            if(!snapshotFirst){require(callbacks.get()==0&&!image.isRecycled(),"sdk-done-parent-still-owned");release.countDown();}
            require(done.await(5,TimeUnit.SECONDS),"terminal-after-both");runOnMainSync(()->{});
            String expected=(mode.equals("success")||mode.equals("latinFailure"))?"success2":mode.equals("snapshotFailure")?"failure":"cancelled";
            require(callbacks.get()==1&&(expected+":true:true").equals(terminal.get()),"unique-main-terminal-"+mode);
            require(sdk.get()==((mode.equals("success")||mode.equals("latinFailure"))?2:1)&&latinCalls.get()==(mode.equals("success")?1:sdk.get())&&active.get()==0&&maximum.get()==1&&image.isRecycled(),"sdk-single-slot-parent-reclaimed");
            require(tokenImage.get().isRecycled(),"terminal-token-reclaimed");
            if(mode.equals("close")||mode.equals("epoch")||mode.equals("current")) {
                var cacheField=BubbleOcr.class.getDeclaredField("cache");cacheField.setAccessible(true);
                ExactPixelCache<?> cache=(ExactPixelCache<?>)cacheField.get(ocr);
                runOnMainSync(()->require(cache.size()==0&&cache.storedInts()==0,"late-result-cannot-repopulate-cache"));
            }
        }finally{
            release.countDown();runOnMainSync(()->{ocr.close();firstTask.trySetException(new IllegalStateException("fixture-cleanup"));});
            require(worker.awaitTermination(10,TimeUnit.SECONDS),"snapshot-worker-ended");runOnMainSync(()->{});if(done.getCount()==0&&!image.isRecycled())image.recycle();
        }
    }
    public void onStart(){Bundle result=new Bundle();TextRecognizer real=null,latinReal=null;Bitmap sample=null,latinSample=null;
        try{
            require(!AssistantCaptureService.running()&&!CaptureService.running()&&!ChatGateService.enabled(),"isolated");
            real=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());sample=Bitmap.createBitmap(650,100,Bitmap.Config.ARGB_8888);sample.eraseColor(Color.WHITE);android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.BLACK);paint.setTextSize(32);new android.graphics.Canvas(sample).drawText("确认订单 AB123 时间",12,60,paint);
            Text fixed=Tasks.await(real.process(InputImage.fromBitmap(sample,0)));require(fixed!=null&&fixed.getText().contains("AB123")
                    &&fixed.getText().codePoints().anyMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN),"fixed-mixed-text");
            latinReal=TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS); latinSample=Bitmap.createBitmap(180,100,Bitmap.Config.ARGB_8888);latinSample.eraseColor(Color.WHITE);new android.graphics.Canvas(latinSample).drawText("AB123",12,60,paint); Text latinFixed=Tasks.await(latinReal.process(InputImage.fromBitmap(latinSample,0))); require(OcrTextRules.compact(latinFixed.getText()).equals("AB123"),"fixed-token-result");
            for(String mode:new String[]{"success","latinFailure","snapshotFailure","close","epoch","current"})for(boolean first:new boolean[]{false,true})scenario(mode,first,fixed,latinFixed);
            synchronousFailure(fixed,false,true);synchronousFailure(fixed,true,true);
            synchronousFailure(latinFixed,false,false);synchronousFailure(latinFixed,true,false);
            result.putString("result","PASS tokenLifecycleChecks="+checks+" scenarios=16 controlledSdk=true realFixture=true realChat=false");
        }catch(Throwable e){result.putString("result","FAIL tokenLifecycleChecks="+checks+" type="+e.getClass().getSimpleName()+" check="+e.getMessage());}
        finally{if(real!=null)real.close();if(latinReal!=null)latinReal.close();if(sample!=null)sample.recycle();if(latinSample!=null)latinSample.recycle();}
        finish(result.getString("result").startsWith("PASS")?-1:0,result);
    }
}
