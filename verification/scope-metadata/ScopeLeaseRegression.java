package com.codextemp.wechatcaptureprobe;

import java.util.Random;

public final class ScopeLeaseRegression {
    private static int checks;

    public static void main(String[] arguments) {
        ScopeLease lease = new ScopeLease(200);
        ScopeLease.Request first = lease.begin(1000);
        require(first != null && lease.begin(1001) == null && lease.pending(), "single-in-flight");
        ScopeLease.Certificate firstCertificate = lease.complete(first, 1020, true);
        require(lease.valid(firstCertificate, 1020) && lease.valid(firstCertificate, 1199), "bounded-certificate-valid");
        require(!lease.valid(firstCertificate, 1200) && !lease.valid(firstCertificate, 999), "deadline-and-backwards-clock-rejected");
        ScopeLease.Request second = lease.begin(1080);
        ScopeLease.Certificate secondCertificate = lease.complete(second, 1090, false);
        require(lease.valid(firstCertificate, 1090) && lease.valid(secondCertificate, 1090), "same-scene-bounded-overlap");
        ScopeLease.Request changed = lease.begin(1120);
        ScopeLease.Certificate changedCertificate = lease.complete(changed, 1130, true);
        require(!lease.valid(firstCertificate, 1130) && !lease.valid(secondCertificate, 1130)
                && lease.valid(changedCertificate, 1130), "scene-change-revokes-older-certificates");
        ScopeLease.Request stale = lease.begin(1200);
        lease.invalidate();
        require(!lease.valid(changedCertificate, 1201) && lease.begin(1202) == null, "event-invalidates-without-opening-second-slot");
        require(lease.complete(stale, 1210, false) == null && !lease.pending(), "event-late-result-rejected");
        ScopeLease.Request slow = lease.begin(1300);
        require(lease.expire(1500) && lease.pending() && lease.begin(1501) == null, "timeout-does-not-free-running-worker-slot");
        require(!lease.expire(1501) && lease.complete(slow, 1600, false) == null, "timeout-never-resurrects-positive-result");
        ScopeLease.Request newer = lease.begin(1601);
        require(lease.complete(slow, 1602, false) == null && lease.pending(), "duplicate-old-completion-does-not-release-newer-slot");
        ScopeLease.Certificate newerCertificate = lease.complete(newer, 1603, false);
        require(lease.valid(newerCertificate, 1603), "fresh-request-can-recover");
        ScopeLease.Request atClose = lease.begin(1610);
        lease.close();
        require(!lease.valid(newerCertificate, 1611) && lease.begin(1612) == null
                && lease.complete(atClose, 1613, false) == null, "close-rejects-pending-and-existing-certificates");
        ScopeLease previousService = new ScopeLease(200);
        ScopeLease.Request oldServiceRequest = previousService.begin(2000);
        ScopeLease.Certificate oldServiceCertificate = previousService.complete(oldServiceRequest, 2010, false);
        previousService.close();
        ScopeLease newService = new ScopeLease(200);
        ScopeLease.Request newServiceRequest = newService.begin(2011);
        ScopeLease.Certificate newServiceCertificate = newService.complete(newServiceRequest, 2012, false);
        require(!previousService.valid(oldServiceCertificate, 2012) && newService.valid(newServiceCertificate, 2012), "service-recreation-independent");
        require(!newService.valid(oldServiceCertificate, 2012), "certificate-cannot-cross-service-owner");
        overlappingRenewal();
        Random random = new Random(756034);
        for (int trial = 0; trial < 12000; trial++) {
            ScopeLease sample = new ScopeLease(200);
            long started = 10000L + trial * 1000L;
            ScopeLease.Request request = sample.begin(started);
            int action = random.nextInt(4);
            int duration = random.nextInt(300);
            if (action == 1) sample.invalidate();
            if (action == 2) sample.close();
            if (action == 3) sample.expire(started + 200);
            ScopeLease.Certificate certificate = sample.complete(request, started + duration, random.nextBoolean());
            boolean expected = action == 0 && duration < 200;
            require((certificate != null) == expected, "completion-requires-current-epoch-and-deadline");
            require(sample.valid(certificate, started + duration) == expected, "accepted-result-matches-lease-validity");
            require(!sample.valid(certificate, started + 200), "no-certificate-survives-source-age-limit");
        }
        System.out.println("PASS scopeLeaseChecks=" + checks + " modelRequests=0 chatFiles=0");
    }

    private static void overlappingRenewal() {
        ScopeLease overlap = new ScopeLease(200);
        ScopeLease.Request old = overlap.begin(1000);
        ScopeLease.Certificate oldCertificate = overlap.complete(old, 1020, false);
        ScopeLease.Request renewal = overlap.begin(1080);
        require(overlap.expire(1200) && overlap.pending(), "expired-accepted-certificate-closes-but-retains-worker-slot");
        require(!overlap.valid(oldCertificate, 1200) && !overlap.expire(1201), "expired-accepted-certificate-stays-revoked");
        ScopeLease.Certificate renewed = overlap.complete(renewal, 1202, false);
        require(renewed != null && overlap.valid(renewed, 1202), "fresh-overlapping-query-not-invalidated-by-predecessor-expiry");
        require(!overlap.valid(oldCertificate, 1202) && !overlap.valid(renewed, 1280), "no-certificate-lifetime-extension");
        ScopeLease backwards = new ScopeLease(200);
        ScopeLease.Certificate beforeClockChange = backwards.complete(backwards.begin(1000), 1020, false);
        ScopeLease.Request duringClockChange = backwards.begin(1080);
        require(backwards.expire(999) && backwards.pending() && !backwards.valid(beforeClockChange, 1090),
                "backwards-clock-invalidates-existing-certificate-without-freeing-slot");
        require(backwards.complete(duringClockChange, 1090, false) == null,
                "backwards-clock-still-invalidates-overlapping-query");
        for (int offset = 20; offset < 200; offset += 20) {
            for (int delay = 0; delay < 200; delay += 5) {
                for (int action = 0; action < 4; action++) {
                    ScopeLease sample = new ScopeLease(200);
                    ScopeLease.Request baseline = sample.begin(1000);
                    ScopeLease.Certificate baselineCertificate = sample.complete(baseline, 1010, false);
                    ScopeLease.Request pending = sample.begin(1000 + offset);
                    require(sample.expire(1200) && sample.pending() && sample.begin(1201) == null,
                            "renewal-overlap-never-opens-an-extra-worker-slot");
                    if (action == 1) sample.invalidate();
                    if (action == 2) sample.close();
                    if (action == 3) sample.expire(1000 + offset + 200);
                    ScopeLease.Certificate certificate = sample.complete(pending, 1200 + delay, false);
                    boolean expected = action == 0 && delay < offset;
                    require((certificate != null) == expected, "overlap-still-requires-own-epoch-and-own-start-deadline");
                    require(sample.valid(certificate, 1200 + delay) == expected
                                    && !sample.valid(baselineCertificate, 1200 + delay),
                            "renewal-does-not-resurrect-predecessor-or-event-revoked-result");
                    require(!sample.valid(certificate, 1000 + offset + 200), "renewal-own-deadline-is-strict");
                }
            }
        }
    }

    private static void require(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}
