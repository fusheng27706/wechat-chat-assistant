package com.codextemp.wechatcaptureprobe;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

/** 固定标题边界筛查，含贴边反例及同输入重读；不采集微信，不授予缓存或来源许可。 */
public final class HeaderCropSmoke extends Instrumentation {
    private int checks;
    private void require(boolean value){checks++;if(!value)throw new AssertionError("header-crop-precondition");}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    /** SDK完成前不回收输入；中断只阻止后续样本，不能提前释放真实调用。 */
    private Text recognize(TextRecognizer reader, Bitmap image) throws Exception {
        Task<Text> task = reader.process(InputImage.fromBitmap(image, 0));
        boolean interrupted = false;
        try {
            Text result;
            for (;;) {
                try { result = Tasks.await(task); break; }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted) throw new InterruptedException();
            return result;
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private static int maximumBottom(Text result) {
        int bottom=-1;
        for(Text.TextBlock block:result.getTextBlocks())for(Text.Line line:block.getLines())for(Text.Element element:line.getElements())
            if(element.getBoundingBox()!=null)bottom=Math.max(bottom,element.getBoundingBox().bottom);
        return bottom;
    }
    @Override public void onStart(){
        Bundle output=new Bundle();TextRecognizer reader=null;int cases=0,different=0,repeatDifferent=0,inkLoss=0;
        String[] source={"测试标题","项目讨论群(12)","Demo AB123","较长的固定测试标题"};
        try {
            require(!AssistantCaptureService.running()&&!CaptureService.running()&&!ChatGateService.enabled());
            reader=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            for(int font:new int[]{32,44,56})for(boolean dark:new boolean[]{false,true})for(boolean edge:new boolean[]{false,true})for(int sample=0;sample<source.length;sample++) {
                require(!Thread.currentThread().isInterrupted());
                Bitmap full=Bitmap.createBitmap(756,132,Bitmap.Config.ARGB_8888);Bitmap trimmed=null;
                try {
                    Canvas canvas=new Canvas(full);int bg=dark?0xff202020:0xffededed;canvas.drawColor(bg);
                    Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(dark?Color.WHITE:Color.BLACK);paint.setTextSize(font);
                    require(paint.measureText(source[sample])<716);
                    Paint.FontMetrics metrics=paint.getFontMetrics();
                    android.graphics.Rect inkBounds=new android.graphics.Rect();paint.getTextBounds(source[sample],0,source[sample].length(),inkBounds);
                    float baseline=edge?132-inkBounds.bottom:(132-metrics.ascent-metrics.descent)/2;
                    canvas.drawText(source[sample],20,baseline,paint);
                    int removed=0;
                    for(int y=130;y<132;y++)for(int x=0;x<756;x++)if(full.getPixel(x,y)!=bg)removed++;
                    trimmed=Bitmap.createBitmap(full,0,0,756,130);
                    Text[] texts=new Text[3];
                    if((cases&1)==0){texts[0]=recognize(reader,full);texts[1]=recognize(reader,trimmed);}
                    else {texts[1]=recognize(reader,trimmed);texts[0]=recognize(reader,full);}
                    texts[2]=recognize(reader,full);
                    boolean differs=!texts[0].getText().equals(texts[1].getText());
                    boolean repeats=!texts[0].getText().equals(texts[2].getText());
                    if(differs)different++;if(repeats)repeatDifferent++;if(removed>0)inkLoss++;
                    Bundle line=new Bundle();line.putString("headerCrop","case="+cases+" fontPx="+font+" dark="+dark+" edge="+edge+" removedInk="+removed
                        +" textDiffers="+differs+" repeatDiffers="+repeats+" normalizedEqual="+ConversationSession.normalize(texts[0].getText()).equals(ConversationSession.normalize(texts[1].getText()))
                        +" oldSourceEqual="+ConversationSession.normalize(texts[0].getText()).equals(ConversationSession.normalize(source[sample]))
                        +" newSourceEqual="+ConversationSession.normalize(texts[1].getText()).equals(ConversationSession.normalize(source[sample]))
                        +" oldBottom="+maximumBottom(texts[0])+" newBottom="+maximumBottom(texts[1]));sendStatus(2,line);cases++;
                }finally{if(trimmed!=null)trimmed.recycle();full.recycle();}
            }
            output.putString("result","COMPLETED headerCropChecks="+checks+" cases="+cases+" textDifferences="+different+" repeatDifferences="+repeatDifferent+" casesWithRemovedInk="+inkLoss+" realImages=0 productionEligible=false");
        }catch(Throwable failure){output.putString("result","FAIL checks="+checks+" type="+failure.getClass().getSimpleName());}
        finally{if(reader!=null)reader.close();}
        finish(output.getString("result").startsWith("COMPLETED")?-1:0,output);
    }
}