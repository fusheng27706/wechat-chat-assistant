package com.codextemp.wechatcaptureprobe;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** 固定像素的新旧输出对照；微基准含Java分配/GC，不等于OCR或整机响应。 */
public final class GraphicDetectorRegression {
    private static int checks;
    private static volatile int sink;
    private static void require(boolean condition) { checks++; if (!condition) throw new AssertionError("graphic-equivalence"); }
    private static int compare(int[] pixels, int width, int height, float density) {
        ChatLayout.Box box = new ChatLayout.Box(7, 9, 7 + width, 9 + height, false);
        ChatLayout.Pixels source = (x,y) -> pixels[(y-9)*width+x-7];
        List<BaselineGraphicDetector.Region> before = BaselineGraphicDetector.detect(source, box, density);
        List<GraphicDetector.Region> after = GraphicDetector.detect(source, box, density);
        require(before.size()==after.size());
        for(int i=0;i<before.size();i++) {
            var a=before.get(i); var b=after.get(i);
            require(a.left==b.left && a.top==b.top && a.right==b.right && a.bottom==b.bottom);
        }
        return after.size();
    }
    private static int[] fixture(int width, int height, int background, boolean graphic) {
        int[] pixels = new int[width*height]; Arrays.fill(pixels,background);
        for(int y=12;y<height-12;y++) for(int x=12;x<width-12;x++) {
            if(graphic && x>=width/2 && x<width/2+40 && y>=height/2 && y<height/2+40) pixels[y*width+x]=0xffff2020;
            else if(y%20<3 && x%16<7) pixels[y*width+x]=0xff202020;
        }
        return pixels;
    }
    public static String run() {
        checks=0;
        for(int background:new int[]{0xffffffff,0xff95ec69,0xff202020}) {
            require(compare(fixture(620,180,background,false),620,180,2.75f)==0);
            require(compare(fixture(620,180,background,true),620,180,2.75f)>0);
        }
        Random random = new Random(73021);
        int cases=6;
        for(int round=0;round<200;round++) {
            int width=1+random.nextInt(240),height=1+random.nextInt(160);
            int[] pixels=new int[width*height];
            for(int i=0;i<pixels.length;i++) pixels[i]=random.nextInt(5)==0 ? random.nextInt() : 0xffffffff;
            compare(pixels,width,height,1+random.nextFloat()*2); cases++;
        }
        for(int width:new int[]{1,2,3,39,40,41}) for(int height:new int[]{1,2,3,39,40,41}) {
            int[] pixels=new int[width*height]; Arrays.fill(pixels,0xffffffff);
            pixels[0]=0xffff0000; pixels[pixels.length-1]=0xff0000ff;
            compare(pixels,width,height,1); cases++;
        }
        return "PASS graphicChecks="+checks+" cases="+cases+" "+measure(false)+" "+measure(true)+" syntheticPixels=true";
    }
    private static String measure(boolean graphic) {
        int width=620,height=180;
        int[] pixels=fixture(width,height,0xffffffff,graphic);
        ChatLayout.Box box=new ChatLayout.Box(0,0,width,height,false);
        ChatLayout.Pixels source=(x,y)->pixels[y*width+x];
        long[] oldTime=new long[21],newTime=new long[21];
        for(int round=-6;round<21;round++) for(int turn=0;turn<2;turn++) {
            boolean candidate=((round+turn)&1)==0;
            long began=System.nanoTime();
            for(int i=0;i<30;i++) sink+=candidate ? GraphicDetector.detect(source,box,2.75f).size()
                    : BaselineGraphicDetector.detect(source,box,2.75f).size();
            long perCall=(System.nanoTime()-began)/30;
            if(round>=0) { if(candidate)newTime[round]=perCall;else oldTime[round]=perCall; }
        }
        Arrays.sort(oldTime); Arrays.sort(newTime);
        return (graphic?"colored":"plain")+"OldMedianUs="+oldTime[10]/1000+" "+(graphic?"colored":"plain")
                +"NewMedianUs="+newTime[10]/1000+" "+(graphic?"colored":"plain")+"OldP95Us="+oldTime[19]/1000
                +" "+(graphic?"colored":"plain")+"NewP95Us="+newTime[19]/1000;
    }
    public static void main(String[] args) { System.out.println(run()); }
}
