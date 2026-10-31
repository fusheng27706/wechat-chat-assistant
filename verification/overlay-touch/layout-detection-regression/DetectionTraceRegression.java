package com.codextemp.wechatcaptureprobe;
/** 固定像素只验证诊断不改变布局通过条件或增加读取，不代表微信布局准确性。 */
public final class DetectionTraceRegression {
    static int checks;
    static void require(boolean ok){checks++;if(!ok)throw new AssertionError("check-"+checks);}
    public static void main(String[] args){
        for(String mode:new String[]{"SPACE","BACK","FIELD","INK","CHAT"}){
            int height=mode.equals("SPACE")?100:800;
            int[] reads={0};
            ChatLayout.Pixels pixels=(x,y)->{
                reads[0]++;
                if(!mode.equals("BACK")&&x>=12&&x<38&&y>=12&&y<36)return 0xff000000;
                if(!mode.equals("INK")&&x>=4&&x<44&&y>=757&&y<787)return 0xff000000;
                if(mode.equals("FIELD")&&x>=60&&y>=744)return 0xffeeeeee;
                return 0xffffffff;
            };
            ChatLayout.traceDetection=false;ChatLayout.detectionTrace="disabled";
            ChatLayout ordinary=ChatLayout.detect(360,height,1,0,0,-1,pixels);int count=reads[0];
            require(ChatLayout.detectionTrace.equals("disabled"));reads[0]=0;
            ChatLayout.traceDetection=true;
            ChatLayout traced=ChatLayout.detect(360,height,1,0,0,-1,pixels);
            require((ordinary!=null)==(traced!=null));require((traced!=null)==mode.equals("CHAT"));
            require(reads[0]==count);require(ChatLayout.detectionTrace.startsWith("reason="+mode+","));
        }
        ChatLayout.traceDetection=false;ChatLayout.detectionTrace="disabled";
        System.out.println("PASS detectionTraceChecks="+checks);
    }
}