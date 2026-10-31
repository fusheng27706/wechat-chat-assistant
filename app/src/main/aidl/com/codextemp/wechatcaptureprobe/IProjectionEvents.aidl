package com.codextemp.wechatcaptureprobe;

oneway interface IProjectionEvents {
    void stopped(String session, int reason, boolean tokenReleased, boolean surfaceReleased);
    void source(String session, long revision, long sourceVersion, int width, int height, boolean visible);
}
