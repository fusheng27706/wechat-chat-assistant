package com.codextemp.wechatcaptureprobe;
import java.lang.reflect.Field;

/** 使用固定时间戳区分事件发送与接收；不伪造系统来源，不包含聊天或剪贴板。 */
public final class LateInteractionRegression {
    private static int checks;
    private static void require(boolean value){checks++;if(!value)throw new AssertionError("late-event-check="+checks);}
    private static boolean visible(SuggestionVisibility state,long now,long frame,String signature,boolean appended){
        return state.visible(ChatInteraction.quiet(now),ChatInteraction.revision(),frame,signature,true,appended,ChatInteraction.latestEventUptime());
    }
    public static void main(String[] args)throws Exception{
        for(String name:new String[]{"revision","quietUntil","latestEventUptime"}){
            Field f=ChatInteraction.class.getDeclaredField(name);f.setAccessible(true);f.setLong(null,name.equals("latestEventUptime")?-1:0);
        }
        SuggestionVisibility state=new SuggestionVisibility();
        ChatInteraction.observedScroll(50000,1000,900);
        state.dismiss(ChatInteraction.revision(),1,1000);
        ChatInteraction.observedScroll(50100,1100,950);
        require(ChatInteraction.revision()==2);
        require(!visible(state,50100,1,"A",false));
        require(!visible(state,50450,1,"A",false));
        ChatInteraction.observedEvent(50500,1500,1000);
        require(ChatInteraction.quietRemaining(50500)==450);
        require(!visible(state,50950,1,"A",false)); // 同毫秒仍不能证明在收起之后。
        require(!visible(state,50950,2,"new-mask-baseline",true));
        require(visible(state,50950,3,"new-incoming",true));
        state.dismiss(ChatInteraction.revision(),3,2000);
        for(long invalid:new long[]{-1,0,2501,Long.MAX_VALUE}){
            ChatInteraction.observedEvent(52000,2500,invalid);
            require(ChatInteraction.latestEventUptime()==1000);
            require(!visible(state,52450,3,"new-incoming",false));
        }
        ChatInteraction.observedScroll(53000,3000,2900);
        ChatInteraction.observedScroll(53010,3010,1900);
        require(ChatInteraction.latestEventUptime()==2900);
        require(!visible(state,53010,3,"new-incoming",false));
        require(visible(state,53360,3,"new-incoming",false));
        state.dismiss(ChatInteraction.revision(),3,4000);
        ChatInteraction.observedScroll(100000,4100,3999);
        require(!visible(state,100350,3,"new-incoming",false));
        state.open();require(visible(state,100350,3,"new-incoming",false));
        System.out.println("PASS lateInteractionChecks="+checks+" syntheticOnly=true");
    }
}
