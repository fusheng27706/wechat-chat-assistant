package com.codextemp.wechatcaptureprobe;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** 主线程只读数字快照；不触发节点查询、不取图、不保留标题或图像指纹。 */
final class RecoveryJournal implements AutoCloseable {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> records = new ArrayDeque<>();
    private final long started = SystemClock.elapsedRealtime();
    private long step, previousAt, discarded, maximumGap, maximumPollMillis;
    private boolean closed;
    private Object previousReader;
    private long readerChanges;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (closed) return;
            long now = SystemClock.elapsedRealtime();
            if (previousAt != 0) maximumGap = Math.max(maximumGap, now - previousAt);
            previousAt = now;
            try { record(now); }
            catch (ReflectiveOperationException failure) { append("t=" + (now - started) + " observerError=1"); }
            maximumPollMillis = Math.max(maximumPollMillis, SystemClock.elapsedRealtime() - now);
            handler.postDelayed(this, 100);
        }
    };
    RecoveryJournal() { poll.run(); }
    void stage(long value) { step = value; }
    private Object read(Class<?> type, Object object, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private Object read(Object object, String name) throws ReflectiveOperationException {
        return read(object.getClass(), object, name);
    }
    private void record(long now) throws ReflectiveOperationException {
        Object service = read(AssistantCaptureService.class, null, "instance");
        if (service == null) { append("t=" + (now - started) + " step=" + step + " service=0"); return; }
        ChatGateService.Snapshot gate = ChatGateService.snapshot();
        // 只读已发布遮挡，不能为观测刷新布局或触发范围查询；剩余时间不是就绪证明。
        OverlayOcclusion overlay = ChatGateService.publishedCaptureOcclusion();
        long geometryAt = (Long) read(service, "geometryEnteredAt");
        long nextTickAt = (Long) read(service, "nextTickAt");
        Object reader = read(service, "reader");
        if (reader != previousReader) { previousReader = reader; readerChanges++; }
        ProjectionSession owner = (ProjectionSession) read(service, "projectionSession");
        Object session = read(service, "session");
        long candidate = (Long) read(service, "candidateHash"), processed = (Long) read(service, "processedHash");
        StringBuilder line = new StringBuilder("t=" + (now - started) + " step=" + step
                + " generation=" + gate.generation + " wechat=" + gate.wechat + " valid=" + gate.valid()
                + " gateAge=" + (now - gate.enteredAt) + " checkedAge=" + (now - gate.checkedAt)
                + " keyboardVisible=" + (gate.keyboardTop >= 0)
                + " overlayKnown=" + (overlay != null && overlay.known)
                + " overlayAge=" + (overlay == null ? -1 : now - overlay.changedAt)
                + " sourceWait=" + Math.max(0, gate.enteredAt + 250 - now)
                + " geometryWait=" + Math.max(0, geometryAt + 350 - now)
                + " overlayWait=" + (overlay == null || !overlay.known ? -1 : Math.max(0, overlay.changedAt + 350 - now))
                + " nextTickIn=" + (nextTickAt < 0 ? -1 : nextTickAt - now)
                + " reader=" + (reader != null) + " readerChanges=" + readerChanges
                + " ownerReady=" + (owner != null && owner.ready()) + " latest=" + (read(service, "latest") != null)
                + " arrivalAge=" + (now - (Long) read(service, "arrivedAt"))
                + " geometryAge=" + (now - (Long) read(service, "geometryEnteredAt"))
                + " readerRequestAge=" + (now - (Long) read(service, "readerRequestedAt"))
                + " pendingResume=" + read(service, "pendingResumeGeneration")
                + " firstPreparedGeneration=" + read(read(service, "firstFrame"), "consumedGeneration")
                + " changeAge=" + (now - (Long) read(service, "changedAt"))
                + " ocrAge=" + (now - (Long) read(service, "lastOcrAt"))
                + " hashInitialized=" + (candidate != Long.MIN_VALUE) + " hashSame=" + (candidate == processed)
                + " paused=" + AssistantState.paused + " expanded=" + AssistantState.expanded
                + " published=" + AssistantState.frames + " messages=" + AssistantState.messages.size());
        for (String name : new String[]{"busy", "frameCount", "frameHandled", "layoutUncertain", "regionAligned", "regionVisible"})
            line.append(' ').append(name).append('=').append(read(service, name));
        for (String name : new String[]{"revision", "observed", "confirmations"})
            line.append(' ').append(name).append('=').append(read(session, name));
        line.append(" initialHeaderPrepared=").append(read(session, "initialHeaderPrepared"));
        Object ocr = read(service, "bubbleOcr");
        if (ocr != null) {
            Object cache = read(ocr, "cache");
            java.util.Map<?, ?> entries = (java.util.Map<?, ?>) read(cache, "entries");
            int isolated = 0;
            long earliest = Long.MAX_VALUE;
            // 形状摘要仅输出维度/方向/密度分布，禁止输出键尾指纹、像素或OCR结果。
            for (Object entry : entries.values()) {
                long expires = (Long) read(entry, "expires");
                if (expires != Long.MAX_VALUE) { isolated++; earliest = Math.min(earliest, expires); }
            }
            line.append(" cacheEntries=").append(entries.size())
                    .append(" cachePixels=").append(read(cache, "pixelCount"))
                    .append(" cacheStoredInts=").append(read(cache, "storedInts"))
                    .append(" cacheEpoch=").append(read(ocr, "cacheEpoch"))
                    .append(" cacheIsolated=").append(isolated)
                    .append(" cacheExpiryIn=").append(earliest == Long.MAX_VALUE ? -1 : earliest - now)
                    .append(" cacheShapes=").append(CacheShapeSummary.summarize(entries.keySet()));
        }
        append(line.toString());
    }
    private void append(String line) {
        if (records.size() == 256) { records.removeFirst(); discarded++; }
        records.addLast(line);
    }
    List<String> freeze() {
        closed = true; handler.removeCallbacks(poll);
        List<String> result = new ArrayList<>();
        result.add("samples=" + records.size() + " discarded=" + discarded + " maxGapMs=" + maximumGap
                + " pollMaxMs=" + maximumPollMillis);
        result.addAll(records); return result;
    }
    @Override public void close() { closed = true; handler.removeCallbacks(poll); records.clear(); previousReader = null; }
}
