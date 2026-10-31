package com.codextemp.wechatcaptureprobe;

import java.util.Arrays;
import java.util.Random;

public final class ProcessCpuSnapshotRegression {
    private static int checks;

    public static void main(String[] arguments) {
        Random random = new Random(1926703);
        for (String name : new String[]{"simple", "name with spaces", "name ) suffix", "nested (name) end"}) {
            for (int sample = 0; sample < 500; sample++) {
                long user = random.nextInt(1000000);
                long system = random.nextInt(1000000);
                long start = 40000 + sample;
                ProcessCpuSnapshot before = ProcessCpuSnapshot.parse(stat(name, user, system, start), 1234);
                ProcessCpuSnapshot after = ProcessCpuSnapshot.parse(stat(name, user + 4, system + 3, start), 1234);
                require(before.ticks == user + system && before.startedTicks == start && before.pid == 1234, "counter-fields");
                require(after.elapsedMillis(before, 100) == 70, "combined-user-system-delta");
            }
        }
        rejected(() -> ProcessCpuSnapshot.parse(stat("name", 1, 2, 3), 999), "wrong-pid");
        rejected(() -> ProcessCpuSnapshot.parse("1234 (name) S 0", 1234), "missing-fields");
        rejected(() -> ProcessCpuSnapshot.parse(stat("name", -1, 2, 3), 1234), "negative-counter");
        rejected(() -> ProcessCpuSnapshot.parse(stat("name", Long.MAX_VALUE, 2, 3), 1234), "overflow-counter");
        rejected(() -> ProcessCpuSnapshot.parse(stat("name", 1, 2, 0), 1234), "missing-start-identity");
        ProcessCpuSnapshot before = ProcessCpuSnapshot.parse(stat("name", 4, 5, 6), 1234);
        rejected(() -> ProcessCpuSnapshot.parse(stat("name", 4, 5, 7), 1234).elapsedMillis(before, 100), "pid-reuse");
        rejected(() -> ProcessCpuSnapshot.parse(stat("name", 3, 4, 6), 1234).elapsedMillis(before, 100), "decreasing-counter");
        rejected(() -> before.elapsedMillis(before, 0), "invalid-clock-rate");
        System.out.println("PASS processCpuChecks=" + checks + " network=0 chatFiles=0");
    }

    private static String stat(String name, long user, long system, long start) {
        String[] fields = new String[23];
        Arrays.fill(fields, "0");
        fields[0] = "S";
        fields[11] = Long.toString(user);
        fields[12] = Long.toString(system);
        fields[19] = Long.toString(start);
        return "1234 (" + name + ") " + String.join(" ", fields);
    }

    private static void rejected(Runnable action, String name) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException | ArithmeticException expected) { rejected = true; }
        require(rejected, name);
    }

    private static void require(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}
