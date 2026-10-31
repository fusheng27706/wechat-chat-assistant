package com.codextemp.wechatcaptureprobe;

import java.util.Arrays;

/** 单条精确标题计算缓存；仅保存双帧确认后的结果，不保存或授予来源许可。 */
final class HeaderRecognitionCache {
    private static final int MAX_PIXELS = 262144;
    private long ticket, generation, rulesRevision;
    private int left, top, width, height;
    private int[] pixels;
    private String title;

    // 数值诊断：0命中、1无条目、2会话、3范围、4规则、5裁剪、6像素。
    private int missReason=1;
    int missReason(){return missReason;}
    private String differenceStats="none";
    String differenceStats(){return differenceStats;}
    String get(long ticket, long generation, long rulesRevision, int left, int top, int width, int height, int[] current) {
        differenceStats="none";
        missReason=pixels==null?1:this.ticket!=ticket?2:this.generation!=generation?3
                :this.rulesRevision!=rulesRevision?4:this.left!=left||this.top!=top||this.width!=width||this.height!=height?5
                :!Arrays.equals(pixels,current)?6:0;
        if(missReason==6&&current!=null&&current.length==pixels.length) {
            int count=0,minX=width,minY=height,maxX=-1,maxY=-1,maxDelta=0;
            for(int i=0;i<pixels.length;i++)if(pixels[i]!=current[i]) {
                count++;int x=i%width,y=i/width;
                minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);
                for(int shift=0;shift<32;shift+=8)maxDelta=Math.max(maxDelta,Math.abs(((pixels[i]>>>shift)&255)-((current[i]>>>shift)&255)));
            }
            differenceStats=count+","+pixels.length+","+minX+","+minY+","+(maxX+1)+","+(maxY+1)+","+maxDelta;
        }
        if(missReason!=0){clear();return null;}
        return title;
    }
    /** 调用方须已完成真实双帧标题确认、当次来源校验及排除规则检查。数组独立持有。 */
    void put(long ticket, long generation, long rulesRevision, int left, int top, int width, int height, int[] current, String title) {
        clear();
        if (current == null || width <= 0 || height <= 0 || (long) width * height != current.length
                || current.length > MAX_PIXELS || title == null || title.isEmpty()) return;
        this.ticket=ticket; this.generation=generation; this.rulesRevision=rulesRevision;
        this.left=left; this.top=top; this.width=width; this.height=height;
        pixels=current.clone(); this.title=title;
    }

    void clear() {
        if (pixels != null) Arrays.fill(pixels, 0);
        pixels=null;
        title=null;
    }
}
