package com.codextemp.wechatcaptureprobe;

import java.util.List;

public final class ScopeQueryTraceRegression {
    private static int checks;

    public static void main(String[] arguments) {
        ScopeQueryTrace trace = new ScopeQueryTrace(2);
        ScopeLease lease = new ScopeLease(200);
        ScopeLease.Request first = lease.begin(1000);
        require(trace.begin(first) == null && trace.snapshot().isEmpty(), "tracing-disabled-by-default");
        require(lease.pending() && lease.begin(1001) == null, "disabled-trace-does-not-release-physical-slot");
        trace.start();
        ScopeQueryTrace.Entry entry = trace.begin(first);
        require(entry.identifier == first.identifier && entry.epoch == first.epoch && entry.startedAt == first.startedAt,
                "trace-records-only-request-identity-and-start");
        require(entry.workerStartedAt == -1 && entry.workerFinishedAt == -1 && entry.acceptStartedAt == -1
                && entry.validatedAt == -1 && entry.sceneStartedAt == -1 && entry.sceneFinishedAt == -1
                && entry.renderQueuedAt == -1 && entry.renderStartedAt == -1 && entry.renderFinishedAt == -1
                && entry.finishedAt == -1 && entry.outcome == 0 && entry.renderDisposition == 0,
                "missing-phases-are-not-fabricated-zero-duration");
        require(entry.rootStartedAt == -1 && entry.rootFinishedAt == -1 && entry.windowsFinishedAt == -1
                && entry.occlusionsFinishedAt == -1 && entry.geometryStartedAt == -1
                && entry.geometryFinishedAt == -1 && entry.acceptGeometryFinishedAt == -1,
                "unobserved-sdk-phases-remain-missing");
        entry.workerStartedAt = 1002;
        entry.workerFinishedAt = 1030;
        entry.acceptStartedAt = 1080;
        entry.validatedAt = 1090;
        entry.outcome = ScopeQueryTrace.ACCEPTED;
        List<ScopeQueryTrace.Entry> snapshot = trace.snapshot();
        require(snapshot.size() == 1 && snapshot.get(0) == entry, "snapshot-references-explicitly-live-numeric-entry");
        snapshot.clear();
        require(trace.snapshot().size() == 1, "snapshot-list-mutation-does-not-clear-trace");
        ScopeLease.Certificate certificate = lease.complete(first, 1090, false);
        require(lease.valid(certificate, 1199) && !lease.valid(certificate, 1200), "timings-do-not-extend-certificate");
        ScopeLease.Request second = lease.begin(1080);
        ScopeQueryTrace.Entry secondEntry = trace.begin(second);
        require(trace.snapshot().size() == 2 && trace.snapshot().get(1) == secondEntry, "second-query-is-recorded-in-order");
        lease.complete(second, 1090, false);
        ScopeLease.Request third = lease.begin(1160);
        ScopeQueryTrace.Entry thirdEntry = trace.begin(third);
        require(trace.snapshot().size() == 2 && trace.snapshot().get(0) == secondEntry && trace.snapshot().get(1) == thirdEntry,
                "oldest-record-evicted-at-bound");
        trace.reset();
        require(trace.snapshot().isEmpty() && trace.begin(third) == null, "reset-clears-history-and-disables-recording");
        entry.finishedAt = 1210;
        require(trace.snapshot().isEmpty(), "late-phase-write-does-not-reinsert-detached-entry");
        require(lease.pending(), "trace-reset-does-not-release-real-query");
        trace.start();
        require(trace.snapshot().isEmpty() && trace.begin(third) != null, "new-observation-starts-with-empty-history");
        lease.invalidate();
        require(lease.complete(third, 1180, false) == null, "trace-does-not-bypass-event-revocation");
        ScopeQueryTrace retained = new ScopeQueryTrace(2);
        retained.expired(100);
        require(retained.firstExpiryAt() == -1 && retained.firstExpirySnapshot().isEmpty(), "disabled-expiry-retains-nothing");
        retained.start();
        ScopeQueryTrace.Entry old = retained.begin(first);
        retained.begin(second);
        retained.expired(1200);
        retained.begin(third);
        retained.expired(1400);
        require(retained.firstExpiryAt() == 1200, "later-expiry-does-not-overwrite-first-event");
        require(retained.firstExpirySnapshot().size() == 2 && retained.firstExpirySnapshot().get(0) == old,
                "first-expiry-survives-live-ring-eviction");
        require(retained.snapshot().size() == 2 && !retained.snapshot().contains(old), "live-ring-still-bounded");
        retained.firstExpirySnapshot().clear();
        require(retained.firstExpirySnapshot().size() == 2, "caller-cannot-clear-retained-history");
        old.finishedAt = 1500;
        require(retained.firstExpirySnapshot().get(0).finishedAt == 1500, "retained-phase-fields-explicitly-remain-live");
        retained.reset();
        require(retained.firstExpiryAt() == -1 && retained.firstExpirySnapshot().isEmpty(), "reset-clears-first-expiry");
        retained.start();
        retained.expired(1600);
        retained.begin(first);
        retained.expired(1800);
        require(retained.firstExpiryAt() == 1600 && retained.firstExpirySnapshot().isEmpty(), "empty-first-expiry-not-replaced");
        retained.start();
        require(retained.firstExpiryAt() == -1 && retained.snapshot().isEmpty(), "restart-clears-both-histories");
        System.out.println("PASS scopeQueryTraceChecks=" + checks + " realQueries=0 modelRequests=0 chatFiles=0");
    }

    private static void require(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}
