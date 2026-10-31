package com.codextemp.wechatcaptureprobe;

import java.util.Arrays;
import java.util.Random;

public final class GlyphFeatureRegression {
    private static int checks;

    public static void main(String[] arguments) {
        for (int background : new int[]{0xffffffff, 0xff95ec69, 0xff303030}) {
            int[] empty = new int[80 * 90];
            Arrays.fill(empty, background);
            require(GlyphFeature.extract(empty, 80, 90, background) == null, "empty-is-not-a-glyph");
            GlyphFeature first = GlyphFeature.extract(pattern(background, 0, 0, false), 80, 90, background);
            require(first != null && first.distance(first) == 0, "self-distance-zero");
            require(first.structureDistance(first) == 0, "structure-self-distance-zero");
            for (int across = -8; across <= 8; across += 2) {
                for (int down = -8; down <= 8; down += 2) {
                    GlyphFeature translated = GlyphFeature.extract(pattern(background, across, down, false), 80, 90, background);
                    require(first.distance(translated) == 0, "translation-retains-shape");
                    require(translated.distance(first) == first.distance(translated), "distance-symmetric");
                    require(first.structureDistance(translated) == 0, "structure-translation-retains-shape");
                    require(translated.structureDistance(first) == first.structureDistance(translated), "structure-distance-symmetric");
                }
            }
            GlyphFeature mirrored = GlyphFeature.extract(pattern(background, 0, 0, true), 80, 90, background);
            require(first.distance(mirrored) > 0 && first.distance(mirrored) <= 1, "left-right-detail-not-collapsed");
            require(first.structureDistance(mirrored) > 0 && first.structureDistance(mirrored) <= 1, "structure-left-right-not-collapsed");
        }
        int[] bright = pattern(0xffffffff, 0, 0, false);
        for (int index = 0; index < bright.length; index++) {
            if (bright[index] == 0xff101010) bright[index] = 0xffffbc20;
        }
        GlyphFeature missingStructure = GlyphFeature.extract(bright, 80, 90, 0xffffffff);
        require(missingStructure != null && missingStructure.structureDistance(missingStructure) == 1,
                "no-dark-structure-is-not-an-exact-match");
        Random random = new Random(9381);
        for (int iteration = 0; iteration < 200; iteration++) {
            int[] pixels = pattern(0xffffffff, 0, 0, random.nextBoolean());
            for (int sample = 0; sample < 30; sample++) pixels[(20 + random.nextInt(40)) * 80 + 20 + random.nextInt(40)] = 0xff101010;
            GlyphFeature first = GlyphFeature.extract(pixels, 80, 90, 0xffffffff);
            GlyphFeature other = GlyphFeature.extract(pattern(0xffffffff, 0, 0, false), 80, 90, 0xffffffff);
            double distance = first.structureDistance(other);
            require(Double.isFinite(distance) && distance >= 0 && distance <= 1, "structure-distance-bounded");
            require(Math.abs(distance - other.structureDistance(first)) < 0.000000001, "structure-random-symmetry");
        }
        int[] transparent = new int[40 * 40];
        Arrays.fill(transparent, 0x00ff0000);
        require(GlyphFeature.extract(transparent, 40, 40, 0xffffffff) == null, "transparent-rgb-not-a-glyph");
        int[] point = new int[20 * 20];
        Arrays.fill(point, 0xffffffff);
        point[100] = 0xff000000;
        require(GlyphFeature.extract(point, 20, 20, 0xffffffff) == null, "single-pixel-rejected");
        rejected(() -> GlyphFeature.extract(new int[0], 0, 0, 0), "empty-dimensions");
        rejected(() -> GlyphFeature.extract(new int[1], -1, 1, 0), "negative-dimensions");
        rejected(() -> GlyphFeature.extract(new int[1], 2, 2, 0), "length-mismatch");
        rejected(() -> GlyphFeature.extract(new int[0], 65536, 65536, 0), "overflow-dimensions");
        rejected(() -> GlyphFeature.extract(new int[1], 1025, 1, 0), "unbounded-glyph-rejected");
        System.out.println("PASS glyphFeatureChecks=" + checks + " modelRequests=0 rawChatFiles=0");
    }

    private static int[] pattern(int background, int across, int down, boolean mirrored) {
        int[] pixels = new int[80 * 90];
        Arrays.fill(pixels, background);
        for (int vertical = 20; vertical < 60; vertical++) {
            for (int horizontal = 20; horizontal < 60; horizontal++) {
                int sample = mirrored ? 79 - horizontal : horizontal;
                int color = sample < 32 && vertical < 35 ? 0xff101010 : 0xffffbc20;
                pixels[(vertical + down) * 80 + horizontal + across] = color;
            }
        }
        return pixels;
    }

    private static void rejected(Runnable action, String name) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, name);
    }

    private static void require(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}
