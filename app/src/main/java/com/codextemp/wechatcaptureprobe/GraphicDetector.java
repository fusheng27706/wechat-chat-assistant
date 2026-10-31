package com.codextemp.wechatcaptureprobe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class GraphicDetector {
    static final class Region {
        final int left;
        final int top;
        final int right;
        final int bottom;

        Region(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    static List<Region> detect(ChatLayout.Pixels pixels, ChatLayout.Box bubble, float density) {
        int step = 2;
        int columns = Math.max(1, (bubble.right - bubble.left) / step);
        int rows = Math.max(1, (bubble.bottom - bubble.top) / step);
        byte[] mask = null;
        int[] histogram = new int[4096];
        int common = 0;
        for (int vertical = 0; vertical < rows; vertical += 2) {
            for (int horizontal = 0; horizontal < columns; horizontal += 2) {
                if (horizontal > columns * 0.15 && horizontal < columns * 0.85
                        && vertical > rows * 0.15 && vertical < rows * 0.85) {
                    continue;
                }
                int color = pixels.colorAt(bubble.left + horizontal * step, bubble.top + vertical * step);
                int bucket = ((color >> 20) & 15) << 8 | ((color >> 12) & 15) << 4 | ((color >> 4) & 15);
                histogram[bucket]++;
                if (histogram[bucket] > histogram[common]) {
                    common = bucket;
                }
            }
        }
        int backgroundColor = (((common >> 8) & 15) * 16 + 8) << 16
                | (((common >> 4) & 15) * 16 + 8) << 8 | (common & 15) * 16 + 8;
        for (int vertical = 0; vertical < rows; vertical++) {
            for (int horizontal = 0; horizontal < columns; horizontal++) {
                int color = pixels.colorAt(bubble.left + horizontal * step, bubble.top + vertical * step);
                if (colored(color) && !background(color, backgroundColor)) {
                    // 普通文字没有彩色候选时不分配整块掩码；采样点及候选判据保持不变。
                    if (mask == null) mask = new byte[columns * rows];
                    mask[vertical * columns + horizontal] = 1;
                }
            }
        }
        List<Region> regions = new ArrayList<>();
        // 普通文字气泡通常没有彩色候选，无需分配连通域队列或再遍历全零掩码。
        if (mask == null) return regions;
        int[] queue = new int[mask.length];
        for (int origin = 0; origin < mask.length; origin++) {
            if (mask[origin] == 0) {
                continue;
            }
            int beginning = 0;
            int ending = 1;
            queue[0] = origin;
            mask[origin] = 0;
            int left = columns;
            int right = 0;
            int top = rows;
            int bottom = 0;
            while (beginning < ending) {
                int position = queue[beginning++];
                int horizontal = position % columns;
                int vertical = position / columns;
                left = Math.min(left, horizontal);
                right = Math.max(right, horizontal);
                top = Math.min(top, vertical);
                bottom = Math.max(bottom, vertical);
                if (horizontal > 0 && mask[position - 1] != 0) {
                    mask[position - 1] = 0;
                    queue[ending++] = position - 1;
                }
                if (horizontal + 1 < columns && mask[position + 1] != 0) {
                    mask[position + 1] = 0;
                    queue[ending++] = position + 1;
                }
                if (vertical > 0 && mask[position - columns] != 0) {
                    mask[position - columns] = 0;
                    queue[ending++] = position - columns;
                }
                if (vertical + 1 < rows && mask[position + columns] != 0) {
                    mask[position + columns] = 0;
                    queue[ending++] = position + columns;
                }
            }
            int width = right - left + 1;
            int height = bottom - top + 1;
            if (width * step < density * 11 || height * step < density * 11
                    || width > height * 1.65 || height > width * 1.65 || ending < width * height * 0.25) {
                continue;
            }
            int foreground = 0;
            for (int vertical = top; vertical <= bottom; vertical++) {
                for (int horizontal = left; horizontal <= right; horizontal++) {
                    int color = pixels.colorAt(bubble.left + horizontal * step, bubble.top + vertical * step);
                    if (!background(color, backgroundColor)) {
                        foreground++;
                    }
                }
            }
            if (foreground >= width * height * 0.62) {
                regions.add(new Region(bubble.left + left * step, bubble.top + top * step,
                        Math.min(bubble.right, bubble.left + (right + 1) * step),
                        Math.min(bubble.bottom, bubble.top + (bottom + 1) * step)));
            }
        }
        regions.sort(Comparator.comparingInt((Region region) -> region.top).thenComparingInt(region -> region.left));
        return regions;
    }

    private static boolean colored(int color) {
        int red = (color >> 16) & 255;
        int green = (color >> 8) & 255;
        int blue = color & 255;
        return Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) > 50;
    }

    private static boolean background(int color, int background) {
        int red = (color >> 16) & 255;
        int green = (color >> 8) & 255;
        int blue = color & 255;
        return Math.abs(red - ((background >> 16) & 255)) + Math.abs(green - ((background >> 8) & 255))
                + Math.abs(blue - (background & 255)) < 75;
    }

    private GraphicDetector() {
    }
}
