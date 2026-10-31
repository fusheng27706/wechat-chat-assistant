package com.codextemp.wechatcaptureprobe;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/** 固定虚构画面的检测阶段筛查；不验证SDK结果，也不使用手机图像。 */
public final class TightTranslationProbe {
    private static int checks, cases, coarseChanges, tightChanges, coarseInkLoss, tightInkLoss;
    private static long coarseReads, tightReads;
    private static int tightPixelChanges;

    public static void main(String[] args) throws Exception {
        for (int width : new int[]{720, 1080})
            for (boolean dark : new boolean[]{false, true})
                for (boolean outgoing : new boolean[]{false, true})
                    for (int pattern = 0; pattern < 3; pattern++) run(width, dark, outgoing, pattern);
        System.out.println("GRID_PROBE checks="+checks+" cases="+cases+" coarseChanges="+coarseChanges
                +" tightChanges="+tightChanges+" coarseInkLoss="+coarseInkLoss+" tightInkLoss="+tightInkLoss
                +" coarseReads="+coarseReads+" tightReads="+tightReads+" tightPixelChanges="+tightPixelChanges);
        require(tightChanges == 0, "tight-translation-invariance");
        require(tightInkLoss == 0, "tight-fixed-ink-coverage");
        require(tightPixelChanges == 0, "tight-complete-pixel-invariance");
        require(coarseChanges > 0, "coarse-aliasing-reproduced");
    }

    private static void run(int width, boolean dark, boolean outgoing, int pattern) throws Exception {
        int height = 660, left = 210, top = 190, bw = 271, bh = 139;
        float density = width / 360f;
        int oldCoarseLoss=coarseInkLoss,oldTightLoss=tightInkLoss;
        int oldCoarseChanges=coarseChanges,oldTightChanges=tightChanges;
        int[] baseCoarse = null, baseTight = null;
        int[] basePixels = null;
        for (int dy = 0; dy < 6; dy++) for (int dx = 0; dx < 6; dx++) {
            int[] pixels = new int[width * height];
            boolean[] ink = new boolean[pixels.length];
            Arrays.fill(pixels, dark ? 0xff171717 : 0xffededed);
            int bubble = outgoing ? 0xff95ec69 : dark ? 0xff404040 : 0xffffffff;
            for (int y = 0; y < bh; y++) for (int x = 0; x < bw; x++) {
                // 圆角、内部笔画和贴近边缘的笔画均固定，平移不改变像素内容。
                if ((x < 5 || x >= bw-5) && (y < 5 || y >= bh-5)) continue;
                int at = (top+dy+y)*width+left+dx+x;
                boolean mark = pattern == 0 ? x>20 && x<245 && y>18 && y<120 && x%13<3 && y%19<14
                        : pattern == 1 ? x>1 && x<bw-2 && (y==2 || y==bh-3 || x==2 || x==bw-3)
                        : x>12 && x<250 && y>15 && y<124 && ((x+y)%23<3);
                pixels[at] = mark ? (dark ? 0xffdddddd : 0xff222222) : bubble;
                ink[at] = mark;
            }
            long[] reads = {0};
            ChatLayout.Pixels source = (x,y) -> { reads[0]++; return pixels[y*width+x]; };
            int[] coarse = bounds(ChatLayout.class, ChatLayout.Pixels.class, source, width, height, density, dark);
            coarseReads += reads[0]; reads[0] = 0;
            TightChatLayout.Pixels tightSource = (x,y) -> { reads[0]++; return pixels[y*width+x]; };
            int[] tight = bounds(TightChatLayout.class, TightChatLayout.Pixels.class, tightSource, width, height, density, dark);
            tightReads += reads[0]; cases++;
            require(coarse.length == 5 && tight.length == 5, "one-fixed-bubble");
            require(tight[4] == (outgoing ? 1 : 0), "tight-direction");
            int tw=tight[2]-tight[0],th=tight[3]-tight[1];
            int[] selected=new int[tw*th];
            for(int y=0;y<th;y++)System.arraycopy(pixels,(y+tight[1])*width+tight[0],selected,y*tw,tw);
            if(basePixels==null)basePixels=selected;
            else if(!Arrays.equals(basePixels,selected))tightPixelChanges++;
            if (lost(ink,width,coarse)>0) coarseInkLoss++;
            if (lost(ink,width,tight)>0) {
                if (tightInkLoss == oldTightLoss) System.out.println("TIGHT_FIRST_LOSS width="+width
                        +" dark="+dark+" outgoing="+outgoing+" pattern="+pattern+" dx="+dx+" dy="+dy
                        +" coarse="+Arrays.toString(coarse)+" tight="+Arrays.toString(tight));
                tightInkLoss++;
            }
            coarse[0]-=dx;coarse[2]-=dx;coarse[1]-=dy;coarse[3]-=dy;
            tight[0]-=dx;tight[2]-=dx;tight[1]-=dy;tight[3]-=dy;
            if(baseCoarse==null){baseCoarse=coarse;baseTight=tight;}
            else {if(!Arrays.equals(baseCoarse,coarse))coarseChanges++;if(!Arrays.equals(baseTight,tight))tightChanges++;}
        }
        System.out.println("GRID_CASE width="+width+" dark="+dark+" outgoing="+outgoing+" pattern="+pattern
                +" coarseChanges="+(coarseChanges-oldCoarseChanges)+" tightChanges="+(tightChanges-oldTightChanges)
                +" coarseInkLoss="+(coarseInkLoss-oldCoarseLoss)+" tightInkLoss="+(tightInkLoss-oldTightLoss));
    }

    private static int[] bounds(Class<?> type,Class<?> pixelsType,Object pixels,int width,int height,float density,boolean dark) throws Exception {
        Method method=type.getDeclaredMethod("findBubbles",int.class,int.class,int.class,float.class,pixelsType,boolean.class);
        method.setAccessible(true);
        List<?> boxes=(List<?>)method.invoke(null,width,80,height-50,density,pixels,dark);
        if(boxes.size()!=1)return new int[0];
        Object box=boxes.get(0);String[] names={"left","top","right","bottom"};int[] result=new int[5];
        for(int i=0;i<4;i++){java.lang.reflect.Field f=box.getClass().getDeclaredField(names[i]);f.setAccessible(true);result[i]=f.getInt(box);}
        java.lang.reflect.Field f=box.getClass().getDeclaredField("outgoing");f.setAccessible(true);result[4]=f.getBoolean(box)?1:0;
        return result;
    }
    private static int lost(boolean[] ink,int width,int[] box){int count=0;for(int i=0;i<ink.length;i++)if(ink[i]&&(i%width<box[0]||i%width>=box[2]||i/width<box[1]||i/width>=box[3]))count++;return count;}
    private static void require(boolean pass,String name){checks++;if(!pass)throw new AssertionError(name);}
}
