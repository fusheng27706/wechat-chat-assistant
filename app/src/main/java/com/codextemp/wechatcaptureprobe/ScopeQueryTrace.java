package com.codextemp.wechatcaptureprobe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

final class ScopeQueryTrace {
    static final int ACCEPTED = 1;
    static final int DISCONNECTED = 2;
    static final int INVALID_METADATA = 3;
    static final int LEASE_REJECTED = 4;
    static final int RENDER_QUEUED = 1;
    static final int RENDER_SHOWN = 2;
    static final int RENDER_SUPERSEDED = 3;
    static final int RENDER_CANCELLED = 4;
    static final int RENDER_CLEARED = 5;

    static final class Entry {
        final long identifier;
        final long epoch;
        final long startedAt;
        volatile long workerStartedAt = -1;
        // 仅诊断的单调时间；未执行或异常中断保留-1，不参与有效性判断。
        volatile long rootStartedAt = -1;
        volatile long rootFinishedAt = -1;
        volatile long windowsFinishedAt = -1;
        volatile long occlusionsFinishedAt = -1;
        volatile long geometryStartedAt = -1;
        volatile long geometryFinishedAt = -1;
        volatile long acceptGeometryFinishedAt = -1;
        volatile long workerFinishedAt = -1;
        volatile long acceptStartedAt = -1;
        volatile long validatedAt = -1;
        volatile long sceneStartedAt = -1;
        volatile long sceneFinishedAt = -1;
        volatile long renderQueuedAt = -1;
        volatile long renderStartedAt = -1;
        volatile long renderFinishedAt = -1;
        volatile long finishedAt = -1;
        volatile int outcome;
        volatile int renderDisposition;

        Entry(ScopeLease.Request request) {
            identifier = request.identifier;
            epoch = request.epoch;
            startedAt = request.startedAt;
        }
    }

    private final int limit;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private final List<Entry> firstExpiryEntries = new ArrayList<>();
    private long firstExpiryAt = -1;
    private boolean enabled;

    ScopeQueryTrace(int limit) {
        if (limit < 1) throw new IllegalArgumentException("Invalid query trace limit");
        this.limit = limit;
    }

    synchronized void start() {
        entries.clear();
        firstExpiryEntries.clear();
        firstExpiryAt = -1;
        enabled = true;
    }

    synchronized Entry begin(ScopeLease.Request request) {
        if (!enabled) return null;
        Entry entry = new Entry(request);
        if (entries.size() == limit) entries.removeFirst();
        entries.addLast(entry);
        return entry;
    }

    synchronized List<Entry> snapshot() { return new ArrayList<>(entries); }

    /** 仅诊断模式保留首次超期前的有界请求；阶段字段仍可由在途请求补齐，不是原子时点快照。 */
    synchronized void expired(long now) {
        if (!enabled || firstExpiryAt >= 0) return;
        firstExpiryAt = now;
        firstExpiryEntries.addAll(entries);
    }

    synchronized long firstExpiryAt() { return firstExpiryAt; }

    synchronized List<Entry> firstExpirySnapshot() { return new ArrayList<>(firstExpiryEntries); }

    synchronized void reset() {
        enabled = false;
        entries.clear();
        firstExpiryEntries.clear();
        firstExpiryAt = -1;
    }
}
