package com.codextemp.wechatcaptureprobe;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class CaptureService extends Service {
    private static final String CHANNEL = "capture-probe";
    private static final String STOP_ACTION = "com.codextemp.wechatcaptureprobe.STOP";
    private static volatile boolean active;
    private static CaptureService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ProjectionSession projectionSession;
    private ImageReader imageReader;
    private Image latestImage;
    private long latestImageAt;
    private boolean armed;
    private boolean captured;
    private boolean finished;
    private TextRecognizer recognizer;
    private boolean continuous;
    private boolean processing;
    private int frameNumber;
    private long sessionStartedAt;
    private final Set<String> seenSampleCodes = new LinkedHashSet<>();
    private Runnable ocrTimeout;

    static boolean running() {
        return active;
    }

    static void stopCurrent() {
        if (instance != null) {
            instance.endSession("USER_STOP");
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "采集验证", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP_ACTION.equals(intent.getAction())) {
            endSession("USER_STOP");
            return START_NOT_STICKY;
        }
        if (active) {
            return START_NOT_STICKY;
        }
        continuous = intent != null && intent.getBooleanExtra("continuous", false);
        Notification notification = notification(continuous
                ? "限时连续测试，最多 3 分钟；可点击停止" : "8 秒后仅采集一帧，可随时停止", true);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(1, notification);
            }
        } catch (RuntimeException exception) {
            fail("无法启动诊断：" + exception.getClass().getSimpleName());
            return START_NOT_STICKY;
        }
        if (AssistantCaptureService.running()) {
            finished = true;
            ProbeState.fail("聊天辅助仍在运行，已拒绝并行开启；请先停止所有采集");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ProjectionSession.available()) {
            finished = true;
            ProbeState.fail("上次共享仍在清理，未开启诊断；请稍候重新开始");
            stopSelf();
            return START_NOT_STICKY;
        }
        active = true;
        instance = this;
        AssistantState.clear();
        sessionStartedAt = SystemClock.elapsedRealtime();
        ProbeState.begin();
        if (continuous) {
            ProbeState.status("连续测试已开启，8 秒后开始；每次识别后间隔 5 秒，最多 3 分钟。请切到文件传输助手。");
            Log.i("CaptureProbe", "START mode=continuous maxSeconds=180 intervalSeconds=5");
        }
        if (intent == null) {
            fail("采集授权缺失，请重新开始。");
            return START_NOT_STICKY;
        }
        int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
        Intent data = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra("captureData", Intent.class)
                : intent.getParcelableExtra("captureData");
        if (resultCode != Activity.RESULT_OK || data == null) {
            fail("采集授权无效，请重新开始。");
            return START_NOT_STICKY;
        }
        try {
            DisplayMetrics metrics = new DisplayMetrics();
            getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(metrics);
            imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 3);
            imageReader.setOnImageAvailableListener(this::receiveImage, handler);
            ScreenGeometry geometry = new ScreenGeometry(metrics.widthPixels, metrics.heightPixels,
                    metrics.densityDpi, 0, 0, 0, 0, 0, false);
            projectionSession = new ProjectionSession(this, resultCode, data, "WechatCaptureProbe", geometry,
                    imageReader.getSurface(), 1, notification, new ProjectionSession.Listener() {
                        @Override public void ready() {
                            if (finished) {
                                return;
                            }
                            handler.postDelayed(CaptureService.this::armCapture, 8000);
                            if (continuous) {
                                handler.postDelayed(() -> endSession("TIME_LIMIT"), 180000);
                            }
                            handler.postDelayed(() -> {
                                if (!captured) {
                                    fail("未取得有效画面，请结束本轮，检查共享目标后重新授权。");
                                }
                            }, 14000);
                        }
                        @Override public void stopped(String reason) {
                            if (!finished && (continuous || !captured)) {
                                endSession("SYSTEM_STOP");
                            }
                        }
                        @Override public void source(int width, int height, boolean visible) {
                            if (!finished && (!visible || width > 0 && height > 0
                                    && (width != metrics.widthPixels || height != metrics.heightPixels))) {
                                fail("共享源不可见或尺寸不匹配，请结束本轮后重新授权");
                            }
                        }
                    });
        } catch (RuntimeException exception) {
            fail("系统拒绝或无法建立采集：" + exception.getClass().getSimpleName());
        }
        return START_NOT_STICKY;
    }

    private void receiveImage(ImageReader reader) {
        if (finished || (!continuous && captured)) {
            return;
        }
        Image frame;
        try {
            frame = reader.acquireLatestImage();
        } catch (RuntimeException exception) {
            fail("读取画面失败：" + exception.getClass().getSimpleName());
            return;
        }
        if (frame == null) {
            return;
        }
        if (projectionSession == null || !projectionSession.ready()) {
            frame.close();
            return;
        }
        if (armed && !processing) {
            processImage(frame, 0);
        } else {
            if (latestImage != null) {
                latestImage.close();
            }
            latestImage = frame;
            latestImageAt = SystemClock.elapsedRealtime();
        }
    }

    private void armCapture() {
        if (finished || processing) {
            return;
        }
        armed = true;
        ProbeState.status(continuous ? "连续测试：等待当前画面…" : "正在获取本轮唯一一帧画面…");
        if (latestImage != null) {
            Image selectedImage = latestImage;
            latestImage = null;
            processImage(selectedImage, SystemClock.elapsedRealtime() - latestImageAt);
        }
    }

    private void processImage(Image frame, long frameAgeMs) {
        if (finished || processing || (!continuous && captured)) {
            frame.close();
            return;
        }
        captured = true;
        processing = true;
        armed = false;
        frameNumber++;
        Bitmap image;
        try {
            Image.Plane plane = frame.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            if (pixelStride != 4) {
                throw new IllegalStateException("Unexpected pixel stride");
            }
            int paddedWidth = plane.getRowStride() / pixelStride;
            Bitmap padded = Bitmap.createBitmap(paddedWidth, frame.getHeight(), Bitmap.Config.ARGB_8888);
            buffer.rewind();
            padded.copyPixelsFromBuffer(buffer);
            image = Bitmap.createBitmap(padded, 0, 0, frame.getWidth(), frame.getHeight());
            if (image != padded) {
                padded.recycle();
            }
        } catch (RuntimeException exception) {
            fail("转换画面失败：" + exception.getClass().getSimpleName());
            return;
        } finally {
            frame.close();
        }
        if (!continuous) {
            releaseCapture();
        }
        ProbeState.status(continuous ? "连续测试：正在本机识别第 " + frameNumber + " 帧…"
                : "采集已结束，正在本机识别中文…");
        if (recognizer == null) {
            recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        }
        long startedAt = SystemClock.elapsedRealtime();
        ocrTimeout = () -> fail("本机 OCR 超时，本轮已停止，请重新开始。");
        handler.postDelayed(ocrTimeout, 30000);
        recognizer.process(InputImage.fromBitmap(image, 0))
                .addOnSuccessListener(result -> {
                    if (finished) {
                        image.recycle();
                        return;
                    }
                    handler.removeCallbacks(ocrTimeout);
                    processing = false;
                    List<Text.Line> lines = new ArrayList<>();
                    for (Text.TextBlock block : result.getTextBlocks()) {
                        lines.addAll(block.getLines());
                    }
                    lines.sort(Comparator.comparingInt((Text.Line line) -> bounds(line).top)
                            .thenComparingInt(line -> bounds(line).left));
                    boolean hasContentText = lines.stream().anyMatch(line -> {
                        Rect rectangle = bounds(line);
                        return rectangle.centerY() > image.getHeight() / 10
                                && rectangle.centerY() < image.getHeight() * 9 / 10;
                    });
                    boolean contentUnavailable = !hasContentText
                            && FrameQuality.isMostlyBlack(image.getWidth(), image.getHeight(), image::getPixel);
                    Set<String> currentSampleCodes = ConversationSamples.matchedCodes(result.getText());
                    seenSampleCodes.addAll(currentSampleCodes);
                    long ocrMs = SystemClock.elapsedRealtime() - startedAt;
                    if (continuous) {
                        Log.i("CaptureProbe", "FRAME number=" + frameNumber + " lines=" + lines.size()
                                + " ocrMs=" + ocrMs + " current=" + String.join(",", currentSampleCodes)
                                + " seen=" + String.join(",", seenSampleCodes)
                                + " quality=" + (contentUnavailable ? "BLACK" : "REVIEW"));
                    }
                    StringBuilder output = new StringBuilder("文字行及坐标（尚未自动判定发言者）\n\n");
                    for (int index = 0; index < lines.size(); index++) {
                        Text.Line line = lines.get(index);
                        Rect rectangle = bounds(line);
                        output.append(String.format(Locale.ROOT, "%02d [%d,%d,%d,%d] %s%n",
                                index + 1, rectangle.left, rectangle.top, rectangle.right, rectangle.bottom, line.getText()));
                    }
                    if (lines.isEmpty()) {
                        output.append("没有识别到文字。请检查预览是否为正确页面、黑屏或尚未显示完成；本工具无法仅凭黑图确认原因。");
                    }
                    String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
                    String normalizedText = Normalizer.normalize(result.getText(), Normalizer.Form.NFKC)
                            .replaceAll("[\\p{P}\\p{Z}\\s]", "");
                    String[] samples = {"客户预算只有八千今天能定吗", "我我们先确认交付范围", "群成员小李周五下午三点开会"};
                    int matchingSamples = 0;
                    for (String sample : samples) {
                        if (normalizedText.contains(sample)) {
                            matchingSamples++;
                        }
                    }
                    String summary = "时间：" + timestamp + "\n尺寸：" + image.getWidth() + " × " + image.getHeight()
                            + "\n识别文字行：" + lines.size() + "\nOCR 耗时："
                            + ocrMs + " ms\n最后画面更新距取帧：" + frameAgeMs + " ms"
                            + "\n已知测试句：" + matchingSamples + "/3（忽略标点与空白）"
                            + (matchingSamples == 3 ? "\n中文基线通过；不代表微信采集已通过。"
                            : "\n非基线页面可忽略测试句数量；微信消息需另行核对。")
                            + (contentUnavailable
                            ? "\n画面检查：主要内容区近乎黑色且未读到正文。可能是空白或受保护页面，原因未确认；请停止该场景，不要连续重试。"
                            : "\n画面检查：仍需人工确认页面与消息归属。")
                            + (continuous ? "\n连续测试帧：" + frameNumber + "\n本帧画面文本样本："
                            + currentSampleCodes + "\n累计画面文本匹配：" + seenSampleCodes
                            + "\n匹配可能包含输入区，不代表已发送消息或发言者判定。"
                            + "\n本轮已运行：" + ((SystemClock.elapsedRealtime() - sessionStartedAt) / 1000) + " 秒"
                            : "\n仅一帧；请人工确认目标页面、消息和群成员。");
                    ProbeState.finish(image, summary, output.toString(), lines.size(), contentUnavailable);
                    if (continuous) {
                        if (contentUnavailable) {
                            endSession("BLACK_FRAME");
                        } else {
                            ProbeState.status("连续测试中：已识别 " + frameNumber + " 帧，固定样本累计匹配 "
                                    + seenSampleCodes.size() + "/8。通知内可停止。");
                            handler.postDelayed(this::armCapture, 5000);
                        }
                        return;
                    }
                    finished = true;
                    if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            == PackageManager.PERMISSION_GRANTED) {
                        getSystemService(NotificationManager.class).notify(2, notification("单次验证完成，点击核对原图和文字", false));
                    }
                    stopSelf();
                })
                .addOnFailureListener(exception -> {
                    image.recycle();
                    fail("本机 OCR 失败：" + exception.getClass().getSimpleName());
                });
    }

    private void endSession(String reason) {
        if (finished) {
            return;
        }
        finished = true;
        releaseCapture();
        ProbeState.status("采集会话已结束（" + reason + "），识别 " + frameNumber
                + " 帧；固定样本累计匹配 " + seenSampleCodes.size() + "/8。原图和文字需人工核对。");
        Log.i("CaptureProbe", "STOP reason=" + reason + " frames=" + frameNumber
                + " seen=" + String.join(",", seenSampleCodes));
        stopSelf();
    }

    private static Rect bounds(Text.Line line) {
        Rect rectangle = line.getBoundingBox();
        return rectangle == null ? new Rect() : rectangle;
    }

    private void fail(String message) {
        if (finished) {
            return;
        }
        finished = true;
        if (continuous) {
            Log.i("CaptureProbe", "STOP reason=ERROR frames=" + frameNumber
                    + " seen=" + String.join(",", seenSampleCodes));
        }
        ProbeState.fail(message);
        releaseCapture();
        stopSelf();
    }

    private Notification notification(String message, boolean ongoing) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("微信采集验证器")
                .setContentText(message)
                .setContentIntent(pendingIntent)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing);
        if (ongoing) {
            PendingIntent stop = PendingIntent.getService(this, 1,
                    new Intent(this, CaptureService.class).setAction(STOP_ACTION),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "停止测试", stop).build());
        }
        return builder.build();
    }

    private void releaseCapture() {
        if (latestImage != null) {
            latestImage.close();
            latestImage = null;
        }
        if (projectionSession != null) {
            projectionSession.close();
            projectionSession = null;
        }
        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
            imageReader = null;
        }
    }

    @Override
    public void onDestroy() {
        boolean cancelled = !finished;
        finished = true;
        handler.removeCallbacksAndMessages(null);
        releaseCapture();
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
        active = false;
        if (instance == this) {
            instance = null;
        }
        if (cancelled) {
            ProbeState.fail("本轮采集已停止，尚未形成结果。");
            if (continuous) {
                Log.i("CaptureProbe", "STOP reason=DESTROYED frames=" + frameNumber
                        + " seen=" + String.join(",", seenSampleCodes));
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
