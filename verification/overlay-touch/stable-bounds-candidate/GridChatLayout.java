package com.codextemp.wechatcaptureprobe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;

public final class GridChatLayout {
    public interface Pixels {
        int colorAt(int horizontal, int vertical);
    }

    public static final class Box {
        final int left;
        final int top;
        final int right;
        final int bottom;
        final boolean outgoing;

        Box(int left, int top, int right, int bottom, boolean outgoing) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.outgoing = outgoing;
        }

        boolean contains(int horizontal, int vertical) {
            return horizontal >= left && horizontal < right && vertical >= top && vertical < bottom;
        }
    }

    final int headerTop;
    final int contentTop;
    final int contentBottom;
    final List<Box> bubbles;
    final boolean dark;

    private GridChatLayout(int headerTop, int contentTop, int contentBottom, List<Box> bubbles, boolean dark) {
        this.headerTop = headerTop;
        this.contentTop = contentTop;
        this.contentBottom = contentBottom;
        this.bubbles = bubbles;
        this.dark = dark;
    }

    static GridChatLayout detect(int width, int height, float density, int statusHeight,
                             int navigationHeight, int keyboardTop, Pixels pixels) {
        int toolbarHeight = Math.round(48 * density);
        int contentTop = statusHeight + toolbarHeight;
        int base = keyboardTop > 0 ? keyboardTop : height - navigationHeight;
        int composerHeight = Math.round(56 * density);
        int contentBottom = base - composerHeight;
        if (contentBottom <= contentTop + Math.round(80 * density) || base > height) {
            return null;
        }
        int headerColor = pixels.colorAt(width / 8, statusHeight + toolbarHeight / 2);
        boolean dark = brightness(headerColor) < 100;
        int backPixels = 0;
        for (int vertical = statusHeight + toolbarHeight / 4;
             vertical < statusHeight + toolbarHeight * 3 / 4; vertical += 2) {
            for (int horizontal = Math.round(12 * density); horizontal < Math.min(width, Math.round(38 * density)); horizontal += 2) {
                int level = brightness(pixels.colorAt(horizontal, vertical));
                if (dark ? level > 150 : level < 110) {
                    backPixels++;
                }
            }
        }
        if (backPixels < 12) {
            return null;
        }
        int fieldPixels = 0;
        int background = brightness(pixels.colorAt(width * 11 / 100, base - composerHeight / 2));
        for (int horizontal : new int[]{width * 17 / 100, width * 65 / 100}) {
            for (int vertical : new int[]{base - Math.round(44 * density), base - Math.round(12 * density)}) {
                int level = brightness(pixels.colorAt(horizontal, vertical));
                if (dark ? level > background + 7 && level < 140 : level > 247) {
                    fieldPixels++;
                }
            }
        }
        int composerInk = 0;
        for (int vertical = base - Math.round(43 * density); vertical < base - Math.round(13 * density); vertical += 3) {
            for (int horizontal = Math.round(4 * density); horizontal < Math.min(width, Math.round(44 * density)); horizontal += 3) {
                int level = brightness(pixels.colorAt(horizontal, vertical));
                if (dark ? level > 150 : level < 110) {
                    composerInk++;
                }
            }
        }
        if (fieldPixels < 3 || composerInk < 8) {
            return null;
        }
        return new GridChatLayout(statusHeight, contentTop, contentBottom, Collections.emptyList(), dark);
    }

    GridChatLayout withBubbles(int width, float density, Pixels pixels) {
        return new GridChatLayout(headerTop, contentTop, contentBottom,
                findBubbles(width, contentTop, contentBottom, density, pixels, dark), dark);
    }

    private static List<Box> findBubbles(int width, int top, int bottom, float density, Pixels pixels, boolean dark) {
        int step = Math.max(2, width / 360);
        int margin = Math.min(width / 4, Math.round(55 * density));
        int left = margin;
        int right = width - margin;
        int columns = Math.max(1, (right - left) / step);
        int rows = Math.max(1, (bottom - top) / step);
        byte[] mask = new byte[columns * rows];
        int[] queue = new int[mask.length];
        for (int vertical = 0; vertical < rows; vertical++) {
            for (int horizontal = 0; horizontal < columns; horizontal++) {
                int color = pixels.colorAt(left + horizontal * step, top + vertical * step);
                int red = (color >> 16) & 255;
                int green = (color >> 8) & 255;
                int blue = color & 255;
                if (green > 100 && green > red * 1.15 && green > blue * 1.25) {
                    mask[vertical * columns + horizontal] = 2;
                } else if (dark ? red > 32 && red < 115 && Math.abs(red - green) < 10 && Math.abs(red - blue) < 10
                        : red > 247 && green > 247 && blue > 247) {
                    mask[vertical * columns + horizontal] = 1;
                }
            }
        }
        List<Box> boxes = new ArrayList<>();
        for (int origin = 0; origin < mask.length; origin++) {
            byte kind = mask[origin];
            if (kind == 0) {
                continue;
            }
            int head = 0;
            int tail = 1;
            queue[0] = origin;
            mask[origin] = 0;
            int minimumHorizontal = columns;
            int maximumHorizontal = 0;
            int minimumVertical = rows;
            int maximumVertical = 0;
            while (head < tail) {
                int index = queue[head++];
                int horizontal = index % columns;
                int vertical = index / columns;
                minimumHorizontal = Math.min(minimumHorizontal, horizontal);
                maximumHorizontal = Math.max(maximumHorizontal, horizontal);
                minimumVertical = Math.min(minimumVertical, vertical);
                maximumVertical = Math.max(maximumVertical, vertical);
                if (horizontal > 0 && mask[index - 1] == kind) {
                    mask[index - 1] = 0;
                    queue[tail++] = index - 1;
                }
                if (horizontal + 1 < columns && mask[index + 1] == kind) {
                    mask[index + 1] = 0;
                    queue[tail++] = index + 1;
                }
                if (vertical > 0 && mask[index - columns] == kind) {
                    mask[index - columns] = 0;
                    queue[tail++] = index - columns;
                }
                if (vertical + 1 < rows && mask[index + columns] == kind) {
                    mask[index + columns] = 0;
                    queue[tail++] = index + columns;
                }
            }
            int boxWidth = (maximumHorizontal - minimumHorizontal + 1) * step;
            int boxHeight = (maximumVertical - minimumVertical + 1) * step;
            int boxTop = top + minimumVertical * step;
            int boxBottom = top + (maximumVertical + 1) * step;
            if (boxWidth >= 20 * density && boxHeight >= 20 * density
                    && boxTop > top + step && boxBottom < bottom - step
                    && tail * step * step > boxWidth * boxHeight * 0.3) {
                boxes.add(new Box(left + minimumHorizontal * step, boxTop,
                        left + (maximumHorizontal + 1) * step, boxBottom, kind == 2));
            }
        }
        boxes.sort(Comparator.comparingInt(box -> box.top));
        return boxes;
    }

    private static int brightness(int color) {
        return (((color >> 16) & 255) + ((color >> 8) & 255) + (color & 255)) / 3;
    }
}
