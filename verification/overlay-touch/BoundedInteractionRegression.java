package com.codextemp.wechatcaptureprobe;
import java.lang.reflect.Field;

/** 固定时钟覆盖滚动的有限抵扣、连续事件及文字/窗口保守路径；不是来源许可测试。 */
public final class BoundedInteractionRegression {
    private static int checks;
    private static void require(boolean value){checks++;if(!value)throw new AssertionError("bounded-interaction-check="+checks);}
    private static void reset()throws Exception{for(String name:new String[]{"revision","quietUntil"}){Field f=ChatInteraction.class.getDeclaredField(name);f.setAccessible(true);f.setLong(null,0);}}
    public static void main(String[] args)throws Exception{
        for(long age:new long[]{0,1,50,99,100,101,450,10000}){
            reset();ChatInteraction.observedScroll(50000,20000,20000-age);
            long remaining=450-Math.min(100,age);
            require(ChatInteraction.quietRemaining(50000)==remaining);
            require(!ChatInteraction.quiet(50000+remaining-1));require(ChatInteraction.quiet(50000+remaining));
        }
        for(long event:new long[]{-1,0,20001,Long.MAX_VALUE}){reset();ChatInteraction.observedScroll(50000,20000,event);require(ChatInteraction.quietRemaining(50000)==450);}
        reset();ChatInteraction.observed(1000);ChatInteraction.observedScroll(1050,1050,1);
        require(ChatInteraction.quietRemaining(1050)==400); // 后到旧事件不得缩短原截止1450。
        for(int i=0;i<20;i++){
            long now=1100+i*100;ChatInteraction.observedScroll(now,now,now-100);
            require(!ChatInteraction.quiet(now+99));require(ChatInteraction.quietRemaining(now)>=350);
        }
        require(!ChatInteraction.quiet(3349));require(ChatInteraction.quiet(3350));
        require(ChatInteraction.revision()==22);
        ChatInteraction.observed(3100);require(ChatInteraction.quietRemaining(3100)==450);
        reset();ChatInteraction.observedScroll(100000,2000,1900);require(ChatInteraction.quietRemaining(100000)==350);
        ChatInteraction.observedScroll(100010,2001,1900);require(ChatInteraction.quietRemaining(100010)==350);
        reset();ChatInteraction.observedScroll(Long.MAX_VALUE-100,2000,1900);
        require(!ChatInteraction.quiet(Long.MAX_VALUE-1));require(ChatInteraction.quiet(Long.MAX_VALUE));
        System.out.println("PASS boundedInteractionChecks="+checks+" minimumReceivedWaitMs=350 syntheticOnly=true");
    }
}
