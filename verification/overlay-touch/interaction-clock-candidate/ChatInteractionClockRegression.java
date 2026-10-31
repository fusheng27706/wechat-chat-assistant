package com.codextemp.wechatcaptureprobe;
import java.lang.reflect.Field;
/** 固定时间验证显示等待；不模拟或放宽来源许可。 */
public final class ChatInteractionClockRegression {
    private static int checks;
    private static void require(boolean value){checks++;if(!value)throw new AssertionError("interaction-clock");}
    private static void reset()throws Exception{for(String name:new String[]{"revision","quietUntil"}){Field f=ChatInteraction.class.getDeclaredField(name);f.setAccessible(true);f.setLong(null,0);}}
    public static void main(String[] args)throws Exception{
        for(long age:new long[]{0,1,100,449,450,451,10000}){
            reset();ChatInteraction.observed(50000,20000,20000-age);
            long remaining=Math.max(0,450-age);require(ChatInteraction.quietRemaining(50000)==remaining);
            require(ChatInteraction.quiet(50000+remaining));if(remaining>0)require(!ChatInteraction.quiet(50000+remaining-1));
            require(ChatInteraction.revision()==1);
        }
        for(long event:new long[]{-1,0,20001,Long.MAX_VALUE}){reset();ChatInteraction.observed(50000,20000,event);require(ChatInteraction.quietRemaining(50000)==450);}
        reset();ChatInteraction.observed(1000,1000,1000);ChatInteraction.observed(1100,1100,600);require(ChatInteraction.quietRemaining(1100)==350);require(ChatInteraction.revision()==2);
        ChatInteraction.observed(1200,1200,1200);require(ChatInteraction.quietRemaining(1200)==450);
        reset();ChatInteraction.observed(100000,2000,1900);require(ChatInteraction.quietRemaining(100000)==350);
        reset();ChatInteraction.observed(1000);require(ChatInteraction.quietRemaining(1000)==450);
        reset();ChatInteraction.observed(Long.MAX_VALUE-100);require(!ChatInteraction.quiet(Long.MAX_VALUE-1));require(ChatInteraction.quiet(Long.MAX_VALUE));
        System.out.println("PASS interactionClockChecks="+checks+" syntheticOnly=true");
    }
}
