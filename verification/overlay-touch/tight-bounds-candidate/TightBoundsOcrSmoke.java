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

/** 固定字体画面比较粗框与紧轮廓完整OCR及重复波动；不采集微信。 */
public final class TightBoundsOcrSmoke extends Instrumentation {
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
    private List<ChatLayout.Box> boxes(Bitmap image, boolean dark, boolean tight) throws Exception {
        Class<?> type=tight?TightChatLayout.class:ChatLayout.class;
        Class<?> pixelType=tight?TightChatLayout.Pixels.class:ChatLayout.Pixels.class;
        Object pixels=tight?(TightChatLayout.Pixels)image::getPixel:(ChatLayout.Pixels)image::getPixel;
        java.lang.reflect.Method method=type.getDeclaredMethod("findBubbles",int.class,int.class,int.class,float.class,pixelType,boolean.class);
        method.setAccessible(true);
        List<?> found=(List<?>)method.invoke(null,image.getWidth(),80,650,2.75f,pixels,dark);
        List<ChatLayout.Box> result=new ArrayList<>();
        for(Object box:found) {
            int[] v=new int[4];String[] names={"left","top","right","bottom"};
            for(int i=0;i<4;i++){var f=box.getClass().getDeclaredField(names[i]);f.setAccessible(true);v[i]=f.getInt(box);}
            var f=box.getClass().getDeclaredField("outgoing");f.setAccessible(true);
            result.add(new ChatLayout.Box(v[0],v[1],v[2],v[3],f.getBoolean(box)));
        }
        return result;
    }
    @Override public void onStart() {
        Bundle result=new Bundle();TextRecognizer chinese=null;BubbleOcr ocr=null;
        int cases=0,differences=0,textDiff=0,repeatOld=0,repeatNew=0,wrongOld=0,wrongNew=0,changedBounds=0;
        String[] samples={"测试订单AB123共8000元","明天下午三点确认时间"};
        try {
            require(!AssistantCaptureService.running()&&!CaptureService.running()&&!ChatGateService.enabled(),"isolated");
            chinese=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            ocr=new BubbleOcr(chinese);BubbleOcr current=ocr;
            for(int theme=0;theme<3;theme++)for(int font:new int[]{32,44})for(int graphic=0;graphic<2;graphic++)for(int phase=0;phase<3;phase++) {
                boolean dark=theme==2,green=theme==1;
                String text=samples[(theme+graphic)%2];
                Bitmap image=Bitmap.createBitmap(1080,700,Bitmap.Config.ARGB_8888);
                try {
                    Canvas canvas=new Canvas(image);canvas.drawColor(dark?0xff171717:0xffededed);
                    Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(green?0xff95ec69:dark?0xff404040:Color.WHITE);
                    int x=210+phase,y=200+phase;
                    canvas.drawRoundRect(x,y,x+650,y+190,8,8,paint);
                    paint.setColor(dark?0xffdddddd:Color.BLACK);paint.setTextSize(font);
                    require(paint.measureText(text)<600,"fixed-text-fits");
                    canvas.drawText(text,x+22,y+80,paint);
                    if(graphic==1){paint.setColor(0xffff2020);canvas.drawRect(x+260,y+115,x+305,y+160,paint);}
                    List<ChatLayout.Box> old=boxes(image,dark,false),next=boxes(image,dark,true);
                    require(old.size()==1&&next.size()==1,"one-complete-bubble");
                    ChatLayout.Box a=old.get(0),b=next.get(0);
                    boolean changed=a.left!=b.left||a.top!=b.top||a.right!=b.right||a.bottom!=b.bottom;
                    if(changed)changedBounds++;
                    Reading[][] reads=new Reading[2][2];
                    for(int repeat=0;repeat<2;repeat++)for(int order=0;order<2;order++) {
                        int variant=(cases+repeat+order)%2;
                        runOnMainSync(current::clearCache);
                        reads[variant][repeat]=read(ocr,image,variant==0?old:next);
                        require(reads[variant][repeat].messages.size()==1,"one-ocr-result");
                    }
                    boolean differs=!same(reads[0][0].messages.get(0),reads[1][0].messages.get(0));
                    boolean td=!reads[0][0].messages.get(0).text.equals(reads[1][0].messages.get(0).text);
                    boolean ro=!same(reads[0][0].messages.get(0),reads[0][1].messages.get(0));
                    boolean rn=!same(reads[1][0].messages.get(0),reads[1][1].messages.get(0));
                    boolean wo=!reads[0][0].messages.get(0).text.replaceAll("\\s+", "").equals(text);
                    boolean wn=!reads[1][0].messages.get(0).text.replaceAll("\\s+", "").equals(text);
                    if(differs)differences++;if(td)textDiff++;if(ro)repeatOld++;if(rn)repeatNew++;if(wo)wrongOld++;if(wn)wrongNew++;
                    Bundle line=new Bundle();line.putString("tightOcr","case="+cases+" theme="+theme+" font="+font+" graphic="+graphic+" phase="+phase
                            +" changedBounds="+changed+" outputDiff="+differs+" textDiff="+td+" repeatOld="+ro+" repeatNew="+rn
                            +" wrongOld="+wo+" wrongNew="+wn+" oldGraphics="+reads[0][0].stats.graphics+" newGraphics="+reads[1][0].stats.graphics
                            +" oldMs="+reads[0][0].millis+" newMs="+reads[1][0].millis);
                    sendStatus(2,line);cases++;
                } finally {image.recycle();}
            }
            result.putString("result","COMPLETED tightOcrChecks="+checks+" cases="+cases+" changedBounds="+changedBounds+" outputDifferences="+differences
                    +" textDifferences="+textDiff+" repeatOld="+repeatOld+" repeatNew="+repeatNew+" strictWrongOld="+wrongOld+" strictWrongNew="+wrongNew
                    +" realImages=0 productionEligible=false");
        } catch(Throwable failure) {result.putString("result","FAIL tightOcrChecks="+checks+" cases="+cases+" type="+failure.getClass().getSimpleName());}
        finally {if(ocr!=null){BubbleOcr current=ocr;runOnMainSync(current::close);}if(chinese!=null)chinese.close();}
        finish(result.getString("result").startsWith("COMPLETED")?-1:0,result);
    }
}