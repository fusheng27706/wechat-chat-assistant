package com.codextemp.wechatcaptureprobe;

/** 固定像素覆盖各首个回退码；不使用真实图像。 */
public final class BoundaryReasonsRegression {
    private static int checks;
    private static void expect(int expected,ChatLayout.Pixels scene,int left) {
        int actual=BoundaryRefinementReasons.inspect(new ChatLayout.Box(20,20,40,40,false),3,left,0,80,80,false,scene);
        checks++;if(actual!=expected)throw new AssertionError("reason-"+expected+"-got-"+actual);
    }
    public static void main(String[] args) {
        ChatLayout.Pixels plain=(x,y)->x>=20&&x<40&&y>=20&&y<40?0xffffffff:0xffededed;
        expect(0,plain,0);
        expect(1,plain,19);
        expect(2,(x,y)->x==25&&y==14?0xff111111:plain.colorAt(x,y),0);
        expect(3,(x,y)->x==30&&y==30?0xffededed:plain.colorAt(x,y),0);
        expect(4,(x,y)->x==18&&y==18?0xffffffff:plain.colorAt(x,y),0);
        expect(5,(x,y)->0xffededed,0);
        ChatLayout.Pixels inset=(x,y)->x>=15&&x<40&&y>=19&&y<39?0xffffffff:0xffededed;
        expect(6,inset,13);
        expect(7,(x,y)->x==12&&y==25?0xff111111:inset.colorAt(x,y),0);
        int[] detail=new int[6];
        int reason=BoundaryRefinementReasons.inspect(new ChatLayout.Box(20,20,40,40,false),3,19,19,41,41,false,plain,detail);
        checks++;if(reason!=1||!java.util.Arrays.equals(detail,new int[]{1,1,1,1,0,0}))throw new AssertionError("limit-detail");
        java.util.Arrays.fill(detail,0);
        reason=BoundaryRefinementReasons.inspect(new ChatLayout.Box(20,20,40,40,false),3,0,0,80,80,false,
                (x,y)->x==30&&y==30?0xffededed:plain.colorAt(x,y),detail);
        checks++;if(reason!=3||detail[4]!=1||detail[5]!=0)throw new AssertionError("internal-gap-detail");
        System.out.println("PASS boundaryReasonChecks="+checks+" syntheticOnly=true");
    }
}
