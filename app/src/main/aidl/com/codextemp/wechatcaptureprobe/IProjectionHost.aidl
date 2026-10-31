package com.codextemp.wechatcaptureprobe;

import android.content.Intent;
import android.os.Bundle;
import android.view.Surface;
import com.codextemp.wechatcaptureprobe.IProjectionEvents;

interface IProjectionHost {
    Bundle open(String session, long revision, int resultCode, in Intent grant, String name,
        int width, int height, int densityDpi, in Surface surface, IProjectionEvents events);
    Bundle update(String session, long revision, int width, int height, int densityDpi, in Surface surface);
    void requestStop(String session);
    void close(String session);
}
