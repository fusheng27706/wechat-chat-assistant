package com.codextemp.wechatcaptureprobe;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class ProcessCpuSnapshot {
    final int pid;
    final long ticks;
    final long startedTicks;

    private ProcessCpuSnapshot(int pid, long ticks, long startedTicks) {
        this.pid = pid;
        this.ticks = ticks;
        this.startedTicks = startedTicks;
    }

    static ProcessCpuSnapshot read(int pid) throws IOException {
        try (FileInputStream input = new FileInputStream("/proc/" + pid + "/stat")) {
            byte[] bytes = new byte[8192];
            int count = input.read(bytes);
            if (count <= 0 || count == bytes.length) {
                throw new IOException("Unexpected process stat size");
            }
            return parse(new String(bytes, 0, count, StandardCharsets.UTF_8), pid);
        }
    }

    static ProcessCpuSnapshot parse(String value, int expectedPid) {
        int opening = value.indexOf('(');
        int closing = value.lastIndexOf(')');
        if (expectedPid <= 0 || opening <= 0 || closing <= opening
                || Integer.parseInt(value.substring(0, opening).trim()) != expectedPid) {
            throw new IllegalArgumentException("Process identity mismatch");
        }
        String[] fields = value.substring(closing + 1).trim().split("\\s+");
        if (fields.length < 20 || fields[0].length() != 1) {
            throw new IllegalArgumentException("Incomplete process stat");
        }
        long user = Long.parseLong(fields[11]);
        long system = Long.parseLong(fields[12]);
        long started = Long.parseLong(fields[19]);
        if (user < 0 || system < 0 || started <= 0) {
            throw new IllegalArgumentException("Invalid process counters");
        }
        return new ProcessCpuSnapshot(expectedPid, Math.addExact(user, system), started);
    }

    long elapsedMillis(ProcessCpuSnapshot before, long ticksPerSecond) {
        if (before.pid != pid || before.startedTicks != startedTicks || ticks < before.ticks || ticksPerSecond <= 0) {
            throw new IllegalArgumentException("Process changed during measurement");
        }
        return Math.multiplyExact(ticks - before.ticks, 1000L) / ticksPerSecond;
    }
}
