package com.codextemp.wechatcaptureprobe;
import java.util.Arrays;
import java.lang.reflect.Field;
/** 标题缓存只验证计算结果匹配；不替代真实来源及会话双帧测试。 */
public final class HeaderCacheRegression {
    private static int checks;
    private static void require(boolean value){checks++;if(!value)throw new AssertionError("header-cache");}
    private static void put(HeaderRecognitionCache c,int[] p){c.put(1,2,3,4,5,2,2,p,"固定标题");}
    private static String get(HeaderRecognitionCache c,int[] p){return c.get(1,2,3,4,5,2,2,p);}
    public static void main(String[] args)throws Exception {
        HeaderRecognitionCache c=new HeaderRecognitionCache(); int[] p={1,2,3,4};
        require(get(c,p)==null);put(c,p);require("固定标题".equals(get(c,p)));
        p[0]=9;require("固定标题".equals(get(c,new int[]{1,2,3,4})));p[0]=1;
        for(int i=0;i<4;i++){put(c,p);int[] x=p.clone();x[i]^=1;require(get(c,x)==null);require(get(c,p)==null);}
        put(c,p);require(get(c,new int[]{1,2,3,5})==null);require(c.differenceStats().equals("1,4,1,1,2,2,1"));require(get(c,p)==null);require(c.differenceStats().equals("none"));
        for(int i=0;i<7;i++) {put(c,p);long[] key={1,2,3,4,5,2,2};key[i]++;require(c.get(key[0],key[1],key[2],(int)key[3],(int)key[4],(int)key[5],(int)key[6],p)==null);}
        put(c,p);Field field=HeaderRecognitionCache.class.getDeclaredField("pixels");field.setAccessible(true);int[] stored=(int[])field.get(c);c.clear();require(Arrays.equals(stored,new int[4]));require(get(c,p)==null);
        c.put(1,2,3,4,5,1,3,p,"固定标题");require(get(c,p)==null);
        c.put(1,2,3,4,5,2,2,p,"");require(get(c,p)==null);
        c.put(1,2,3,4,5,513,512,new int[513*512],"固定标题");require(field.get(c)==null);
        c.put(1,2,3,4,5,512,512,new int[512*512],"固定标题");require(field.get(c)!=null);c.clear();
        ConversationSession session=new ConversationSession();session.observe(8,9);long ticket=session.ticket();
        require(!session.confirmed(ticket,8));require(!session.confirm(ticket,"固定标题"));require(!session.confirmed(ticket,8));require(session.confirm(ticket,"固定标题"));require(session.confirmed(ticket,8));require(!session.confirmed(ticket+1,8));require(!session.confirmed(ticket,9));session.suspend();require(!session.current(ticket,8));require(!session.confirmed(ticket,8));
        System.out.println("PASS headerCacheChecks="+checks+" syntheticOnly=true");
    }
}
