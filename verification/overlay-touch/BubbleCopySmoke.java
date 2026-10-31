package com.codextemp.wechatcaptureprobe;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import java.util.Arrays;

/** 固定不透明像素比较Bitmap读取和已有数组复制；不改变生产输入或访问聊天。 */
public final class BubbleCopySmoke extends Instrumentation {
    private int checks;
    private volatile int sink;
    private void require(boolean value){checks++;if(!value)throw new AssertionError("copy-equivalence");}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try {
            require(!AssistantCaptureService.running()&&!CaptureService.running()&&!ChatGateService.enabled());
            int width=1080,height=2201; int[] source=new int[width*height];
            for(int i=0;i<source.length;i++)source[i]=0xff000000|(i*73+(i>>>7)*19)&0xffffff;
            Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            try {
                bitmap.setPixels(source,0,width,0,0,width,height);
                for(int cropWidth:new int[]{180,360,720}) {
                    int cropHeight=180;
                    int[] expected=new int[cropWidth*cropHeight];
                    bitmap.getPixels(expected,0,cropWidth,100,200,cropWidth,cropHeight);
                    long[] before=new long[21],after=new long[21];
                    for(int round=-5;round<21;round++)for(int order=0;order<2;order++) {
                        boolean direct=((round+order)&1)==0;
                        long start=SystemClock.elapsedRealtimeNanos();
                        for(int bubble=0;bubble<6;bubble++) {
                            int[] pixels=new int[expected.length];
                            if(direct)for(int row=0;row<cropHeight;row++)System.arraycopy(source,(200+row)*width+100,pixels,row*cropWidth,cropWidth);
                            else bitmap.getPixels(pixels,0,cropWidth,100,200,cropWidth,cropHeight);
                            sink^=pixels[pixels.length/2];
                            // 比较在计时之外另做，避免完整扫描掩盖复制成本。
                        }
                        long elapsed=SystemClock.elapsedRealtimeNanos()-start;
                        if(round>=0)(direct?after:before)[round]=elapsed;
                    }
                    int[] actual=new int[expected.length];
                    for(int row=0;row<cropHeight;row++)System.arraycopy(source,(200+row)*width+100,actual,row*cropWidth,cropWidth);
                    require(Arrays.equals(expected,actual));
                    Arrays.sort(before);Arrays.sort(after);
                    Bundle line=new Bundle();line.putString("copySample","width="+cropWidth+" height="+cropHeight+" bubbles=6 bitmapMedianUs="+before[10]/1000+" arrayMedianUs="+after[10]/1000+" bitmapMaxUs="+before[20]/1000+" arrayMaxUs="+after[20]/1000);sendStatus(2,line);
                    Arrays.fill(expected,0);Arrays.fill(actual,0);
                }
            } finally {bitmap.recycle();Arrays.fill(source,0);}
            result.putString("result","PASS copyChecks="+checks+" cases=3 rounds=21 syntheticOnly=true productionChanged=false");
        }catch(Throwable failure){result.putString("result","FAIL type="+failure.getClass().getSimpleName());}
        finish(result.getString("result").startsWith("PASS")?-1:0,result);
    }
}
