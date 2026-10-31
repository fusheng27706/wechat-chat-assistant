package com.codextemp.wechatcaptureprobe;

import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 成功发布后的主线程同步诊断；只保存数字，不复制图像或保留借用参数。 */
final class BoundaryReasonJournal implements AutoCloseable, AssistantCaptureService.FrameObserver {
    private final List<String> records=new ArrayList<>();
    private boolean closed;
    BoundaryReasonJournal(){
        if(AssistantCaptureService.frameObserver!=null)throw new IllegalStateException("observer-already-set");
        AssistantCaptureService.frameObserver=this;
    }
    @Override public void observed(int sequence,ChatLayout layout,List<ChatLayout.Box> visible,
                                   int width,float density,ChatLayout.Pixels pixels){
        if(closed||records.size()>=8)return;
        long started=SystemClock.elapsedRealtime();
        try {
            int[] reasons=new int[8];
            int[] detail=new int[6];
            int margin=Math.min(width/4,Math.round(55*density));
            for(ChatLayout.Box box:visible)reasons[BoundaryRefinementReasons.inspect(box,Math.max(2,width/360),
                    margin,layout.contentTop,width-margin,layout.contentBottom,layout.dark,pixels,detail)]++;
            records.add("sequence="+sequence+" publishedFrame="+AssistantState.frames+" visible="+visible.size()
                    +" reasons="+Arrays.toString(reasons)+" detail="+Arrays.toString(detail)+" diagnosticMs="+(SystemClock.elapsedRealtime()-started));
        }catch(RuntimeException failure){records.add("observerError=1");close();}
    }
    List<String> snapshot(){return new ArrayList<>(records);}
    @Override public void close(){closed=true;if(AssistantCaptureService.frameObserver==this)AssistantCaptureService.frameObserver=null;}
}
