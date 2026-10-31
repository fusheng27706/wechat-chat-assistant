package com.codextemp.wechatcaptureprobe;

import java.util.ArrayList;
import java.util.Arrays;

/** 同一已确认会话内保留少量完整标题输入；计算结果复用不授予来源许可。 */
final class HeaderRecognitionCache {
    private static final int MAX_ENTRY_PIXELS=262144, MAX_TOTAL_PIXELS=524288, MAX_ENTRIES=4;
    private static final class Entry {
        final int[] pixels;
        final String title;
        Entry(int[] pixels,String title){this.pixels=pixels.clone();this.title=title;}
    }
    private final ArrayList<Entry> entries=new ArrayList<>();
    private int storedPixels;
    private long ticket,generation,rulesRevision;
    private int left,top,width,height;
    private boolean matches(long ticket,long generation,long rulesRevision,int left,int top,int width,int height){
        return this.ticket==ticket&&this.generation==generation&&this.rulesRevision==rulesRevision
                &&this.left==left&&this.top==top&&this.width==width&&this.height==height;
    }
    String get(long ticket,long generation,long rulesRevision,int left,int top,int width,int height,int[] current){
        if(entries.isEmpty())return null;
        if(!matches(ticket,generation,rulesRevision,left,top,width,height)){clear();return null;}
        for(int i=entries.size()-1;i>=0;i--){
            Entry entry=entries.get(i);
            if(Arrays.equals(entry.pixels,current)){
                entries.remove(i);entries.add(entry);
                return entry.title;
            }
        }
        // 滚动可能交替出现完整像素相同的旧输入；仅像素失配不撤销同会话其它计算结果。
        return null;
    }
    /** 仅在双帧真实标题确认及当次来源/排除规则检查通过后写入，副本由本类持有。 */
    void put(long ticket,long generation,long rulesRevision,int left,int top,int width,int height,int[] current,String title){
        if(current==null||width<=0||height<=0||(long)width*height!=current.length
                ||current.length>MAX_ENTRY_PIXELS||title==null||title.isEmpty()){clear();return;}
        if(!matches(ticket,generation,rulesRevision,left,top,width,height))clear();
        this.ticket=ticket;this.generation=generation;this.rulesRevision=rulesRevision;
        this.left=left;this.top=top;this.width=width;this.height=height;
        for(int i=entries.size()-1;i>=0;i--)if(Arrays.equals(entries.get(i).pixels,current))remove(i);
        while(!entries.isEmpty()&&(entries.size()>=MAX_ENTRIES||storedPixels+current.length>MAX_TOTAL_PIXELS))remove(0);
        entries.add(new Entry(current,title));storedPixels+=current.length;
    }
    private void remove(int index){Entry entry=entries.remove(index);storedPixels-=entry.pixels.length;Arrays.fill(entry.pixels,0);}
    void clear(){while(!entries.isEmpty())remove(entries.size()-1);}
}
