package com.codextemp.wechatcaptureprobe;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/** 固定虚构画面的检测阶段筛查；不验证SDK结果，也不使用手机图像。 */
public final class GridTranslationProbe {
    private static int checks, cases, coarseChanges, fineChanges, coarseInkLoss, fineInkLoss;
    private static long coarseReads, fineReads;

    public static void main(String[] args) throws Exception {
        for (int width : new int[]{720, 1080})
            for (boolean dark : new boolean[]{false, true})
                for (boolean outgoing : new boolean[]{false, true})
                    for (int pattern = 0; pattern < 3; pattern++) run(width, dark, outgoing, pattern);
        System.out.println("GRID_PROBE checks="+checks+" cases="+cases+" coarseChanges="+coarseChanges
                +" fineChanges="+fineChanges+" coarseInkLoss="+coarseInkLoss+" fineInkLoss="+fineInkLoss
                +" coarseReads="+coarseReads+" fineReads="+fineReads);
        require(fineChanges == 0, "fine-translation-invariance");
        require(fineInkLoss == 0, "fine-fixed-ink-coverage");
        require(coarseChanges > 0, "coarse-aliasing-reproduced");
    }

    private static void run(int width, boolean dark, boolean outgoing, int pattern) throws Exception {
        int height = 660, left = 210, top = 190, bw = 271, bh = 139;
        float density = width / 360f;
        int oldCoarseLoss=coarseInkLoss,oldFineLoss=fineInkLoss;
        int oldCoarseChanges=coarseChanges,oldFineChanges=fineChanges;
        int[] baseCoarse = null, baseFine = null;
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
            PixelChatLayout.Pixels fineSource = (x,y) -> { reads[0]++; return pixels[y*width+x]; };
            int[] fine = bounds(PixelChatLayout.class, PixelChatLayout.Pixels.class, fineSource, width, height, density, dark);
            fineReads += reads[0]; cases++;
            require(coarse.length == 5 && fine.length == 5, "one-fixed-bubble");
            require(fine[4] == (outgoing ? 1 : 0), "fine-direction");
            if (lost(ink,width,coarse)>0) coarseInkLoss++;
            if (lost(ink,width,fine)>0) fineInkLoss++;
            coarse[0]-=dx;coarse[2]-=dx;coarse[1]-=dy;coarse[3]-=dy;
            fine[0]-=dx;fine[2]-=dx;fine[1]-=dy;fine[3]-=dy;
            if(baseCoarse==null){baseCoarse=coarse;baseFine=fine;}
            else {if(!Arrays.equals(baseCoarse,coarse))coarseChanges++;if(!Arrays.equals(baseFine,fine))fineChanges++;}
        }
        System.out.println("GRID_CASE width="+width+" dark="+dark+" outgoing="+outgoing+" pattern="+pattern
                +" coarseChanges="+(coarseChanges-oldCoarseChanges)+" fineChanges="+(fineChanges-oldFineChanges)
                +" coarseInkLoss="+(coarseInkLoss-oldCoarseLoss)+" fineInkLoss="+(fineInkLoss-oldFineLoss));
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
