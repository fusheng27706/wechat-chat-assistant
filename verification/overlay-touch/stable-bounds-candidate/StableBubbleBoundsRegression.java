package com.codextemp.wechatcaptureprobe;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/** 固定像素检查裁剪平移稳定性与保守回退；不代表实际微信OCR通过。 */
public final class StableBubbleBoundsRegression {
    private static int checks;
    private static void require(boolean condition, String code) {
        checks++; if (!condition) throw new AssertionError(code);
    }
    private static int[] crop(ChatLayout.Box box, ChatLayout.Pixels pixels) {
        int width = box.right - box.left, height = box.bottom - box.top;
        int[] result = new int[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            result[y * width + x] = pixels.colorAt(box.left + x, box.top + y);
        return result;
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Method current = ChatLayout.class.getDeclaredMethod("findBubbles", int.class, int.class, int.class,
                float.class, ChatLayout.Pixels.class, boolean.class);
        Method baseline = GridChatLayout.class.getDeclaredMethod("findBubbles", int.class, int.class, int.class,
                float.class, GridChatLayout.Pixels.class, boolean.class);
        current.setAccessible(true); baseline.setAccessible(true);
        int stableCases = 0;
        for (int width : new int[]{720,1080,1440}) for (int mode = 0; mode < 3; mode++) {
            int step = width / 360;
            int[] original = null;
            for (int dx = 0; dx < step; dx++) for (int dy = 0; dy < step; dy++) {
                int x0 = width / 4 + dx, y0 = 300 + dy;
                int background = mode == 2 ? 0xff191919 : 0xffededed;
                int fill = mode == 0 ? 0xffffffff : mode == 1 ? 0xff95ec69 : 0xff303030;
                ChatLayout.Pixels scene = (x,y) -> {
                    if (x < x0 || x >= x0 + width / 2 || y < y0 || y >= y0 + 180) return background;
                    if (x-x0 > 25 && x-x0 < 150 && y-y0 > 30 && y-y0 < 70 && (x-x0)%20 < 5)
                        return 0xff111111;
                    return fill;
                };
                List<ChatLayout.Box> boxes = (List<ChatLayout.Box>)current.invoke(null,width,132,1900,2.75f,scene,mode==2);
                GridChatLayout.Pixels oldScene = scene::colorAt;
                List<GridChatLayout.Box> old = (List<GridChatLayout.Box>)baseline.invoke(null,width,132,1900,2.75f,oldScene,mode==2);
                require(boxes.size()==1 && old.size()==1,"single");
                ChatLayout.Box box=boxes.get(0); GridChatLayout.Box before=old.get(0);
                require(box.left<=before.left && box.top<=before.top && box.right>=before.right && box.bottom>=before.bottom,"contains-old");
                require(box.left>=Math.round(55*2.75f) && box.right<=width-Math.round(55*2.75f)
                        && box.top>=132 && box.bottom<=1900,"bounded");
                require(box.outgoing==before.outgoing,"role-preserved");
                int[] pixels=crop(box,scene);
                if(original==null)original=pixels;
                require(Arrays.equals(original,pixels),"translation-stable"); stableCases++;
            }
        }
        ChatLayout.Box coarse=new ChatLayout.Box(20,20,40,40,false);
        ChatLayout.Pixels plain=(x,y)->x>=20&&x<40&&y>=20&&y<40?0xffffffff:0xffededed;
        ChatLayout.Box enlarged=StableBubbleBounds.refine(coarse,3,0,0,80,80,false,plain);
        require(enlarged.left==17&&enlarged.right==43&&enlarged.top==17&&enlarged.bottom==43,"fixed-padding");
        for(int side=0;side<4;side++) {
            int left=side==0?19:0,top=side==1?19:0,right=side==2?41:80,bottom=side==3?41:80;
            require(StableBubbleBounds.refine(coarse,3,left,top,right,bottom,false,plain)==coarse,"limit-fallback");
        }
        ChatLayout.Pixels touching=(x,y)->y==14&&x==25?0xff111111:plain.colorAt(x,y);
        require(StableBubbleBounds.refine(coarse,3,0,0,80,80,false,touching)==coarse,"outer-ring-ink");
        ChatLayout.Pixels dot=(x,y)->y==18&&x==18?0xffffffff:plain.colorAt(x,y);
        require(StableBubbleBounds.refine(coarse,3,0,0,80,80,false,dot)==coarse,"disconnected-dot");
        ChatLayout.Pixels hole=(x,y)->x==30&&y==30?0xffededed:plain.colorAt(x,y);
        require(StableBubbleBounds.refine(coarse,3,0,0,80,80,false,hole).stabilized,"enclosed-hole-accepted");
        ChatLayout.Pixels inset=(x,y)->x>=15&&x<40&&y>=19&&y<39?0xffffffff:0xffededed;
        ChatLayout.Box extended=StableBubbleBounds.refine(coarse,3,0,0,80,80,false,inset);
        require(extended.stabilized&&extended.left==12,"padding-outside-search-positive");
        ChatLayout.Pixels outsideSearch=(x,y)->x==12&&y==25?0xff111111:inset.colorAt(x,y);
        require(StableBubbleBounds.refine(coarse,3,0,0,80,80,false,outsideSearch)==coarse,"final-padding-checked");
        for(int gap=1;gap<=6;gap++) {
            int secondTop=40+gap;
            ChatLayout.Box other=new ChatLayout.Box(20,secondTop,40,secondTop+20,false);
            ChatLayout.Pixels neighbours=(x,y)->x>=20&&x<40&&(y>=20&&y<40||y>=secondTop&&y<secondTop+20)?0xffffffff:0xffededed;
            List<ChatLayout.Box> result=StableBubbleBounds.refineAll(List.of(coarse,other),3,0,0,80,100,false,neighbours);
            require(result.get(0).bottom<=result.get(1).top,"no-new-overlap");
        }
        ChatLayout.Box edge=new ChatLayout.Box(100,300,200,400,false);
        ChatLayout.Pixels edgeScene=(x,y)->x>=100&&x<200&&y>=300&&y<400?0xffffffff:0xffededed;
        ChatLayout.Box refinedEdge=StableBubbleBounds.refine(edge,3,0,132,1080,1800,false,edgeScene);
        ChatViewport viewport=ChatViewport.create(new ScreenGeometry(1080,2000,160,0,0,0,0,0,true),
                new ChatViewport.Bounds(0,0,1080,2000),-1,0,1080,List.of(new ChatViewport.Bounds(210,300,220,400)));
        require(viewport!=null&&!viewport.occludes(edge),"old-edge-not-masked");
        require(refinedEdge.stabilized&&viewport.occludes(refinedEdge),"expanded-edge-masked");
        ChatLayout.Pixels islandInHole=(x,y)->x>=25&&x<35&&y>=25&&y<35
                ?(x==30&&y==30?0xff111111:0xffededed):plain.colorAt(x,y);
        require(StableBubbleBounds.refine(coarse,3,0,0,80,80,false,islandInHole).stabilized,"enclosed-ink-island");
        ChatLayout.Pixels openPocket=(x,y)->x>=25&&x<35&&y>=20&&y<35
                ?(x==30&&y==30?0xffffffff:0xffededed):plain.colorAt(x,y);
        require(StableBubbleBounds.refine(coarse,3,0,0,80,80,false,openPocket)==coarse,"open-pocket-independent-object");
        ChatLayout.Pixels notch=(x,y)->x>=25&&x<35&&y>=20&&y<35?0xffededed:plain.colorAt(x,y);
        require(StableBubbleBounds.refine(coarse,3,0,0,80,80,false,notch).stabilized,"connected-open-notch");
        ChatLayout.Pixels diagonal=(x,y)->x==19&&y==19?0xffffffff:plain.colorAt(x,y);
        require(StableBubbleBounds.refine(coarse,3,0,0,80,80,false,diagonal)==coarse,"diagonal-only-disconnected");
        ChatLayout.Pixels diagonalExit=(x,y)->x==y&&x>=20&&x<25?0xffededed:islandInHole.colorAt(x,y);
        require(StableBubbleBounds.refine(coarse,3,0,0,80,80,false,diagonalExit)==coarse,"diagonal-background-exit");
        ChatLayout.Pixels bridge=(x,y)->x==30&&y!=30?0xffededed:plain.colorAt(x,y);
        require(StableBubbleBounds.refine(coarse,3,0,0,80,80,false,bridge).stabilized,"one-pixel-bridge");
        ChatLayout.Pixels separated=(x,y)->x==30?0xffededed:plain.colorAt(x,y);
        require(StableBubbleBounds.refine(coarse,3,0,0,80,80,false,separated)==coarse,"split-components");
        ChatLayout.Box marginBox=new ChatLayout.Box(80,20,100,40,false);
        ChatLayout.Pixels marginScene=(x,y)->x>=80&&x<100&&y>=20&&y<40?0xffffffff:0xffededed;
        require(StableBubbleBounds.refine(marginBox,3,0,0,100,80,false,marginScene)==marginBox,"old-detection-margin");
        require(StableBubbleBounds.refine(marginBox,3,0,0,120,80,false,marginScene).right==103,"actual-viewport-limit");
        ChatLayout.Pixels avatar=(x,y)->x>=104&&x<108&&y>=20&&y<40?0xffffffff:marginScene.colorAt(x,y);
        require(StableBubbleBounds.refine(marginBox,3,0,0,120,80,false,avatar)==marginBox,"nearby-avatar-refused");
        int[] reads={0};ChatLayout.Box large=new ChatLayout.Box(20,20,1000,1000,false);
        require(StableBubbleBounds.refine(large,3,0,0,1200,1200,false,(x,y)->{reads[0]++;return 0xffffffff;})==large
                &&reads[0]==0,"budget-before-read");
        ChatLayout.Box limit=new ChatLayout.Box(20,20,520,520,false);
        require(StableBubbleBounds.refine(limit,3,0,0,600,600,false,
                (x,y)->x>=20&&x<520&&y>=20&&y<520?0xffffffff:0xffededed).stabilized,"single-budget-inclusive");
        reads[0]=0;ChatLayout.Box over=new ChatLayout.Box(20,20,521,520,false);
        require(StableBubbleBounds.refine(over,3,0,0,600,600,false,(x,y)->{reads[0]++;return 0xffffffff;})==over
                &&reads[0]==0,"single-budget-exclusive");
        java.util.ArrayList<ChatLayout.Box> many=new java.util.ArrayList<>();
        for(int i=0;i<7;i++)many.add(new ChatLayout.Box(20,20+i*440,420,420+i*440,false));
        List<ChatLayout.Box> bounded=StableBubbleBounds.refineAll(many,3,0,0,1000,4000,false,(x,y)->{
            for(ChatLayout.Box b:many)if(b.contains(x,y))return 0xffffffff;return 0xffededed;
        });
        require(bounded.stream().filter(b->b.stabilized).count()==6&&bounded.get(6)==many.get(6),"total-frame-budget");
        Class<?> scratchType=Class.forName("com.codextemp.wechatcaptureprobe.StableBubbleBounds$Scratch");
        java.lang.reflect.Constructor<?> constructor=scratchType.getDeclaredConstructor();constructor.setAccessible(true);
        Object scratch=constructor.newInstance();
        Method internal=StableBubbleBounds.class.getDeclaredMethod("refine",ChatLayout.Box.class,int.class,int.class,int.class,
                int.class,int.class,boolean.class,ChatLayout.Pixels.class,scratchType);internal.setAccessible(true);
        internal.invoke(null,coarse,3,0,0,80,80,false,plain,scratch);
        java.lang.reflect.Field maskField=scratchType.getDeclaredField("mask"),queueField=scratchType.getDeclaredField("queue");
        maskField.setAccessible(true);queueField.setAccessible(true);
        boolean empty=true;for(byte value:(byte[])maskField.get(scratch))empty&=value==0;
        require(empty&&Arrays.stream((int[])queueField.get(scratch)).allMatch(value->value==0),"scratch-cleared-success");
        boolean threw=false;
        try{internal.invoke(null,coarse,3,0,0,80,80,false,(ChatLayout.Pixels)(x,y)->{
            if(x==35&&y==35)throw new IllegalArgumentException("synthetic-read-failure");return plain.colorAt(x,y);
        },scratch);}catch(java.lang.reflect.InvocationTargetException expected){threw=true;}
        empty=true;for(byte value:(byte[])maskField.get(scratch))empty&=value==0;
        require(threw&&empty&&Arrays.stream((int[])queueField.get(scratch)).allMatch(value->value==0),"scratch-cleared-exception");
        System.out.println("PASS stableBoundsChecks="+checks+" translationCases="+stableCases+" syntheticOnly=true");
    }
}
