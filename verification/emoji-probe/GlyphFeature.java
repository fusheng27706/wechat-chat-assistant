package com.codextemp.wechatcaptureprobe;

final class GlyphFeature {
    private static final int SIDE = 32;
    private final int[] colors;
    private final int[] darkRows = new int[SIDE];
    private final int darkPixels;

    private GlyphFeature(int[] colors) {
        this.colors = colors;
        int count = 0;
        for (int vertical = 0; vertical < SIDE; vertical++) {
            for (int horizontal = 0; horizontal < SIDE; horizontal++) {
                int color = colors[vertical * SIDE + horizontal];
                int luminance = (54 * ((color >> 16) & 255) + 183 * ((color >> 8) & 255) + 19 * (color & 255)) >> 8;
                if (luminance < 160) {
                    darkRows[vertical] |= 1 << horizontal;
                    count++;
                }
            }
        }
        darkPixels = count;
    }

    static GlyphFeature extract(int[] pixels, int width, int height, int background) {
        if (width <= 0 || height <= 0 || width > 1024 || height > 1024 || pixels.length != (long) width * height) {
            throw new IllegalArgumentException("Invalid glyph dimensions");
        }
        int left = width;
        int top = height;
        int right = -1;
        int bottom = -1;
        for (int vertical = 0; vertical < height; vertical++) {
            for (int horizontal = 0; horizontal < width; horizontal++) {
                if (foreground(pixels[vertical * width + horizontal], background)) {
                    left = Math.min(left, horizontal);
                    right = Math.max(right, horizontal);
                    top = Math.min(top, vertical);
                    bottom = Math.max(bottom, vertical);
                }
            }
        }
        if (right <= left || bottom <= top) {
            return null;
        }
        double extent = Math.max(right - left + 1, bottom - top + 1);
        double centerHorizontal = (left + right) / 2.0;
        double centerVertical = (top + bottom) / 2.0;
        int[] normalized = new int[SIDE * SIDE];
        for (int vertical = 0; vertical < SIDE; vertical++) {
            for (int horizontal = 0; horizontal < SIDE; horizontal++) {
                double sourceHorizontal = centerHorizontal + (horizontal + 0.5 - SIDE / 2.0) * extent / (SIDE - 4);
                double sourceVertical = centerVertical + (vertical + 0.5 - SIDE / 2.0) * extent / (SIDE - 4);
                normalized[vertical * SIDE + horizontal] = interpolate(pixels, width, height,
                        sourceHorizontal, sourceVertical, background);
            }
        }
        return new GlyphFeature(normalized);
    }

    double distance(GlyphFeature other) {
        long total = 0;
        for (int index = 0; index < colors.length; index++) {
            total += difference(colors[index], other.colors[index]);
        }
        return total / (colors.length * 3.0 * 255);
    }

    double structureDistance(GlyphFeature other) {
        if (darkPixels < 4 || other.darkPixels < 4) return 1;
        int bestIntersection = 0;
        for (int down = -1; down <= 1; down++) {
            for (int across = -1; across <= 1; across++) {
                int intersection = 0;
                for (int vertical = 0; vertical < SIDE; vertical++) {
                    int sourceVertical = vertical + down;
                    if (sourceVertical < 0 || sourceVertical >= SIDE) continue;
                    int otherRow = other.darkRows[sourceVertical];
                    if (across < 0) otherRow >>>= 1;
                    if (across > 0) otherRow <<= 1;
                    intersection += Integer.bitCount(darkRows[vertical] & otherRow);
                }
                bestIntersection = Math.max(bestIntersection, intersection);
            }
        }
        double shape = 1 - 2.0 * bestIntersection / (darkPixels + other.darkPixels);
        return 0.85 * shape + 0.15 * distance(other);
    }

    private static int interpolate(int[] pixels, int width, int height, double horizontal, double vertical, int background) {
        int left = (int) Math.floor(horizontal);
        int top = (int) Math.floor(vertical);
        double across = horizontal - left;
        double down = vertical - top;
        int upperLeft = color(pixels, width, height, left, top, background);
        int upperRight = color(pixels, width, height, left + 1, top, background);
        int lowerLeft = color(pixels, width, height, left, top + 1, background);
        int lowerRight = color(pixels, width, height, left + 1, top + 1, background);
        int result = 0xff000000;
        for (int shift = 16; shift >= 0; shift -= 8) {
            double upper = ((upperLeft >> shift) & 255) * (1 - across) + ((upperRight >> shift) & 255) * across;
            double lower = ((lowerLeft >> shift) & 255) * (1 - across) + ((lowerRight >> shift) & 255) * across;
            result |= (int) Math.round(upper * (1 - down) + lower * down) << shift;
        }
        return result;
    }

    private static int color(int[] pixels, int width, int height, int horizontal, int vertical, int background) {
        if (horizontal < 0 || horizontal >= width || vertical < 0 || vertical >= height) {
            return 0xffffffff;
        }
        int color = pixels[vertical * width + horizontal];
        return foreground(color, background) ? color : 0xffffffff;
    }

    private static boolean foreground(int color, int background) {
        return ((color >>> 24) & 255) > 128 && difference(color, background) > 80;
    }

    private static int difference(int first, int second) {
        return Math.abs(((first >> 16) & 255) - ((second >> 16) & 255))
                + Math.abs(((first >> 8) & 255) - ((second >> 8) & 255)) + Math.abs((first & 255) - (second & 255));
    }
}
