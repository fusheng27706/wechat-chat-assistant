package com.codextemp.wechatcaptureprobe;

import java.lang.reflect.Method;
import java.util.List;

/** 固定像素对照裁剪变化与图形遮蔽，不使用真实图像或把形状测试当OCR结论。 */
public final class CropGraphicProbe {
    private static boolean ink(int x,int y,int w,int h) {
        return x>=20&&x<w-20&&y>=20&&y<h-20&&x%13<4&&y%17<8;
    }
    private static int color(int x,int y,int w,int h,boolean green) {
        boolean body=x>=0&&x<w&&y>=0&&y<h;
        boolean corner=(x<8||x>=w-8)&&(y<8||y>=h-8);
        if(body&&!corner)return ink(x,y,w,h)?0xff111111:green?0xff95ec69:0xffffffff;
        return 0xffededed;
    }
    private static int covered(List<GraphicDetector.Region> regions,int dx,int dy,int w,int h) {
        int count=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(ink(x,y,w,h)) {
            for(var r:regions)if(x+dx>=r.left-2&&x+dx<r.right+2&&y+dy>=r.top-2&&y+dy<r.bottom+2){count++;break;}
        }
        return count;
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception {
        Method old=GridChatLayout.class.getDeclaredMethod("findBubbles",int.class,int.class,int.class,float.class,GridChatLayout.Pixels.class,boolean.class);
        Method next=ChatLayout.class.getDeclaredMethod("findBubbles",int.class,int.class,int.class,float.class,ChatLayout.Pixels.class,boolean.class);
        old.setAccessible(true);next.setAccessible(true);
        int cases=0,changed=0,newCovered=0,missing=0;
        for(boolean green:new boolean[]{false,true})for(int w:new int[]{72,96,120,180,300,600})for(int h:new int[]{60,90,120,180})for(int phase=0;phase<3;phase++) {
            final int dx=200+phase,dy=300+phase;
            ChatLayout.Pixels pixels=(x,y)->color(x-dx,y-dy,w,h,green);
            List<GridChatLayout.Box> before=(List<GridChatLayout.Box>)old.invoke(null,1080,132,1900,2.75f,(GridChatLayout.Pixels)pixels::colorAt,false);
            List<ChatLayout.Box> after=(List<ChatLayout.Box>)next.invoke(null,1080,132,1900,2.75f,pixels,false);
            cases++;
            if(before.size()!=1||after.size()!=1){missing++;continue;}
            var b=before.get(0);var n=after.get(0);
            ChatLayout.Box b2=new ChatLayout.Box(b.left,b.top,b.right,b.bottom,b.outgoing);
            var br=GraphicDetector.detect(pixels,b2,2.75f);var nr=GraphicDetector.detect(pixels,n,2.75f);
            int bc=covered(br,dx,dy,w,h),nc=covered(nr,dx,dy,w,h);
            if(br.size()!=nr.size()||bc!=nc) {
                changed++;if(nc>bc)newCovered++;
                System.out.println("cropGraphic green="+green+" width="+w+" height="+h+" phase="+phase+" stabilized="+n.stabilized+" oldRegions="+br.size()+" newRegions="+nr.size()+" oldInkCovered="+bc+" newInkCovered="+nc);
            }
        }
        System.out.println("COMPLETED cases="+cases+" changed="+changed+" increasedInkMask="+newCovered+" unavailableBoxes="+missing+" syntheticOnly=true");
    }
}
