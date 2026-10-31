package com.codextemp.wechatcaptureprobe;

import android.content.Context;
import android.graphics.Insets;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowInsets;
import android.view.WindowManager;

final class ScreenGeometryReader {
    static ScreenGeometry read(Context context) {
        try {
            WindowManager manager = context.getSystemService(WindowManager.class);
            Display display = manager.getDefaultDisplay();
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            if (Build.VERSION.SDK_INT >= 30) {
                Insets insets = manager.getMaximumWindowMetrics().getWindowInsets().getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                return new ScreenGeometry(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, display.getRotation(),
                        insets.left, insets.top, insets.right, insets.bottom, true);
            }
            return new ScreenGeometry(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, display.getRotation(),
                    0, dimension(context, "status_bar_height", Math.round(24 * metrics.density)),
                    0, dimension(context, "navigation_bar_height", Math.round(48 * metrics.density)), false);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static int dimension(Context context, String name, int fallback) {
        int identifier = context.getResources().getIdentifier(name, "dimen", "android");
        return identifier == 0 ? fallback : context.getResources().getDimensionPixelSize(identifier);
    }

    private ScreenGeometryReader() {
    }
}
