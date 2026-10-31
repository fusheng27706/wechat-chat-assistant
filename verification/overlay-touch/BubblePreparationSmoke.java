package com.codextemp.wechatcaptureprobe;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.text.TextRecognizer;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 固定像素和受控准备任务；不读取屏幕，不调用真实OCR，不发送文字。 */
public final class BubblePreparationSmoke extends Instrumentation {
    private int checks;
    private void require(boolean value, String name) {
        checks++;
        if (!value) throw new AssertionError(name);
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            require(!AssistantCaptureService.running() && !CaptureService.running() && !ChatGateService.enabled(), "isolated");
            for (int width : new int[]{180,321}) for (int color : new int[]{Color.WHITE,0xff95ec69,0xff202020})
                for (boolean graphic : new boolean[]{false,true}) compare(width,111,color,graphic);
            for (String mode : new String[]{"close","invalidate","clear","failure","dispatch","rejected"}) lifecycle(mode);
            rejectedDelivery();
            output.putString("result", "PASS preparationChecks=" + checks + " pixelCases=12 lifecycleCases=7 realOcr=false realImages=0");
        } catch (Throwable failure) {
            output.putString("result", "FAIL preparationChecks=" + checks + " type=" + failure.getClass().getSimpleName()
                    + " check=" + failure.getMessage());
        }
        finish(output.getString("result").startsWith("PASS") ? -1 : 0, output);
    }

    private void compare(int width, int height, int color, boolean graphic) {
        int[] pixels = new int[width*height]; Arrays.fill(pixels,color);
        for (int y=12;y<height-12;y++) for(int x=12;x<width-12;x++) {
            if(graphic && x>=width/2 && x<width/2+40 && y>=height/2 && y<height/2+40) pixels[y*width+x]=0xffff2020;
            else if(y%20<3 && x%16<7) pixels[y*width+x]=Color.BLACK;
        }
        ChatLayout.Box box = new ChatLayout.Box(7,9,7+width,9+height,false);
        try (BubblePreparation.Result before = reference(pixels,box,2.75f);
             BubblePreparation.Result after = BubblePreparation.create(pixels,box,2.75f);
             BubblePreparation.Result local = BubblePreparation.create(pixels,
                     new ChatLayout.Box(0,0,width,height,false),2.75f)) {
            require(before.bitmap.sameAs(after.bitmap), "complete-argb-equal");
            require(before.visual == after.visual && before.graphics.equals(after.graphics), "graphics-and-visual-equal");
            require(graphic == !after.graphics.isEmpty(), "fixture-covers-graphic-branch");
            require(local.bitmap.sameAs(after.bitmap) && local.visual == after.visual
                    && local.graphics.equals(after.graphics), "local-origin-preparation-equal");
            int count = (width + 24) * (height + 24);
            require(PreparedPixelComparison.compare(pixels,pixels,width,height,false,2.75f)
                    .equals("0/" + count + "/true/true"), "prepared-diagnostic-identical");
            int[] changed = pixels.clone();
            try {
                changed[0] = Color.BLACK;
                require(PreparedPixelComparison.compare(pixels,changed,width,height,false,2.75f)
                        .equals("1/" + count + "/true/true"), "prepared-diagnostic-keeps-edge-difference");
            } finally { Arrays.fill(changed,0); }
        } finally { Arrays.fill(pixels,0); }
    }

    // 冻结自改动前的准备顺序，用于验证后台化没有改变SDK像素输入。
    private BubblePreparation.Result reference(int[] pixels, ChatLayout.Box box, float density) {
        int width=box.right-box.left, height=box.bottom-box.top;
        List<GraphicDetector.Region> graphics=GraphicDetector.detect((x,y)->pixels[(y-box.top)*width+x-box.left],box,density);
        Bitmap prepared=Bitmap.createBitmap(width+24,height+24,Bitmap.Config.ARGB_8888);
        prepared.eraseColor(Color.WHITE); prepared.setPixels(pixels,0,width,12,12,width,height);
        Canvas canvas=new Canvas(prepared); android.graphics.Paint paint=new android.graphics.Paint();paint.setColor(Color.WHITE);
        List<Rect> bounds=new ArrayList<>();long visual=0;
        for(GraphicDetector.Region region:graphics) {
            Rect rectangle=new Rect(region.left-box.left+12,region.top-box.top+12,region.right-box.left+12,region.bottom-box.top+12);
            bounds.add(rectangle);canvas.drawRect(rectangle.left-2,rectangle.top-2,rectangle.right+2,rectangle.bottom+2,paint);
            visual=visual==0?1469598103934665603L:visual;
            for(int y=region.top;y<region.bottom;y+=2)for(int x=region.left;x<region.right;x+=2)
                visual=(visual^(pixels[(y-box.top)*width+x-box.left]&0x00f8f8f8))*1099511628211L;
        }
        return new BubblePreparation.Result(prepared,bounds,visual);
    }

    private void lifecycle(String mode) throws Exception {
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1), done=new CountDownLatch(1);
        AtomicInteger sdk=new AtomicInteger(), calls=new AtomicInteger();
        AtomicBoolean valid=new AtomicBoolean(true), workerOffMain=new AtomicBoolean();
        AtomicReference<BubblePreparation.Result> created=new AtomicReference<>();
        AtomicReference<String> terminal=new AtomicReference<>();
        ExecutorService worker=Executors.newSingleThreadExecutor();
        BubblePreparation preparation=new BubblePreparation(worker,new Handler(Looper.getMainLooper()),(pixels,box,density)->{
            workerOffMain.set(Looper.myLooper()!=Looper.getMainLooper()); entered.countDown();
            try { if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("release-timeout"); }
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("interrupted");}
            if(mode.equals("failure"))throw new IllegalStateException("fixed-failure");
            BubblePreparation.Result result=BubblePreparation.create(pixels,box,density);created.set(result);return result;
        });
        TextRecognizer recognizer=(TextRecognizer)Proxy.newProxyInstance(TextRecognizer.class.getClassLoader(),new Class<?>[]{TextRecognizer.class},(proxy,method,args)->{
            if(method.getName().equals("process")){sdk.incrementAndGet();return Tasks.forException(new IllegalStateException("fixed-sdk-failure"));}
            return null;
        });
        BubbleOcr ocr=new BubbleOcr(recognizer,preparation);
        Bitmap screen=Bitmap.createBitmap(180,111,Bitmap.Config.ARGB_8888);screen.eraseColor(Color.WHITE);
        try {
            if(mode.equals("rejected"))preparation.close();
            runOnMainSync(()->ocr.read(screen,Collections.singletonList(new ChatLayout.Box(0,0,180,111,false)),2.75f,
                    ()->valid.get(),new BubbleOcr.Callback(){
                        private void finish(String value){calls.incrementAndGet();terminal.set(value+":"+(Looper.myLooper()==Looper.getMainLooper()));done.countDown();}
                        public void success(List<ChatMessage> messages,BubbleOcr.Stats stats){finish("success");}
                        public void cancelled(){finish("cancelled");}
                        public void failure(){finish("failure");}
                    }));
            if(!mode.equals("rejected")) {
                require(entered.await(5,TimeUnit.SECONDS),"worker-entered");
                require(workerOffMain.get() && calls.get()==0 && sdk.get()==0,"pending-not-delivered-or-dispatched");
                runOnMainSync(()->{
                    if(mode.equals("close"))ocr.close();
                    if(mode.equals("invalidate"))valid.set(false);
                    if(mode.equals("clear"))ocr.clearCache();
                });
                require(calls.get()==0,"revocation-does-not-finish-before-worker");
                release.countDown();
            }
            require(done.await(5,TimeUnit.SECONDS),"terminal-delivered");
            runOnMainSync(()->{});
            String expected=mode.equals("close")||mode.equals("invalidate")||mode.equals("clear")?"cancelled:true":"failure:true";
            require(calls.get()==1 && expected.equals(terminal.get()),"exactly-once-main-terminal-"+mode);
            require(sdk.get()==(mode.equals("dispatch")?1:0),"no-stale-sdk-start-"+mode);
            require(created.get()==null||created.get().bitmap.isRecycled(),"prepared-recycled-"+mode);
        } finally {
            release.countDown(); runOnMainSync(ocr::close);
            require(worker.awaitTermination(10,TimeUnit.SECONDS),"worker-terminated");
            runOnMainSync(()->{});screen.recycle();
        }
    }

    private void rejectedDelivery() throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        AtomicReference<BubblePreparation.Result> created=new AtomicReference<>();
        AtomicInteger delivered=new AtomicInteger();
        Handler rejecting=new Handler(Looper.getMainLooper()) {
            @Override public boolean sendMessageAtTime(android.os.Message message,long uptime) { return false; }
        };
        BubblePreparation preparation=new BubblePreparation(worker,rejecting,(pixels,box,density)->{
            BubblePreparation.Result result=BubblePreparation.create(pixels,box,density);created.set(result);return result;
        });
        int[] pixels=new int[180*111];Arrays.fill(pixels,Color.WHITE);
        try {
            runOnMainSync(()->preparation.prepare(pixels,new ChatLayout.Box(0,0,180,111,false),2.75f,
                    result->delivered.incrementAndGet()));
        } finally {
            preparation.close();
            require(worker.awaitTermination(10,TimeUnit.SECONDS),"rejected-delivery-worker-finished");
            Arrays.fill(pixels,0);
        }
        require(delivered.get()==0 && created.get()!=null && created.get().bitmap.isRecycled(),"undeliverable-result-recycled");
    }
}
