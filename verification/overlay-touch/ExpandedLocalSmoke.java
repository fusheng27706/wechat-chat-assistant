package com.codextemp.wechatcaptureprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;

/** 原生自有View生命周期与合成像素微基准，不启动投射、不读取微信。 */
public final class ExpandedLocalSmoke extends Instrumentation {
    private Activity activity;
    private AssistOverlay overlay;
    private FrameLayout root;
    private View panel;
    private int checks;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private void require(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private Field field(String name) throws Exception { Field f=AssistOverlay.class.getDeclaredField(name);f.setAccessible(true);return f; }
    @Override public void onStart() {
        Bundle output=new Bundle();
        try {
            require(!AssistantCaptureService.running() && ProjectionSession.available(), "no-existing-capture");
            activity=startActivitySync(new Intent(getTargetContext(),AssistantActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            runOnMainSync(() -> {
                overlay=new AssistOverlay(activity,new DraftOperations());
                root=new FrameLayout(activity);activity.setContentView(root);
                try {panel=(View)field("panel").get(overlay);}catch(Exception e){throw new RuntimeException(e);}
            });
            runOnMainSync(() -> {
                try {
                    var render = AssistOverlay.class.getDeclaredMethod("renderReplies", AnalysisResult.class);
                    render.setAccessible(true);
                    AnalysisResult demo = AnalysisResult.localDemo();
                    render.invoke(overlay, demo);
                    var copies = (android.widget.LinearLayout) field("copyChoices").get(overlay);
                    var advanced = (android.widget.LinearLayout) field("management").get(overlay);
                    require(copies.getChildCount() == demo.replies.size() + 1, "all-direct-copy-choices");
                    require(((View) field("demoToggle").get(overlay)).getParent() == advanced
                            && ((View) field("replyChoices").get(overlay)).getParent() == advanced
                            && advanced.getVisibility() == View.GONE, "advanced-actions-folded");
                    var sourceToggle = (android.widget.Button) field("sourceToggle").get(overlay);
                    var source = (android.widget.TextView) field("sourceDetails").get(overlay);
                    var content = (android.view.ViewGroup) sourceToggle.getParent();
                    require(content != advanced && source.getParent() == content,
                            "source-access-outside-advanced");
                    require(content.indexOfChild(sourceToggle) < content.indexOfChild((View) field("managementToggle").get(overlay)),
                            "source-before-advanced");
                    require(source.getVisibility() == View.GONE, "source-default-folded");
                    sourceToggle.performClick();
                    require(source.getVisibility() == View.VISIBLE && "收起识别原文".contentEquals(sourceToggle.getText()), "source-opens-directly");
                    sourceToggle.performClick();
                    require(source.getVisibility() == View.GONE && "查看识别原文 · 请核对".contentEquals(sourceToggle.getText()), "source-closes-directly");
                    sourceToggle.performClick();
                    var reset = AssistOverlay.class.getDeclaredMethod("resetFolds");
                    reset.setAccessible(true);
                    reset.invoke(overlay);
                    require(source.getVisibility() == View.GONE && "查看识别原文 · 请核对".contentEquals(sourceToggle.getText()), "automatic-fold-restores-label");
                    source.setText("T01");
                    sourceToggle.performClick();
                    var copy = (android.widget.Button) copies.getChildAt(1);
                    require(copy.getText().toString().contains(demo.replies.get(0).text)
                            && copy.getText().toString().contains("模拟"), "visible-full-marked-reply");
                    // 未发布有效来源，只验证旧按钮拒绝路径，不接触真实剪贴板。
                    copy.performClick();
                    require(field("selectedReply").get(overlay) == null, "invalid-result-not-selected");
                    overlay.close();
                    require(copies.getChildCount() == 0, "close-removes-reply-text-and-listeners");
                    require(source.getText().length() == 0 && source.getVisibility() == View.GONE
                            && "查看识别原文 · 请核对".contentEquals(sourceToggle.getText()), "close-clears-and-folds-source");
                    render.invoke(overlay, new Object[]{null});
                    require(copies.getChildCount() == 0, "empty-result-does-not-restore-old-cards");
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            for(int round=0;round<3;round++) {
                runOnMainSync(() -> {
                    try {field("layoutPending").setBoolean(overlay,true);}catch(Exception e){throw new RuntimeException(e);}
                    root.addView(panel,new FrameLayout.LayoutParams(800,1000));
                });
                long until=SystemClock.elapsedRealtime()+3000;
                boolean[] pending={true};
                do {
                    runOnMainSync(() -> {try{pending[0]=field("layoutPending").getBoolean(overlay);}catch(Exception e){throw new RuntimeException(e);}});
                    if(pending[0])SystemClock.sleep(30);
                }while(pending[0]&&SystemClock.elapsedRealtime()<until);
                require(!pending[0],"preDraw-clears-pending-after-attach-"+round);
                runOnMainSync(() -> root.removeView(panel));
                require(field("drawObserver").get(overlay)==null,"detach-removes-observer-"+round);
            }
            Bitmap bitmap=Bitmap.createBitmap(1080,2000,Bitmap.Config.ARGB_8888);
            int[] pixels=new int[1080*2000];Arrays.fill(pixels,0xffededed);
            for(int y=200;y<1700;y++)for(int x=200;x<850;x++)if((y/120)%2==0)pixels[y*1080+x]=0xff95ec69;
            bitmap.setPixels(pixels,0,1080,0,0,1080,2000);
            var constructor=ChatLayout.class.getDeclaredConstructor(int.class,int.class,int.class,java.util.List.class,boolean.class);
            constructor.setAccessible(true);
            ChatLayout layout=constructor.newInstance(0,132,1846,new ArrayList<ChatLayout.Box>(),false);
            long[] oldNanos=new long[15],newNanos=new long[15];
            for(int i=-3;i<15;i++){
                long start=SystemClock.elapsedRealtimeNanos();
                ChatLayout old=layout.withBubbles(1080,2.75f,bitmap::getPixel);
                long split=SystemClock.elapsedRealtimeNanos();
                int[] bulk=new int[1080*2000];bitmap.getPixels(bulk,0,1080,0,0,1080,2000);
                ChatLayout next=layout.withBubbles(1080,2.75f,(x,y)->bulk[y*1080+x]);
                long end=SystemClock.elapsedRealtimeNanos();
                require(old.bubbles.size()==next.bubbles.size()&&!old.bubbles.isEmpty(),"same-synthetic-bubbles");
                for(int j=0;j<old.bubbles.size();j++){
                    ChatLayout.Box a=old.bubbles.get(j),b=next.bubbles.get(j);
                    require(a.left==b.left&&a.top==b.top&&a.right==b.right&&a.bottom==b.bottom&&a.outgoing==b.outgoing,"same-synthetic-geometry");
                }
                if(i>=0){oldNanos[i]=split-start;newNanos[i]=end-split;}
            }
            bitmap.recycle();Arrays.sort(oldNanos);Arrays.sort(newNanos);
            output.putString("result","PASS expandedLocalChecks="+checks+" attachCycles=3 oldMedianUs="+oldNanos[7]/1000+" bulkMedianUs="+newNanos[7]/1000+" syntheticPixels=true realOcr=false");
        }catch(Throwable failure){output.putString("result","FAIL expandedLocalChecks="+checks+" type="+failure.getClass().getSimpleName()+" check="+failure.getMessage());}
        finally{runOnMainSync(()->{if(root!=null)root.removeAllViews();if(overlay!=null)overlay.destroy();if(activity!=null)activity.finish();});}
        finish(output.getString("result").startsWith("PASS")?-1:0,output);
    }
}
