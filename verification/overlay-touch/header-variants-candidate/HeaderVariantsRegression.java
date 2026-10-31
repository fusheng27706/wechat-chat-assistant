package com.codextemp.wechatcaptureprobe;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Arrays;
public final class HeaderVariantsRegression {
    private static int checks;
    private static void require(boolean value){checks++;if(!value)throw new AssertionError("header-variants");}
    private static void put(HeaderRecognitionCache c,int[] p){c.put(1,2,3,4,5,p.length,1,p,"固定标题");}
    private static String get(HeaderRecognitionCache c,int[] p){return c.get(1,2,3,4,5,p.length,1,p);}
    public static void main(String[] args)throws Exception{
        HeaderRecognitionCache c=new HeaderRecognitionCache();int[] a={1,2},b={3,4};
        require(get(c,a)==null);put(c,a);put(c,b);require(get(c,a)!=null);require(get(c,b)!=null);
        require(get(c,new int[]{5,6})==null);require(get(c,a)!=null);a[0]=9;require(get(c,new int[]{1,2})!=null);a[0]=1;
        Field entries=HeaderRecognitionCache.class.getDeclaredField("entries");entries.setAccessible(true);
        Object entry=((List<?>)entries.get(c)).get(0);Field pixels=entry.getClass().getDeclaredField("pixels");pixels.setAccessible(true);int[] stored=(int[])pixels.get(entry);
        c.clear();require(Arrays.equals(stored,new int[stored.length]));
        for(int n=0;n<4;n++)put(c,new int[]{n,0});require(get(c,new int[]{0,0})!=null);
        put(c,new int[]{4,0});require(get(c,new int[]{1,0})==null);require(get(c,new int[]{0,0})!=null);require(((List<?>)entries.get(c)).size()==4);
        put(c,new int[]{0,0});require(((List<?>)entries.get(c)).size()==4);c.clear();
        for(int i=0;i<7;i++){put(c,a);put(c,b);long[] key={1,2,3,4,5,2,1};key[i]++;require(c.get(key[0],key[1],key[2],(int)key[3],(int)key[4],(int)key[5],(int)key[6],a)==null);require(((List<?>)entries.get(c)).isEmpty());}
        int[] large=new int[262144];put(c,large);large[0]=1;put(c,large);large[0]=2;put(c,large);
        require(((List<?>)entries.get(c)).size()==2);large[0]=0;require(get(c,large)==null);large[0]=1;require(get(c,large)!=null);
        put(c,new int[262145]);require(((List<?>)entries.get(c)).isEmpty());
        ConversationSession session=new ConversationSession();session.observe(8,9);long ticket=session.ticket();require(!session.confirmed(ticket,8));require(!session.confirm(ticket,"固定标题"));require(!session.confirmed(ticket,8));require(session.confirm(ticket,"固定标题"));require(session.confirmed(ticket,8));session.suspend();require(!session.confirmed(ticket,8));
        System.out.println("PASS headerVariantChecks="+checks+" syntheticOnly=true");
    }
}
