package com.codextemp.wechatcaptureprobe;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

public final class OwnWindowIdsRegression {
    private static int checks;

    public static void main(String[] arguments) throws Exception {
        OwnWindowIds ids = new OwnWindowIds(3);
        require(!ids.contains(-1) && !ids.remember(-1) && ids.size() == 0, "undefined-window-never-owned");
        require(ids.remember(0) && ids.contains(0), "framework-zero-window-id-is-valid");
        ids.remember(11); ids.remember(12);
        require(ids.ignoreEvent(11, true, false), "late-owned-window-event-survives-new-attachment");
        require(ids.ignoreEvent(11, false, true), "late-owned-state-event-requires-own-package");
        require(!ids.ignoreEvent(11, false, false), "foreign-package-state-not-exempted-by-retired-id");
        require(!ids.ignoreEvent(99, true, true) && !ids.ignoreOcclusion(99, true), "unknown-id-never-exempt-even-with-own-package");
        require(ids.ignoreOcclusion(11, true) && !ids.ignoreOcclusion(11, false), "only-known-accessibility-overlay-can-be-omitted");
        require(!ids.remember(11), "repeat-observation-is-not-new-window");
        ids.remember(13);
        require(!ids.contains(0) && ids.contains(11) && ids.contains(12) && ids.contains(13), "bounded-recent-window-history");
        ids.remember(14);
        require(!ids.contains(12) && ids.contains(11), "recently-observed-window-retained");
        ids.clear();
        require(ids.size() == 0 && !ids.ignoreEvent(11, true, true) && !ids.ignoreOcclusion(11, true), "connection-reset-revokes-history");
        Random random = new Random(760328);
        for (int capacity = 1; capacity <= 16; capacity++) {
            OwnWindowIds sample = new OwnWindowIds(capacity);
            LinkedHashSet<Integer> expected = new LinkedHashSet<>();
            for (int iteration = 0; iteration < 100; iteration++) {
                int identifier = random.nextInt(40) - 3;
                if (iteration % 17 == 0) { sample.clear(); expected.clear(); }
                boolean added = identifier >= 0 && !expected.contains(identifier);
                require(sample.remember(identifier) == added, "new-registration-matches-numeric-model");
                if (identifier >= 0) {
                    expected.remove(identifier); expected.add(identifier);
                    while (expected.size() > capacity) {
                        Iterator<Integer> oldest = expected.iterator(); oldest.next(); oldest.remove();
                    }
                }
                require(sample.size() == expected.size() && sample.size() <= capacity, "history-always-bounded");
                for (int probe = -3; probe < 40; probe++) {
                    boolean known = expected.contains(probe);
                    require(sample.contains(probe) == known, "ownership-matches-connection-history");
                    require(sample.ignoreEvent(probe, true, false) == known, "window-event-exempts-only-known-ids");
                    require(sample.ignoreEvent(probe, false, true) == known, "own-state-event-exempts-only-known-ids");
                    require(!sample.ignoreEvent(probe, false, false), "foreign-state-not-exempted");
                    require(sample.ignoreOcclusion(probe, true) == known && !sample.ignoreOcclusion(probe, false),
                            "occlusion-exemption-requires-known-id-and-accessibility-type");
                }
            }
        }
        OwnWindowIds concurrent = new OwnWindowIds(64);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try { for (int identifier = 0; identifier < 10000; identifier++) concurrent.remember(identifier); }
            catch (Throwable error) { failure.set(error); }
        });
        Thread reader = new Thread(() -> {
            try {
                for (int iteration = 0; iteration < 10000; iteration++) {
                    if (concurrent.size() > 64 || concurrent.contains(-1)) throw new AssertionError("concurrent-history-invalid");
                    concurrent.ignoreOcclusion(iteration, true);
                }
            } catch (Throwable error) { failure.set(error); }
        });
        writer.start(); reader.start(); writer.join(); reader.join();
        require(failure.get() == null && concurrent.size() == 64, "main-worker-concurrent-access-bounded");
        System.out.println("PASS ownWindowChecks=" + checks + " modelRequests=0 chatFiles=0");
    }

    private static void require(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}
