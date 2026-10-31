package com.codextemp.wechatcaptureprobe;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 固定像素可行性验证；预测只存精确裁剪，不能提供来源证明或代替OCR。 */
public final class PhasePredictionProbe {
    private static int checks, cases, normalHits, predictedHits, changedMisses, maxEntries;
    private static long normalReads, predictedReads;
    private static final class Crop {
        final int width,height;final boolean outgoing;final int[] pixels;
        Crop(int[] image,int screenWidth,ChatLayout.Box box) {
            width=box.right-box.left;height=box.bottom-box.top;outgoing=box.outgoing;
            pixels=new int[width*height];
            for(int y=0;y<height;y++)System.arraycopy(image,(box.top+y)*screenWidth+box.left,pixels,y*width,width);
        }
        String key(float density) {
            long fingerprint=1469598103934665603L;
            for(int p:pixels)fingerprint=(fingerprint^p)*1099511628211L;
            return width+":"+height+":"+outgoing+":"+Float.floatToIntBits(density)+":"+fingerprint;
        }
        boolean same(Crop other) {return width==other.width&&height==other.height&&outgoing==other.outgoing&&Arrays.equals(pixels,other.pixels);}
    }
    private static List<ChatLayout.Box> boxes(int[] image,int width,int phase,boolean dark,long[] reads) throws Exception {
        Method method=ChatLayout.class.getDeclaredMethod("findBubbles",int.class,int.class,int.class,float.class,ChatLayout.Pixels.class,boolean.class);
        method.setAccessible(true);
        ChatLayout.Pixels source=(x,y)->{reads[0]++;return image[y*width+x];};
        @SuppressWarnings("unchecked") List<ChatLayout.Box> found=(List<ChatLayout.Box>)method.invoke(null,width,80+phase,610,width/360f,source,dark);
        return found;
    }
    private static int[] image(int width,int dx,int dy,boolean dark,boolean outgoing,int pattern) {
        int[] image=new int[width*660];Arrays.fill(image,dark?0xff171717:0xffededed);
        int bw=271,bh=139,color=outgoing?0xff95ec69:dark?0xff404040:0xffffffff;
        for(int y=0;y<bh;y++)for(int x=0;x<bw;x++) {
            if((x<5||x>=bw-5)&&(y<5||y>=bh-5))continue;
            boolean ink=pattern==0?x>20&&x<245&&y>18&&y<120&&x%13<3&&y%19<14
                    :pattern==1?x>1&&x<bw-2&&(y==2||y==bh-3||x==2||x==bw-3)
                    :x>12&&x<250&&y>15&&y<124&&(x+y)%23<3;
            image[(190+dy+y)*width+210+dx+x]=ink?(dark?0xffdddddd:0xff222222):color;
        }
        return image;
    }
    private static void require(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    public static void main(String[] args)throws Exception {
        for(int width:new int[]{720,1080})for(boolean dark:new boolean[]{false,true})
            for(boolean outgoing:new boolean[]{false,true})for(int pattern=0;pattern<3;pattern++)
                for(int dx=0;dx<Math.max(2,width/360);dx++)run(width,dx,dark,outgoing,pattern);
        System.out.println("PHASE_PREDICTION cases="+cases+" normalHits="+normalHits+" predictedHits="+predictedHits
                +" changedMisses="+changedMisses+" maxEntriesPerScene="+maxEntries
                +" originalScanReads="+normalReads+" allPhaseScanReads="+predictedReads+" syntheticOnly=true");
        require(predictedHits==cases,"all-translated-crops-exact");
        require(changedMisses==cases,"changed-pixels-never-hit");
        require(normalHits<cases,"ordinary-phase-misses-reproduced");
        System.out.println("PASS phasePredictionChecks="+checks);
    }
    private static void run(int width,int dx,boolean dark,boolean outgoing,int pattern)throws Exception {
        int step=Math.max(2,width/360);float density=width/360f;
        int[] before=image(width,dx,0,dark,outgoing,pattern);
        ExactPixelCache<Integer> cache=new ExactPixelCache<>(1048576,32);
        List<Crop> crops=new ArrayList<>();Crop ordinary=null;
        try {
            for(int phase=0;phase<step;phase++) {
                long[] reads={0};List<ChatLayout.Box> found=boxes(before,width,phase,dark,reads);
                require(found.size()==1,"one-prediction-box");
                if(phase==0)normalReads+=reads[0];predictedReads+=reads[0];
                Crop crop=new Crop(before,width,found.get(0));crops.add(crop);
                if(phase==0)ordinary=crop;
                cache.put(crop.key(density),crop.pixels,phase,100);
            }
            maxEntries=Math.max(maxEntries,cache.size());
            for(int dy=0;dy<6;dy++) {
                long now=101+dy*2L;
                int[] after=image(width,dx,dy,dark,outgoing,pattern);
                try {
                    List<ChatLayout.Box> found=boxes(after,width,0,dark,new long[1]);
                    require(found.size()==1,"one-future-box");
                    Crop next=new Crop(after,width,found.get(0));
                    try {
                        cases++;if(ordinary.same(next))normalHits++;
                        Integer cached=cache.get(next.key(density),next.pixels,now);
                        boolean exact=false;for(Crop crop:crops)exact|=crop.same(next);
                        require((cached!=null)==exact,"real-cache-agrees-full-pixels");
                        if(cached!=null&&cache.activate(next.key(density),next.pixels,cached,now))predictedHits++;
                        String keyBefore=next.key(density);
                        next.pixels[next.pixels.length/2]^=0x00010101;
                        // 保留旧key故意制造指纹碰撞，完整像素比较仍必须拒绝。
                        if(cache.get(keyBefore,next.pixels,now+1)==null)changedMisses++;
                    } finally {Arrays.fill(next.pixels,0);}
                } finally {Arrays.fill(after,0);}
            }
        } finally {cache.clear();Arrays.fill(before,0);for(Crop crop:crops)Arrays.fill(crop.pixels,0);}
    }
}
