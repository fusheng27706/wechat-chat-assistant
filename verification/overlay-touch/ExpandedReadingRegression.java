package com.codextemp.wechatcaptureprobe;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 仅合成像素/数值，不读取手机或真实聊天。 */
public final class ExpandedReadingRegression {
    private static int checks;
    private static void require(boolean value, String name) {
        checks++;
        if (!value) throw new AssertionError(name);
    }
    public static void main(String[] args) {
        ScreenGeometry screen = new ScreenGeometry(1080, 2400, 440, 0, 0, 69, 0, 130, true);
        ChatViewport base = ChatViewport.create(screen, new ChatViewport.Bounds(20, 100, 1020, 2200), -1, 0, 0, List.of());
        ChatViewport masked = base.withOverlay(new ChatViewport.Bounds(800, 500, 1100, 900));
        require(masked != null && masked.masks.size() == 1, "clipped-overlay");
        require(masked.masked(800, 500) && !masked.masked(20, 500), "local-mask-coordinates");
        require(base.masks.isEmpty(), "base-immutable");
        require(base.withOverlay(new ChatViewport.Bounds(0, 101, 200, 200)) == null, "title-rejected");
        require(base.withOverlay(new ChatViewport.Bounds(0, 2100, 200, 2300)) == null, "composer-rejected");
        require(base.withOverlay(new ChatViewport.Bounds(1050, 100, 1080, 300)) == base, "outside-ignored");
        ChatViewport combined = masked.withOverlay(new ChatViewport.Bounds(600, 550, 850, 1000));
        MaskedRgbaRows rows = new MaskedRgbaRows(combined, 4320, 4320 * 2400, (offset, target, start, length) -> {
            int y = offset / 4320 - combined.bounds.top;
            int x = offset % 4320 / 4 - combined.bounds.left;
            for (int i = 0; i < length; i += 4) {
                require(!combined.masked(x + i / 4, y), "no-overlapping-mask-source-read");
                target[start+i] = 1; target[start+i+1] = 2; target[start+i+2] = 3;
            }
        });
        int[] row = new int[combined.width()];
        for (int y = 399; y < 901; y += 17) {
            rows.copyRow(y, row);
            for (int x = 0; x < row.length; x++) require(row[x] == (combined.masked(x,y) ? 0xff777777 : 0xff010203), "masked-copy-equivalent");
        }
        FrameFingerprintCache cache = new FrameFingerprintCache();
        AtomicInteger scans = new AtomicInteger();
        Object frame = new Object();
        for (int i=0; i<1000; i++) cache.prepare(frame, masked, () -> {scans.incrementAndGet(); return 11;}, () -> {scans.incrementAndGet(); return 22;});
        require(scans.get()==2 && cache.header()==11 && cache.body()==22, "one-scan-per-frame-not-per-guard");
        cache.prepare(frame, base, () -> 33, () -> 44);
        require(cache.header()==33 && cache.body()==44, "geometry-change-invalidates");
        Object next = new Object();
        try { cache.prepare(next, base, () -> 55, () -> {throw new IllegalStateException();}); throw new AssertionError("failure-not-thrown"); }
        catch (IllegalStateException expected) { require(cache.header()==33 && cache.body()==44, "failure-not-partially-installed"); }
        cache.prepare(next, base, () -> 55, () -> 66);
        require(cache.header()==55 && cache.body()==66, "new-image-scanned");
        cache.clear(); cache.prepare(next, base, () -> 77, () -> 88);
        require(cache.header()==77 && cache.body()==88, "close-invalidates-reused-object");
        CaptureCoverage coverage = new CaptureCoverage(0, 0, true);
        require(coverage.obscured() && !coverage.warning().isEmpty(), "secure-overlay-always-partial-even-no-current-mask");
        require(!coverage.signature().equals(CaptureCoverage.UNOBSTRUCTED.signature()), "partial-revokes-complete-signature");
        System.out.println("PASS expandedReadingChecks=" + checks + " guards=1000 actualScans=" + scans.get() + " baselineScans=2000 syntheticOnly=true");
    }
}
