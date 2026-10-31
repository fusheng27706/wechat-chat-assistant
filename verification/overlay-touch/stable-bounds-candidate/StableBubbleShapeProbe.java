package com.codextemp.wechatcaptureprobe;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 固定圆角/尾巴/阴影的网格相位诊断，按完整数组统计不同裁剪，不用哈希代替相等。 */
public final class StableBubbleShapeProbe {
    private static int color(int x, int y, int shadow) {
        boolean rect=x>=200&&x<800&&y>=300&&y<480;
        boolean rounded=rect && !(x<208&&y<308) && !(x>=792&&y<308)
                && !(x<208&&y>=472) && !(x>=792&&y>=472);
        boolean tail=x>=194&&x<200&&y>=340&&y<346;
        if(rounded||tail) return x>240&&x<600&&y>340&&y<420&&x%20<5?0xff111111:0xffffffff;
        if(shadow>0&&x>=200-shadow&&x<800+shadow&&y>=300-shadow&&y<480+shadow)return 0xffdddddd;
        return 0xffededed;
    }
    private static void remember(List<int[]> values,int[] pixels) {
        for(int[] old:values)if(Arrays.equals(old,pixels))return;
        values.add(pixels);
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception {
        Method current=ChatLayout.class.getDeclaredMethod("findBubbles",int.class,int.class,int.class,float.class,ChatLayout.Pixels.class,boolean.class);
        Method old=GridChatLayout.class.getDeclaredMethod("findBubbles",int.class,int.class,int.class,float.class,GridChatLayout.Pixels.class,boolean.class);
        current.setAccessible(true);old.setAccessible(true);
        for(int shadow:new int[]{0,1,2,3}) {
            List<int[]> prior=new ArrayList<>(),next=new ArrayList<>(); long[] reads={0,0}; int stabilized=0;
            for(int dx=0;dx<3;dx++)for(int dy=0;dy<3;dy++) {
                final int shiftX=dx,shiftY=dy;
                ChatLayout.Pixels pixels=(x,y)->{reads[0]++;return color(x-shiftX,y-shiftY,shadow);};
                GridChatLayout.Pixels previous=(x,y)->{reads[1]++;return color(x-shiftX,y-shiftY,shadow);};
                List<ChatLayout.Box> boxes=(List<ChatLayout.Box>)current.invoke(null,1080,132,1900,2.75f,pixels,false);
                List<GridChatLayout.Box> before=(List<GridChatLayout.Box>)old.invoke(null,1080,132,1900,2.75f,previous,false);
                if(boxes.size()!=1||before.size()!=1)throw new AssertionError("shape-count");
                ChatLayout.Box b=boxes.get(0);GridChatLayout.Box a=before.get(0);
                if(b.left>a.left||b.top>a.top||b.right<a.right||b.bottom<a.bottom)throw new AssertionError("old-content-cut");
                if(b.stabilized)stabilized++;
                int[] p=new int[(a.right-a.left)*(a.bottom-a.top)],n=new int[(b.right-b.left)*(b.bottom-b.top)];
                for(int y=a.top;y<a.bottom;y++)for(int x=a.left;x<a.right;x++)p[(y-a.top)*(a.right-a.left)+x-a.left]=color(x-shiftX,y-shiftY,shadow);
                for(int y=b.top;y<b.bottom;y++)for(int x=b.left;x<b.right;x++)n[(y-b.top)*(b.right-b.left)+x-b.left]=color(x-shiftX,y-shiftY,shadow);
                remember(prior,p);remember(next,n);
            }
            System.out.println("syntheticShape shadow="+shadow+" phases=9 stabilized="+stabilized+" oldVariants="+prior.size()
                    +" newVariants="+next.size()+" oldReads="+reads[1]+" newReads="+reads[0]);
        }
    }
}
