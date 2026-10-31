package com.codextemp.wechatcaptureprobe;

import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

/** 将粗网格框细化为随气泡平移的固定边距框；失败保留原框，不授予来源或缓存许可。 */
final class StableBubbleBounds {
    private static final int MAX_PIXELS = 262144;
    private static final int MAX_FRAME_PIXELS = 1048576;
    private static final class Scratch {
        byte[] mask = new byte[0];
        int[] queue = new int[0];
        void ensure(int length) {
            if(mask.length < length) { mask=new byte[length];queue=new int[length]; }
        }
    }
    static List<ChatLayout.Box> refineAll(List<ChatLayout.Box> coarse, int step, int left, int top,
                                          int right, int bottom, boolean dark, ChatLayout.Pixels pixels) {
        List<ChatLayout.Box> refined = new ArrayList<>();
        Scratch scratch = new Scratch();
        boolean[] selected=new boolean[coarse.size()];
        int remaining=MAX_FRAME_PIXELS,largest=0;
        for(int i=0;i<coarse.size();i++) {
            ChatLayout.Box box=coarse.get(i);
            long area=((long)box.right-box.left+4L*step)*((long)box.bottom-box.top+4L*step);
            if(area>0&&area<=MAX_PIXELS&&area<=remaining){selected[i]=true;remaining-=(int)area;largest=Math.max(largest,(int)area);}
        }
        // 同帧只分配一个有界工作区，防止大大小小的气泡反复扩容并触发GC。
        scratch.ensure(largest);
        for(int i=0;i<coarse.size();i++)refined.add(selected[i]
                ?refine(coarse.get(i),step,left,top,right,bottom,dark,pixels,scratch):coarse.get(i));
        boolean[] overlapping = new boolean[coarse.size()];
        for (int i = 0; i < refined.size(); i++) for (int j = i + 1; j < refined.size(); j++) {
            ChatLayout.Box a = refined.get(i), b = refined.get(j);
            if (a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom)
                overlapping[i] = overlapping[j] = true;
        }
        for (int i = 0; i < overlapping.length; i++) if (overlapping[i]) refined.set(i, coarse.get(i));
        return refined;
    }
    static ChatLayout.Box refine(ChatLayout.Box coarse, int step, int leftLimit, int topLimit,
                                 int rightLimit, int bottomLimit, boolean dark, ChatLayout.Pixels pixels) {
        return refine(coarse,step,leftLimit,topLimit,rightLimit,bottomLimit,dark,pixels,new Scratch());
    }
    private static ChatLayout.Box refine(ChatLayout.Box coarse, int step, int leftLimit, int topLimit,
                                         int rightLimit, int bottomLimit, boolean dark, ChatLayout.Pixels pixels, Scratch scratch) {
        // 两个网格宽容纳边缘抗锯齿/浅阴影；输出仍只加一个网格的固定背景边距。
        int radius = step * 2;
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
                foundKind |= ChatLayout.bubbleKind(color, dark) == expected;
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
        int refinedLeft = minX - step, refinedTop = minY - step;
        int refinedRight = maxX + 1 + step, refinedBottom = maxY + 1 + step;
        // 固定边距必须包含全部旧OCR输入，且仍位于同一可读内容范围；不靠裁掉差异换命中。
        if (refinedLeft > coarse.left || refinedTop > coarse.top || refinedRight < coarse.right
                || refinedBottom < coarse.bottom || refinedLeft < leftLimit || refinedTop < topLimit
                || refinedRight > rightLimit || refinedBottom > bottomLimit) return coarse;
        // 精确框加边距可能超出搜索框；全部新增边距必须仍是相同背景，不夹入额外文字或邻居。
        for (int y = refinedTop; y < refinedBottom; y++) for (int x = refinedLeft; x < refinedRight; x++) {
            if (x >= minX && x <= maxX && y >= minY && y <= maxY) continue;
            if (pixels.colorAt(x, y) != background) return coarse;
        }
        return new ChatLayout.Box(refinedLeft, refinedTop, refinedRight, refinedBottom, coarse.outgoing, true);
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
    private StableBubbleBounds() {}
}
