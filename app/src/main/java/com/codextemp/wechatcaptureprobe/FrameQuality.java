package com.codextemp.wechatcaptureprobe;

public final class FrameQuality {
    @FunctionalInterface
    public interface PixelReader {
        int colorAt(int horizontal, int vertical);
    }

    public static boolean isMostlyBlack(int width, int height, PixelReader pixels) {
        int sampledPixels = 0;
        int blackPixels = 0;
        int horizontalStep = Math.max(1, width / 80);
        int verticalStep = Math.max(1, height / 80);
        for (int vertical = height / 10; vertical < height * 9 / 10; vertical += verticalStep) {
            for (int horizontal = width / 10; horizontal < width * 9 / 10; horizontal += horizontalStep) {
                int color = pixels.colorAt(horizontal, vertical);
                if (((color >> 16) & 255) <= 20 && ((color >> 8) & 255) <= 20 && (color & 255) <= 20) {
                    blackPixels++;
                }
                sampledPixels++;
            }
        }
        return sampledPixels > 0 && blackPixels >= sampledPixels * 0.98;
    }

    private FrameQuality() {
    }
}
