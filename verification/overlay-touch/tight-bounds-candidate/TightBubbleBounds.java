package com.codextemp.wechatcaptureprobe;

import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

/** 隔离候选：完整非背景轮廓，不额外添加背景边距；失败保留原框。 */
final class TightBubbleBounds {
    private static final int MAX_PIXELS = 262144;
    private static final int MAX_FRAME_PIXELS = 1048576;
    private static final class Scratch {
        byte[] mask = new byte[0];
        int[] queue = new int[0];
        void ensure(int length) {
            if(mask.length < length) { mask=new byte[length];queue=new int[length]; }
        }
    }
    static List<TightChatLayout.Box> refineAll(List<TightChatLayout.Box> coarse, int step, int left, int top,
                                          int right, int bottom, boolean dark, TightChatLayout.Pixels pixels) {
        List<TightChatLayout.Box> refined = new ArrayList<>();
        Scratch scratch = new Scratch();
        boolean[] selected=new boolean[coarse.size()];
        int remaining=MAX_FRAME_PIXELS,largest=0;
        for(int i=0;i<coarse.size();i++) {
            TightChatLayout.Box box=coarse.get(i);
            long area=((long)box.right-box.left+4L*step+4)*((long)box.bottom-box.top+4L*step+4);
            if(area>0&&area<=MAX_PIXELS&&area<=remaining){selected[i]=true;remaining-=(int)area;largest=Math.max(largest,(int)area);}
        }
        // 同帧只分配一个有界工作区，防止大大小小的气泡反复扩容并触发GC。
        scratch.ensure(largest);
        for(int i=0;i<coarse.size();i++)refined.add(selected[i]
                ?refine(coarse.get(i),step,left,top,right,bottom,dark,pixels,scratch):coarse.get(i));
        restoreOverlaps(refined, coarse);
        return refined;
    }
    /** 回退可能让旧框再碰到其它新框；每轮至少恢复一个新框，直至没有新的冲突。 */
    static void restoreOverlaps(List<TightChatLayout.Box> refined, List<TightChatLayout.Box> coarse) {
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < refined.size(); i++) for (int j = i + 1; j < refined.size(); j++) {
                TightChatLayout.Box a = refined.get(i), b = refined.get(j);
                if (a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
                        && (a != coarse.get(i) || b != coarse.get(j))) {
                    refined.set(i, coarse.get(i)); refined.set(j, coarse.get(j)); changed = true;
                }
            }
        } while (changed);
    }
    static TightChatLayout.Box refine(TightChatLayout.Box coarse, int step, int leftLimit, int topLimit,
                                 int rightLimit, int bottomLimit, boolean dark, TightChatLayout.Pixels pixels) {
        return refine(coarse,step,leftLimit,topLimit,rightLimit,bottomLimit,dark,pixels,new Scratch());
    }
    private static TightChatLayout.Box refine(TightChatLayout.Box coarse, int step, int leftLimit, int topLimit,
                                         int rightLimit, int bottomLimit, boolean dark, TightChatLayout.Pixels pixels, Scratch scratch) {
        // 额外两像素覆盖闭合贴边笔画导致的内缩；仅扩大搜索，外圈和预算拒绝条件不变。
        int radius = step * 2 + 2;
        int left = coarse.left - radius, top = coarse.top - radius;
        int right = coarse.right + radius, bottom = coarse.bottom + radius;
        if (step < 1 || left < leftLimit || top < topLimit || right > rightLimit || bottom > bottomLimit)
            return coarse;
        long area=(long)(right-left)*(bottom-top);
        if(area<=0||area>MAX_PIXELS)return coarse;
        int length=(int)area,scanWidth=right-left;
        scratch.ensure(length);
        try {
        int minX = right, minY = bottom, maxX = left - 1, maxY = top - 1;
        int expected = coarse.outgoing ? 2 : 1;
        int background = pixels.colorAt(left, top);
        boolean foundKind = false;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                int color = pixels.colorAt(x, y);
                if (color == background) continue;
                // 非均匀外圈无法证明局部对象完整；复杂背景、邻居接触等保持原裁剪。
                if (x == left || y == top || x == right - 1 || y == bottom - 1) return coarse;
                scratch.mask[(y-top)*scanWidth+x-left]=1;
                foundKind |= TightChatLayout.bubbleKind(color, dark) == expected;
                minX = Math.min(minX, x); minY = Math.min(minY, y);
                maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
            }
        }
        if (!foundKind || maxX < minX || maxY < minY) return coarse;
        // 八邻接背景保留对角出口；剩余区域按四邻接检查。填孔仅用于判断，原始像素绝不改写。
        flood(scratch,scanWidth,length,0,true);
        int seed=0;
        while(seed<length&&scratch.mask[seed]==2)seed++;
        if(seed==length)return coarse;
        flood(scratch,scanWidth,length,seed,false);
        for(int i=0;i<length;i++)if(scratch.mask[i]!=2&&scratch.mask[i]!=3)return coarse;
        int refinedLeft = minX, refinedTop = minY;
        int refinedRight = maxX + 1, refinedBottom = maxY + 1;
        // 搜索已覆盖旧框并统计全部非背景像素；只允许舍弃与已确认外圈相同的背景。
        // 这不证明SDK输出等价，必须另做完整文字/图形对照，不能直接发布。
        if (refinedLeft < leftLimit || refinedTop < topLimit || refinedRight > rightLimit
                || refinedBottom > bottomLimit) return coarse;
        return new TightChatLayout.Box(refinedLeft, refinedTop, refinedRight, refinedBottom, coarse.outgoing);
        } finally {
            Arrays.fill(scratch.mask,0,length,(byte)0);
            Arrays.fill(scratch.queue,0,length,0);
        }
    }
    private static boolean accepts(byte value,boolean exterior) { return exterior?value==0:value!=2&&value!=3; }
    private static void flood(Scratch scratch,int width,int length,int seed,boolean exterior) {
        byte mark=(byte)(exterior?2:3);
        int head=0,tail=1;scratch.queue[0]=seed;scratch.mask[seed]=mark;
        while(head<tail) {
            int at=scratch.queue[head++],x=at%width;
            if(x>0&&accepts(scratch.mask[at-1],exterior)){scratch.mask[at-1]=mark;scratch.queue[tail++]=at-1;}
            if(x+1<width&&accepts(scratch.mask[at+1],exterior)){scratch.mask[at+1]=mark;scratch.queue[tail++]=at+1;}
            if(at>=width&&accepts(scratch.mask[at-width],exterior)){scratch.mask[at-width]=mark;scratch.queue[tail++]=at-width;}
            if(at+width<length&&accepts(scratch.mask[at+width],exterior)){scratch.mask[at+width]=mark;scratch.queue[tail++]=at+width;}
            if(exterior) {
                if(at>=width&&x>0&&scratch.mask[at-width-1]==0){scratch.mask[at-width-1]=mark;scratch.queue[tail++]=at-width-1;}
                if(at>=width&&x+1<width&&scratch.mask[at-width+1]==0){scratch.mask[at-width+1]=mark;scratch.queue[tail++]=at-width+1;}
                if(at+width<length&&x>0&&scratch.mask[at+width-1]==0){scratch.mask[at+width-1]=mark;scratch.queue[tail++]=at+width-1;}
                if(at+width<length&&x+1<width&&scratch.mask[at+width+1]==0){scratch.mask[at+width+1]=mark;scratch.queue[tail++]=at+width+1;}
            }
        }
    }
    private TightBubbleBounds() {}
}
