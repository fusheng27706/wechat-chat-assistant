package com.codextemp.wechatcaptureprobe;

/** 固定像素/虚构时钟；不读取聊天，不等同实际OCR质量与延迟。 */
public final class ExactPixelCacheRegression {
    private static int checks;
    private static void require(boolean value, String name) {
        checks++; if (!value) throw new AssertionError(name);
    }
    public static void main(String[] args) {
        ExactPixelCache<String> cache = new ExactPixelCache<>(8, 2);
        int[] input = {1, 2, 3};
        cache.put("same-hash", input, "A", 100);
        input[0] = 9;
        require(cache.get("same-hash", input, 101) == null, "caller-array-not-retained");
        require("A".equals(cache.get("same-hash", new int[]{1, 2, 3}, 102)), "exact-match");
        require(cache.get("same-hash", new int[]{1, 2, 4}, 103) == null, "hash-collision-rejected");
        require(cache.get("same-hash", new int[]{1, 2}, 104) == null, "length-mismatch");
        require(cache.get("different-density-budget-role", new int[]{1, 2, 3}, 105) == null, "context-key-required");
        cache.put("B", new int[]{4, 5, 6}, "B", 106);
        cache.quarantine(200);
        cache.quarantine(1000);
        require("A".equals(cache.get("same-hash", new int[]{1, 2, 3}, 5199)), "matching-fresh-input-reactivates-before-deadline");
        require(cache.activate("same-hash", new int[]{1, 2, 3}, "A", 5199), "valid-source-commits-hit");
        require(cache.get("B", new int[]{4, 5, 6}, 5200) == null, "repeat-quarantine-does-not-extend");
        require(cache.size() == 1 && cache.pixels() == 3, "expired-memory-accounted");
        require("A".equals(cache.get("same-hash", new int[]{1, 2, 3}, 20000)), "active-entry-survives-idle");
        cache.quarantine(20001);
        require(cache.get("same-hash", new int[]{1, 2, 3}, 25001) == null, "reactivated-entry-new-isolation-deadline");
        cache.put("A", new int[]{1, 2, 3}, "A", 26000);
        cache.put("B", new int[]{4, 5, 6}, "B", 26001);
        cache.get("A", new int[]{1, 2, 3}, 26002);
        cache.put("C", new int[]{7, 8, 9}, "C", 26003);
        require(cache.get("B", new int[]{4, 5, 6}, 26004) == null, "lru-entry-cap");
        cache.put("D", new int[]{1, 2, 3, 4, 5, 6}, "D", 26005);
        require(cache.size() == 1 && cache.pixels() == 6, "pixel-budget-evicts-enough");
        cache.put("huge", new int[9], "H", 26006);
        require(cache.size() == 1 && cache.pixels() == 6, "oversized-entry-not-retained");
        cache.put("D", new int[]{1}, "new", 26007);
        require(cache.pixels() == 1 && "new".equals(cache.get("D", new int[]{1}, 26008)), "replacement-accounting");
        cache.prune(1);
        require(cache.size() == 0 && cache.pixels() == 0, "clock-rollback-clears");
        cache.put("E", new int[]{8}, "E", 2);
        cache.clear();
        require(cache.size() == 0 && cache.pixels() == 0, "explicit-clear");
        cache.put("Q", new int[]{7}, "Q", 10);
        cache.quarantine(11);
        require("Q".equals(cache.get("Q", new int[]{7}, 5000)), "lookup-before-expiry");
        cache.quarantine(5001);
        require(!cache.activate("Q", new int[]{7}, "Q", 5011), "uncommitted-lookup-does-not-renew-expiry");
        cache.put("Q", new int[]{7}, "replacement", 5012);
        require(!cache.activate("Q", new int[]{7}, "Q", 5013), "replacement-cannot-activate-old-result");
        require(input[0] == 9, "clear-does-not-mutate-caller-input");
        ExactPixelCache<String> packed = new ExactPixelCache<>(12, 3);
        int[] solid = new int[12];
        java.util.Arrays.fill(solid, 0xff123456);
        packed.put("one", solid, "one", 1);
        packed.put("two", solid, "two", 2);
        packed.put("three", solid, "three", 3);
        require(packed.size() == 3 && packed.pixels() == 36 && packed.storedInts() == 6, "lossless-storage-budget");
        require("one".equals(packed.get("one", solid, 4)), "encoded-exact-match");
        int[] changed = solid.clone(); changed[11] ^= 0x01000000;
        require(packed.get("one", changed, 5) == null, "encoded-alpha-bit-mismatch");
        require(packed.get("one", new int[11], 6) == null, "encoded-length-mismatch");
        packed.quarantine(10); packed.quarantine(100);
        require(!packed.activate("one", solid, "one", 5010) && packed.storedInts() == 0, "encoded-expiry-no-renewal");
        packed.put("one", solid, "one", 5011);
        packed.put("two", solid, "two", 5012);
        packed.put("three", solid, "three", 5013);
        packed.get("one", solid, 5014);
        packed.put("four", solid, "four", 5015);
        require(packed.size() == 3 && packed.get("two", solid, 5016) == null, "encoded-entry-limit-and-lru");
        packed.put("one", new int[]{1,2,3,4,5,6,7,8,9,10,11,12}, "raw", 5017);
        require(packed.size() == 1 && packed.pixels() == 12 && packed.storedInts() == 12, "encoded-to-raw-replacement-eviction");
        packed.put("one", solid, "packed", 5018);
        require(packed.pixels() == 12 && packed.storedInts() == 2, "raw-to-encoded-replacement-accounting");
        packed.prune(1);
        require(packed.size() == 0 && packed.storedInts() == 0 && packed.pixels() == 0, "encoded-clock-rollback");
        java.util.Random random = new java.util.Random(147);
        for (int trial = 0; trial < 250; trial++) {
            int[] original = new int[3 + random.nextInt(120)];
            for (int i = 0; i < original.length; i++) original[i] = i == 0 || random.nextInt(8) == 0 ? random.nextInt() : original[i - 1];
            ExactPixelCache<String> check = new ExactPixelCache<>(128, 2);
            int[] expected = original.clone();
            check.put("fixed", original, "value", 0);
            original[0] ^= 1;
            require("value".equals(check.get("fixed", expected, 1)), "encoded-clone-and-roundtrip");
            expected[random.nextInt(expected.length)] ^= 1;
            require(check.get("fixed", expected, 2) == null, "encoded-one-bit-collision");
            require(check.storedInts() <= original.length, "encoding-never-expands");
            check.clear();
            require(check.pixels() == 0 && check.storedInts() == 0, "encoded-clear-accounting");
        }
        ExactPixelCache<String> hints = new ExactPixelCache<>(8, 2);
        hints.put("A", new int[]{1}, "A", 0);
        hints.put("B", new int[]{2}, "B", 1);
        require(hints.mayContain("A") && !hints.mayContain("absent"), "hint-presence");
        hints.put("C", new int[]{3}, "C", 2);
        require(!hints.mayContain("A") && hints.mayContain("B"), "hint-does-not-touch-lru");
        hints.quarantine(3);
        require(hints.mayContain("B"), "quarantined-hint-is-only-a-hint");
        require(hints.get("B", new int[]{9}, 4) == null, "hint-does-not-bypass-full-pixels");
        require(hints.get("B", new int[]{2}, 5003) == null, "hint-does-not-extend-expiry");
        hints.clear();
        require(!hints.mayContain("C"), "clear-drops-hints");
        System.out.println("PASS exactPixelCacheChecks=" + checks + " fixedPixels=true realImages=0");
    }
}
