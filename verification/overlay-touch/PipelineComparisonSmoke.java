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
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** 同固定画面/共享Chinese SDK串行交替冷读取；16ms计时器观察排队，不是屏幕帧时间。 */
public final class PipelineComparisonSmoke extends Instrumentation {
    private int checks;
    private static final class Reading {
        List<ChatMessage> messages;
        long millis, prepare, queueMax, chineseWall, latinWall, sdkQueue, validation;
        int queueSamples, fresh, hits, latin, budget, snapshots;
        long snapshotWallNs, snapshotCpuNs, snapshotMaxWallNs, copyWallNs, hashWallNs;
    }
    private static final class QueueMeter {
        final Handler handler=new Handler(Looper.getMainLooper());
        long due, maximum; int samples;
        final Runnable pulse=new Runnable(){ public void run(){
            long now=SystemClock.uptimeMillis();maximum=Math.max(maximum,Math.max(0,now-due));samples++;
            due=now+16;handler.postAtTime(this,due);
        }};
        void start(){due=SystemClock.uptimeMillis()+16;handler.postAtTime(pulse,due);}
        void stop(){
            // 完成回调可能先于已到期的pulse执行，保留这段尾部排队，避免取消时漏计。
            maximum=Math.max(maximum,Math.max(0,SystemClock.uptimeMillis()-due));
            handler.removeCallbacks(pulse);
        }
    }
    private void require(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private Reading read(boolean candidate,PipelinedBubbleOcr after,SnapshotBaselineBubbleOcr before,Bitmap image,List<ChatLayout.Box> boxes) throws Throwable {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
        Reading reading=new Reading();QueueMeter meter=new QueueMeter();long began=SystemClock.elapsedRealtime();
        Runnable finish=()->{meter.stop();reading.millis=SystemClock.elapsedRealtime()-began;
            reading.queueMax=meter.maximum;reading.queueSamples=meter.samples;done.countDown();};
        runOnMainSync(()->{
            try {
                if(candidate)after.clearCache();else before.clearCache();
                meter.start();
                if(candidate)after.read(image,boxes,2.75f,()->true,new PipelinedBubbleOcr.Callback(){
                    public void success(List<ChatMessage> messages,PipelinedBubbleOcr.Stats stats){
                        reading.snapshots=stats.snapshotCalls;reading.snapshotWallNs=stats.snapshotWallNs;
                        reading.snapshotCpuNs=stats.snapshotCpuNs;reading.snapshotMaxWallNs=stats.snapshotMaxWallNs;
                        reading.copyWallNs=stats.copyWallNs;reading.hashWallNs=stats.hashWallNs;
                        reading.messages=messages;reading.prepare=stats.prepareMs;reading.fresh=stats.freshBubbles;
                        reading.hits=stats.cacheHits;reading.latin=stats.latinCalls;reading.budget=stats.latinBudgetUsed;
                        reading.chineseWall=stats.chineseWallMs;reading.latinWall=stats.latinWallMs;
                        reading.sdkQueue=stats.chineseQueueMs+stats.latinQueueMs;reading.validation=stats.validationMs;finish.run();}
                    public void cancelled(){failure.set(new AssertionError("candidate-cancelled"));finish.run();}
                    public void failure(){failure.set(new AssertionError("candidate-sdk-failure"));finish.run();}
                });
                else before.read(image,boxes,2.75f,()->true,new SnapshotBaselineBubbleOcr.Callback(){
                    public void success(List<ChatMessage> messages,SnapshotBaselineBubbleOcr.Stats stats){
                        reading.messages=messages;reading.prepare=stats.prepareMs;reading.fresh=stats.freshBubbles;
                        reading.hits=stats.cacheHits;reading.latin=stats.latinCalls;reading.budget=stats.latinBudgetUsed;
                        reading.chineseWall=stats.chineseWallMs;reading.latinWall=stats.latinWallMs;
                        reading.sdkQueue=stats.chineseQueueMs+stats.latinQueueMs;reading.validation=stats.validationMs;finish.run();}
                    public void cancelled(){failure.set(new AssertionError("baseline-cancelled"));finish.run();}
                    public void failure(){failure.set(new AssertionError("baseline-sdk-failure"));finish.run();}
                });
            } catch(Throwable problem){failure.set(problem);finish.run();}
        });
        boolean interrupted=false;
        for(;;)try{done.await();break;}catch(InterruptedException ignored){interrupted=true;}
        if(interrupted){Thread.currentThread().interrupt();throw new InterruptedException();}
        if(failure.get()!=null)throw failure.get();
        return reading;
    }
    private boolean same(List<ChatMessage> first,List<ChatMessage> second){
        if(first.size()!=second.size())return false;
        for(int i=0;i<first.size();i++){
            ChatMessage a=first.get(i),b=second.get(i);
            if(!a.text.equals(b.text)||!a.warning.equals(b.warning)||a.outgoing!=b.outgoing||a.top!=b.top
                    ||a.visualFingerprint!=b.visualFingerprint)return false;
        }
        return true;
    }
    @Override public void onStart(){
        Bundle result=new Bundle();TextRecognizer chinese=null;PipelinedBubbleOcr after=null;SnapshotBaselineBubbleOcr before=null;
        int differences=0,baselineVariations=0,pairs=0;
        try {
            require(!AssistantCaptureService.running()&&!CaptureService.running()&&!ChatGateService.enabled(),"isolated");
            chinese=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            after=new PipelinedBubbleOcr(chinese);before=new SnapshotBaselineBubbleOcr(chinese);
            for(int scenario=0;scenario<2;scenario++){
                Bitmap image=Bitmap.createBitmap(680,1020,Bitmap.Config.ARGB_8888);
                List<ChatLayout.Box> boxes=new ArrayList<>();Canvas canvas=new Canvas(image);canvas.drawColor(0xffededed);
                Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setTextSize(40);
                for(int i=0;i<6;i++){
                    int top=20+i*165;boolean outgoing=(i&1)==1;
                    paint.setColor(outgoing?0xff95ec69:Color.WHITE);canvas.drawRect(20,top,660,top+140,paint);
                    paint.setColor(Color.BLACK);canvas.drawText(i<4?"明天确认事项 "+i:"CHECK "+(100+i),40,top+75,paint);
                    if(scenario==1){paint.setColor(0xffff2020);canvas.drawRect(550,top+45,590,top+85,paint);}
                    boxes.add(new ChatLayout.Box(20,top,660,top+140,outgoing));
                }
                List<ChatMessage> previous=null;
                try {
                    for(int round=-1;round<4;round++){
                        Reading[] reads=new Reading[2];
                        for(int order=0;order<2;order++){
                            int variant=(round+order+2)&1;
                            reads[variant]=read(variant==1,after,before,image,boxes);
                            require(reads[variant].fresh==6&&reads[variant].hits==0,"all-six-cold");
                        }
                        boolean equal=same(reads[0].messages,reads[1].messages);
                        if(!equal)differences++;
                        if(previous!=null&&!same(previous,reads[0].messages))baselineVariations++;
                        previous=reads[0].messages;
                        require(reads[0].latin==reads[1].latin&&reads[0].budget==reads[1].budget,"review-budget-equal");
                        if(round>=0){
                            Bundle line=new Bundle();line.putString("pipelineComparison","scenario="+scenario+" round="+round
                                    +" baselineFirst="+((round&1)==0)+" resultEqual="+equal+" oldMs="+reads[0].millis+" newMs="+reads[1].millis
                                    +" oldPrepareWallMs="+reads[0].prepare+" newPrepareWallMs="+reads[1].prepare
                                    +" oldChineseMs="+reads[0].chineseWall+" newChineseMs="+reads[1].chineseWall
                                    +" oldLatinMs="+reads[0].latinWall+" newLatinMs="+reads[1].latinWall
                                    +" oldSdkQueueMs="+reads[0].sdkQueue+" newSdkQueueMs="+reads[1].sdkQueue
                                    +" oldValidationMs="+reads[0].validation+" newValidationMs="+reads[1].validation
                                    +" candidateSnapshots="+reads[1].snapshots+" candidateSnapshotWallNs="+reads[1].snapshotWallNs
                                    +" candidateSnapshotCpuNs="+reads[1].snapshotCpuNs+" candidateSnapshotMaxWallNs="+reads[1].snapshotMaxWallNs
                                    +" candidateCopyWallNs="+reads[1].copyWallNs+" candidateHashWallNs="+reads[1].hashWallNs
                                    +" oldQueueMaxMs="+reads[0].queueMax+" newQueueMaxMs="+reads[1].queueMax
                                    +" oldQueueSamples="+reads[0].queueSamples+" newQueueSamples="+reads[1].queueSamples);
                            sendStatus(2,line);pairs++;
                        }
                    }
                } finally{image.recycle();}
            }
            result.putString("result","COMPLETED pipelineComparisonChecks="+checks+" measuredPairs="+pairs
                    +" resultDifferences="+differences+" comparedPairsIncludingWarmup="+(pairs+2)
                    +" baselineVariations="+baselineVariations+" warmupPairs=2 syntheticOnly=true realSdk=true realChat=false");
        }catch(Throwable failure){result.putString("result","FAIL pipelineComparisonChecks="+checks+" type="+failure.getClass().getSimpleName()+" check="+failure.getMessage());}
        finally{
            PipelinedBubbleOcr candidate=after;SnapshotBaselineBubbleOcr baseline=before;
            runOnMainSync(()->{if(candidate!=null)candidate.close();if(baseline!=null)baseline.close();});
            if(chinese!=null)chinese.close();
        }
        finish(result.getString("result").startsWith("COMPLETED")?-1:0,result);
    }
}
