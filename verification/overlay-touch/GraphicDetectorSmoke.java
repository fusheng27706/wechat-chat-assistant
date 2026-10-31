package com.codextemp.wechatcaptureprobe;

import android.app.Instrumentation;
import android.os.Bundle;

/** 独立固定像素检测对照；运行在Instrumentation线程，不启动采集或调用OCR SDK。 */
public final class GraphicDetectorSmoke extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            if (AssistantCaptureService.running() || CaptureService.running() || ChatGateService.enabled())
                throw new AssertionError("no-existing-capture");
            output.putString("result", GraphicDetectorRegression.run()+" batchSize=30 batches=21 realOcr=false");
        } catch(Throwable failure) {
            output.putString("result", "FAIL graphicProbe type="+failure.getClass().getSimpleName());
        }
        finish(output.getString("result").startsWith("PASS") ? -1 : 0, output);
    }
}
