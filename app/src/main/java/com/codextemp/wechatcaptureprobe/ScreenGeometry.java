package com.codextemp.wechatcaptureprobe;

final class ScreenGeometry {
    final int width;
    final int height;
    final int densityDpi;
    final int rotation;
    final int leftInset;
    final int topInset;
    final int rightInset;
    final int bottomInset;
    final boolean reliableInsets;

    ScreenGeometry(int width, int height, int densityDpi, int rotation, int leftInset, int topInset,
                   int rightInset, int bottomInset, boolean reliableInsets) {
        if (width < 240 || height < 240 || width > 8192 || height > 8192 || (long) width * height > 12582912
                || densityDpi < 72 || densityDpi > 1280 || rotation < 0 || rotation > 3
                || leftInset < 0 || topInset < 0 || rightInset < 0 || bottomInset < 0
                || (long) leftInset + rightInset >= width || (long) topInset + bottomInset >= height) {
            throw new IllegalArgumentException("Invalid screen geometry");
        }
        this.width = width;
        this.height = height;
        this.densityDpi = densityDpi;
        this.rotation = rotation;
        this.leftInset = leftInset;
        this.topInset = topInset;
        this.rightInset = rightInset;
        this.bottomInset = bottomInset;
        this.reliableInsets = reliableInsets;
    }

    boolean same(ScreenGeometry other) {
        return other != null && width == other.width && height == other.height && densityDpi == other.densityDpi
                && rotation == other.rotation && leftInset == other.leftInset && topInset == other.topInset
                && rightInset == other.rightInset && bottomInset == other.bottomInset && reliableInsets == other.reliableInsets;
    }

    int contentWidth() {
        return width - leftInset - rightInset;
    }

    boolean supported() {
        return contentWidth() >= dp(180) && height - topInset - bottomInset >= dp(200)
                && (reliableInsets || width <= height);
    }

    int dp(int value) {
        return Math.round(value * densityDpi / 160f);
    }

    int dockLeft(int panelWidth, boolean right) {
        return right ? Math.max(leftInset, width - rightInset - panelWidth) : leftInset;
    }

    int composerLeft() {
        return leftInset + dp(40);
    }

    int composerRight() {
        return width - rightInset - dp(48);
    }

    boolean chatAreaAboveKeyboard(int keyboardTop, int keyboardLeft, int keyboardRight) {
        return keyboardTop < 0 || keyboardTop > topInset + dp(184)
                && keyboardTop <= height && keyboardLeft <= leftInset + dp(8)
                && keyboardRight >= width - rightInset - dp(8);
    }
}
