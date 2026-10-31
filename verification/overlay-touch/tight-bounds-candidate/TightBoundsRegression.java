package com.codextemp.wechatcaptureprobe;

import java.util.Arrays;
import java.util.List;

/** 固定无文字像素检查拒绝路径；不代表实际遮挡或OCR验收。 */
public final class TightBoundsRegression {
    private static int checks;
    private static final int BG=0xffededed, IN=0xffffffff;
    private static void require(boolean value,String name) { checks++; if(!value)throw new AssertionError(name); }
    private static TightChatLayout.Box box(int l,int t,int r,int b) {return new TightChatLayout.Box(l,t,r,b,false);}
    private static boolean same(TightChatLayout.Box a,TightChatLayout.Box b) {
        return a.left==b.left&&a.top==b.top&&a.right==b.right&&a.bottom==b.bottom&&a.outgoing==b.outgoing;
    }
    public static void main(String[] args) {
        int[] pixels=new int[500*500]; Arrays.fill(pixels,BG);
        for(int y=200;y<280;y++)for(int x=200;x<300;x++)pixels[y*500+x]=IN;
        TightChatLayout.Pixels source=(x,y)->pixels[y*500+x];
        var coarse=box(201,201,300,282);
        var expected=box(200,200,300,280);
        require(same(TightBubbleBounds.refine(coarse,3,0,0,500,500,false,source),expected),"tight-envelope");
        for(int side=0;side<4;side++) {
            require(TightBubbleBounds.refine(coarse,3,side==0?200:0,side==1?200:0,
                    side==2?300:500,side==3?282:500,false,source)==coarse,"readable-edge-fallback");
        }
        pixels[195*500+195]=0xff111111;
        require(TightBubbleBounds.refine(coarse,3,0,0,500,500,false,source)==coarse,"isolated-object-fallback");
        pixels[195*500+195]=BG;
        pixels[193*500+195]=0xff111111;
        require(TightBubbleBounds.refine(coarse,3,0,0,500,500,false,source)==coarse,"outer-ring-fallback");
        pixels[193*500+195]=BG;
        // 新邻居进入搜索外圈；即使同色也不能通过背景完整性检查。
        for(int gap=1;gap<=7;gap++) {
            final int distance=gap;
            TightChatLayout.Pixels neighbor=(x,y)->x>=300+distance&&y>=200&&y<280?IN:source.colorAt(x,y);
            require(TightBubbleBounds.refine(coarse,3,0,0,500,500,false,neighbor)==coarse,"neighbor-fallback");
        }
        var enormous=box(100,100,1000,1000);
        require(TightBubbleBounds.refine(enormous,3,0,0,2000,2000,false,(x,y)->{throw new AssertionError("budget-read");})
                ==enormous,"single-area-budget");
        var duplicate=box(202,202,301,281);
        List<TightChatLayout.Box> conflict=TightBubbleBounds.refineAll(Arrays.asList(coarse,duplicate),3,0,0,500,500,false,source);
        require(conflict.get(0)==coarse&&conflict.get(1)==duplicate,"overlap-restores-both");
        List<TightChatLayout.Box> originals=Arrays.asList(box(0,0,10,10),box(9,0,20,10),box(19,0,30,10));
        List<TightChatLayout.Box> refinements=new java.util.ArrayList<>(Arrays.asList(box(1,0,9,10),box(8,0,12,10),box(19,0,28,10)));
        TightBubbleBounds.restoreOverlaps(refinements,originals);
        require(refinements.get(0)==originals.get(0)&&refinements.get(1)==originals.get(1)
                &&refinements.get(2)==originals.get(2),"cascading-overlap-restoration");
        pixels[195*500+195]=0xff111111;
        for(int y=350;y<410;y++)for(int x=200;x<330;x++)pixels[y*500+x]=IN;
        var second=box(201,351,330,412);
        List<TightChatLayout.Box> reuse=TightBubbleBounds.refineAll(Arrays.asList(coarse,second),3,0,0,500,500,false,source);
        require(reuse.get(0)==coarse&&same(reuse.get(1),box(200,350,330,410)),"same-scratch-after-reject");
        int[] reads=new int[5];
        List<TightChatLayout.Box> large=new java.util.ArrayList<>();
        for(int i=0;i<5;i++)large.add(box(20+i*500,20,470+i*500,470));
        List<TightChatLayout.Box> limited=TightBubbleBounds.refineAll(large,3,0,0,3000,600,false,(x,y)->{reads[x/500]++;return BG;});
        // 每个选中框先读一个背景锚点，再扫完整466平方区域；第五框不进入扫描。
        require(reads[0]==466*466+1&&reads[1]==reads[0]&&reads[2]==reads[0]&&reads[3]==reads[0]
                &&reads[4]==0&&limited.equals(large),"frame-budget-and-order");
        System.out.println("PASS tightBoundsChecks="+checks);
    }
}
