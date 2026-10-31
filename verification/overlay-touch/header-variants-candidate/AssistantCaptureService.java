package com.codextemp.wechatcaptureprobe;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class AssistantCaptureService extends Service {
    static final String STOP = "com.codextemp.wechatcaptureprobe.ASSISTANT_STOP";
    private static volatile boolean active;
    private static volatile AssistantCaptureService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MessageTracker tracker = new MessageTracker();
    private final ConversationSession session = new ConversationSession();
    private final HeaderRecognitionCache headerCache = new HeaderRecognitionCache();
    private final ScopeFramePreparation firstFrame = new ScopeFramePreparation();
    private final java.util.concurrent.ExecutorService resultGeometryWorker = java.util.concurrent.Executors.newSingleThreadExecutor(
            task -> new Thread(task, "assistant-result-geometry"));
    private final FreshGeometryRead resultGeometry = new FreshGeometryRead(resultGeometryWorker,
            task -> handler.post(task), SystemClock::elapsedRealtime, () -> ScreenGeometryReader.read(this));
    private ProjectionSession projectionSession;
    private ImageReader reader;
    private TextRecognizer recognizer;
    private BubbleOcr bubbleOcr;
    private Image latest;
    private volatile OverlayOcclusion readingOverlay;
    private OverlayOcclusion imageOverlay;
    private OverlayOcclusion processingOverlay;
    private ChatViewport processingViewport;
    private int[] processingPixels;
    private int[] processingHeaderPixels;
    private final java.util.List<ChatViewport.Bounds> ownMaskHistory = new ArrayList<>();
    private boolean ownMaskOverflow;
    private final FrameFingerprintCache fingerprints = new FrameFingerprintCache();
    private long arrivedAt;
    private long readerRequestedAt;
    private boolean busy;
    private boolean frameHandled;
    private volatile boolean stopped;
    private long candidateHash = Long.MIN_VALUE;
    private long processedHash = Long.MIN_VALUE;
    private long changedAt;
    private long lastOcrAt;
    private DisplayMetrics metrics;
    private int statusHeight;
    private int navigationHeight;
    private int frameCount;
    private String lastPause = "";
    private String lastLayout = "";
    private boolean layoutUncertain;
    private ConversationRulesStore rules;
    private long rulesRevision;
    private AnalysisCoordinator analysis;
    private Consumer<ComposerProof> composerCallback;
    private AssistantState.Snapshot composerTarget;
    private long composerRequestedAt;
    private long composerRequest;
    private ScreenGeometry geometry;
    private DisplayManager displayManager;
    private long geometryEnteredAt;
    private boolean regionAligned = Build.VERSION.SDK_INT < 34;
    private boolean regionVisible = true;
    private boolean geometryUnavailable;
    private long viewportGeneration = Long.MIN_VALUE;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                stopAssistant("主显示已移除，请重新开启辅助");
            }
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY && !stopped) {
                reconcileGeometry();
            }
        }
    };

    static final class ComposerProof {
        final AssistantState.Snapshot target;
        final Rect bounds;
        final long createdAt;

        ComposerProof(AssistantState.Snapshot target, Rect bounds) {
            this.target = target;
            this.bounds = new Rect(bounds);
            createdAt = SystemClock.elapsedRealtime();
        }
    }

    static void requestComposer(AssistantState.Snapshot target, Consumer<ComposerProof> callback) {
        AssistantCaptureService service = instance;
        if (service == null || service.stopped || !service.reconcileGeometry() || !service.geometryCurrent() || !AssistantState.matches(target)) {
            callback.accept(null);
            return;
        }
        service.deliverComposer(null);
        service.discardLatest();
        Image queued = service.reader.acquireLatestImage();
        if (queued != null) {
            queued.close();
        }
        service.composerTarget = target;
        service.composerRequestedAt = SystemClock.elapsedRealtime();
        service.composerCallback = callback;
        long request = ++service.composerRequest;
        service.handler.postDelayed(() -> {
            if (service.composerRequest == request) {
                service.deliverComposer(null);
            }
        }, 1500);
    }

    static boolean proofCurrent(ComposerProof proof) {
        ChatGateService.Snapshot gate = ChatGateService.verify();
        AssistantCaptureService service = instance;
        return proof != null && service != null && !service.stopped && active
                && service.geometryCurrent()
                && SystemClock.elapsedRealtime() - proof.createdAt < 1200
                && AssistantState.matches(proof.target) && gate.valid() && gate.generation == proof.target.generation
                && !service.rules.excludes(proof.target.title);
    }

    static BooleanSupplier composerPermission(ComposerProof proof) {
        if (Looper.myLooper() != Looper.getMainLooper() || !proofCurrent(proof)) return () -> false;
        AssistantCaptureService service = instance;
        if (service == null || service.reader == null || service.projectionSession == null || service.geometry == null) return () -> false;
        Surface surface = service.reader.getSurface();
        ProjectionSession owner = service.projectionSession;
        long version = owner.version();
        ScreenGeometry shape = service.geometry;
        ConversationRulesStore rules = service.rules;
        long policy = rules.revision();
        return () -> {
            ChatGateService.Snapshot gate = ChatGateService.snapshot();
            long now = SystemClock.elapsedRealtime();
            return instance == service && active && !service.stopped && surface.isValid()
                    && owner.version() == version && owner.ready()
                    && now >= proof.createdAt && now - proof.createdAt < 1200
                    && gate.valid() && gate.generation == proof.target.generation && gate.viewport != null
                    && gate.viewport.matchesScreen(shape) && shape.same(ScreenGeometryReader.read(service))
                    && AssistantState.matches(proof.target) && rules.revision() == policy && !rules.excludes(proof.target.title)
                    && owner.version() == version;
        };
    }
    private long nextTickAt = -1;
    private long pendingResumeGeneration = -1;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            nextTickAt = -1;
            if (!stopped) {
                if (bubbleOcr != null) bubbleOcr.pruneCache();
                inspectFrame();
                if (analysis != null) {
                    analysis.poll();
                }
                scheduleTick(SystemClock.elapsedRealtime() + 200);
            }
        }
    };

    /** 新帧/识别完成共用一个唤醒；只提前检查，所有来源和稳定时间门槛仍在 inspectFrame。 */
    private void scheduleTick(long due) {
        if (stopped || nextTickAt >= 0 && nextTickAt <= due) return;
        handler.removeCallbacks(tick);
        nextTickAt = due;
        handler.postDelayed(tick, Math.max(0, due - SystemClock.elapsedRealtime()));
    }

    static boolean running() {
        return active;
    }

    static boolean analysisScopeCurrent(AssistantState.Snapshot target) {
        AssistantCaptureService service = instance;
        if (service == null) return false;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // 自有View只能在主线程核验；模型工作线程等待，主线程不等待远端节点。
            java.util.concurrent.CompletableFuture<OverlayOcclusion> result = new java.util.concurrent.CompletableFuture<>();
            service.handler.post(() -> {
                if (!result.isDone()) result.complete(analysisScopeCurrent(target) ? service.readingOverlay : null);
            });
            try {
                OverlayOcclusion evidence = result.get(350, java.util.concurrent.TimeUnit.MILLISECONDS);
                ChatGateService.Snapshot current = ChatGateService.snapshot();
                // 主线程返回的核验不是跨线程许可；消费时重新检查租约和全部已发布的版本。
                return evidence != null && instance == service && active && !service.stopped
                        && service.readingOverlay == evidence && ChatGateService.publishedCaptureOcclusion() == evidence
                        && current.generation == target.generation && AssistantState.matches(target) && current.valid();
            } catch (InterruptedException failure) { Thread.currentThread().interrupt(); return false; }
            catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) { return false; }
            finally { result.cancel(false); }
        }
        ChatGateService.Snapshot gate = ChatGateService.verify();
        if (!active || service == null || !service.geometryCurrent() || !AssistantState.matches(target)
                || !gate.valid() || gate.generation != target.generation) return false;
        ChatViewport viewport = service.readingViewport(gate);
        return viewport != null && AssistantState.matches(target) && gate.valid()
                && target.coverage.maskedWindows == viewport.masks.size();
    }

    static void stopCurrent() {
        if (instance != null) {
            instance.stopAssistant("已主动停止辅助");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            stopAssistant("已停止辅助");
            return START_NOT_STICKY;
        }
        if (active) {
            return START_NOT_STICKY;
        }
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel("assistant", "微信辅助运行状态", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 20, new Intent(this, AssistantActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 21,
                new Intent(this, AssistantCaptureService.class).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "assistant")
                .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("微信聊天辅助已开启")
                .setContentText("仅当前微信聊天；云端按独立设置，默认关闭；停止可结束共享")
                .setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "停止辅助", stop).build())
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(10, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(10, notification);
            }
            if (CaptureService.running()) {
                stopAssistant("诊断采集仍在运行，已拒绝并行开启；请先停止所有采集");
                return START_NOT_STICKY;
            }
            if (!ProjectionSession.available()) {
                stopAssistant("上次共享仍在清理，未开启新采集；请稍候重新开启");
                return START_NOT_STICKY;
            }
            active = true;
            instance = this;
            ProbeState.clear();
            AssistantState.clear();
            AssistantState.paused = false;
            if (!ChatGateService.enabled()) {
                throw new IllegalStateException("Gate unavailable");
            }
            int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
            Intent grant = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra("captureData", Intent.class)
                    : intent.getParcelableExtra("captureData");
            if (resultCode != Activity.RESULT_OK || grant == null) {
                throw new IllegalStateException("Grant unavailable");
            }
            applyGeometry(ScreenGeometryReader.read(this));
            geometryEnteredAt = SystemClock.elapsedRealtime();
            Log.i("ChatAssistant", "DISPLAY width=" + metrics.widthPixels + " height=" + metrics.heightPixels
                    + " density=" + metrics.density + " status=" + statusHeight + " navigation=" + navigationHeight);
            recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            bubbleOcr = new BubbleOcr(recognizer);
            rules = new ConversationRulesStore(this);
            rulesRevision = rules.revision();
            analysis = new AnalysisCoordinator(this);
            displayManager = getSystemService(DisplayManager.class);
            displayManager.registerDisplayListener(displayListener, handler);
            projectionSession = new ProjectionSession(this, resultCode, grant, "WechatAssistant", geometry, null,
                    10, notification, new ProjectionSession.Listener() {
                        @Override public void ready() {
                            if (!stopped) {
                                scheduleTick(SystemClock.elapsedRealtime());
                            }
                        }
                        @Override public void stopped(String reason) {
                            stopAssistant(reason);
                        }
                        @Override public void source(int width, int height, boolean visible) {
                            if (stopped) {
                                return;
                            }
                            if (Build.VERSION.SDK_INT >= 34 && width > 0 && height > 0) {
                                boolean aligned = geometry != null && width == geometry.width && height == geometry.height;
                                if (regionAligned != aligned) {
                                    regionAligned = aligned;
                                    resetGeometryScope("共享区域变化，重新确认当前聊天");
                                }
                                if (!aligned) {
                                    AssistantState.status = "共享区域与屏幕坐标未对齐，已暂停识别与填入；单应用共享尚待适配";
                                }
                            }
                            if (regionVisible != visible) {
                                regionVisible = visible;
                                resetGeometryScope(visible ? "共享源已恢复可见，重新确认当前聊天" : "共享源不可见，已暂停识别与填入");
                            }
                        }
                    });
            AssistantState.status = "已开启，等待微信聊天页";
            Log.i("ChatAssistant", "START foregroundGate=on contentLog=off");
        } catch (RuntimeException exception) {
            stopAssistant("无法启动辅助：" + exception.getClass().getSimpleName());
        }
        return START_NOT_STICKY;
    }

    private void inspectFrame() {
        ChatGateService.Snapshot gate = ChatGateService.snapshot();
        if (!gate.valid() || AssistantState.paused) {
            reconcileViewport(gate);
            suspendOutput();
            pauseFrameProcessing(gate);
            return;
        }
        if (!reconcileGeometry()) {
            suspendOutput();
            return;
        }
        if (rules.revision() != rulesRevision) {
            rulesRevision = rules.revision();
            AssistantState.invalidateScope("排除规则变化，重新确认当前标题");
            AssistantState.expanded = false;
            tracker.clear();
            bubbleOcr.clearCache();
            session.close(); headerCache.clear();
            processedHash = Long.MIN_VALUE;
            candidateHash = Long.MIN_VALUE;
        }
        gate = ChatGateService.verify();
        reconcileViewport(gate);
        if (!gate.valid() || AssistantState.paused) {
            suspendOutput();
            pauseFrameProcessing(gate);
            return;
        }
        if (!regionAligned || !regionVisible) {
            discardLatest();
            return;
        }
        if (!resumeOutput(gate)) {
            // 同一新 Reader 的帧可能先于投射回执到达；仅等待，不识别或改写其到达时间。
            return;
        }
        if (readingViewport(gate) == null) return;
        lastPause = "";
        if (refreshMissingFrame(gate)) return;
        if (busy || latest == null || arrivedAt < gate.enteredAt + 250
                || arrivedAt < geometryEnteredAt + 350) {
            return;
        }
        if (SystemClock.elapsedRealtime() < lastOcrAt + 600) {
            scheduleTick(lastOcrAt + 600);
            return;
        }
        observeScope(latest, gate);
        ChatViewport viewport = readingViewport(gate);
        if (latest == null || viewport == null || imageOverlay != readingOverlay || !gate.valid()) return;
        Image.Plane plane = latest.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int width = viewport.width();
        int height = viewport.height();
        if (pixelStride != 4 || latest.getWidth() != metrics.widthPixels || latest.getHeight() != metrics.heightPixels) {
            stopAssistant("显示尺寸改变，请重新开启辅助");
            return;
        }
        ChatLayout.Pixels source = (horizontal, vertical) -> {
            if (viewport.masked(horizontal, vertical)) {
                return 0xff777777;
            }
            int offset = (vertical + viewport.bounds.top) * rowStride + (horizontal + viewport.bounds.left) * pixelStride;
            return 0xff000000 | (buffer.get(offset) & 255) << 16
                    | (buffer.get(offset + 1) & 255) << 8 | (buffer.get(offset + 2) & 255);
        };
        if (FrameQuality.isMostlyBlack(width, height, source::colorAt)) {
            stopAssistant("主要区域为黑图，已停止；不尝试绕过保护");
            return;
        }
        ChatLayout layout = ChatLayout.detect(width, height, metrics.density, 0, 0, -1, source);
        String layoutState = (layout == null ? "UNKNOWN" : "CHAT") + ":" + gate.keyboardTop;
        if (!layoutState.equals(lastLayout)) {
            Log.i("ChatAssistant", "LAYOUT state=" + layoutState);
            lastLayout = layoutState;
        }
        if (layout == null) {
            if (!layoutUncertain) {
                AssistantState.invalidateScope("已暂停：无法确认聊天输入区，请切回文字聊天");
                session.suspend(); headerCache.clear();
                tracker.clear();
                // 键盘动画可短暂破坏输入区形状；仅隔离计算缓存，恢复仍须新图和双帧确认。
                bubbleOcr.quarantineCache();
            }
            layoutUncertain = true;
            processedHash = Long.MIN_VALUE;
            return;
        }
        layoutUncertain = false;
        long now = SystemClock.elapsedRealtime();
        if (candidateHash == processedHash) return;
        boolean headerOnly = now >= changedAt && now - changedAt < 350 && session.claimInitialHeader(gate.generation);
        if (now - changedAt < 350 && !headerOnly) {
            // 周期检查可以提前发生，但不能丢掉已知的稳定截止时间而再多等一轮。
            scheduleTick(changedAt + 350);
            return;
        }
        // 标题准备帧不进入正文；保留原坐标原点，只复制标题所需前缀，避免无用的整页转换。
        int copyHeight = headerOnly ? layout.contentTop : height;
        long copyStarted = SystemClock.elapsedRealtime();
        Bitmap image = Bitmap.createBitmap(width, copyHeight, Bitmap.Config.ARGB_8888);
        int[] copiedPixels = new int[width * copyHeight];
        ByteBuffer copyBuffer = buffer.duplicate();
        try {
            if (rowStride % 4 == 0) {
                copyBuffer.position(0);
                java.nio.IntBuffer words = copyBuffer.order(java.nio.ByteOrder.BIG_ENDIAN).asIntBuffer();
                MaskedRgbaWords rows = new MaskedRgbaWords(viewport, rowStride, copyBuffer.limit(),
                        (offset, target, targetOffset, count) -> {
                            words.position(offset / 4);
                            words.get(target, targetOffset, count);
                        });
                for (int vertical = 0; vertical < copyHeight; vertical++)
                    rows.copyRow(vertical, copiedPixels, vertical * width);
            } else {
                // 非整像素行跨度保留原字节路径，不能因为快速路径限制拒绝原本可处理的帧。
                int[] row = new int[width];
                MaskedRgbaRows rows = new MaskedRgbaRows(viewport, rowStride, copyBuffer.limit(),
                        (offset, target, targetOffset, length) -> {
                            copyBuffer.position(offset);
                            copyBuffer.get(target, targetOffset, length);
                        });
                for (int vertical = 0; vertical < copyHeight; vertical++) {
                    rows.copyRow(vertical, row);
                    System.arraycopy(row, 0, copiedPixels, vertical * width, width);
                }
            }
            image.setPixels(copiedPixels, 0, width, 0, 0, width, copyHeight);
        } catch (RuntimeException failure) {
            image.recycle();
            stopAssistant("画面复制失败，辅助已停止");
            return;
        }
        processingOverlay = readingOverlay;
        processingViewport = viewport;
        processingPixels = copiedPixels;
        discardLatest();
        busy = true;
        frameHandled = false;
        lastOcrAt = now;
        processedHash = candidateHash;
        frameCount++;
        Log.i("ChatAssistant", "FRAME_COPY frame=" + frameCount + " headerOnly=" + headerOnly
                + " copiedPixels=" + copiedPixels.length + " fullPixels=" + ((long) width * height)
                + " copyMs=" + (SystemClock.elapsedRealtime() - copyStarted));
        if (headerOnly) Log.i("ChatAssistant", "EARLY_HEADER frame=" + frameCount + " bodyWaitMs=" + (changedAt + 350 - now));
        recognizeHeader(image, layout, gate, now, changedAt, frameCount, session.ticket(), AssistantState.policyVersion, headerOnly);
        int sequence = frameCount;
        handler.postDelayed(() -> {
            if (busy && frameCount == sequence) {
                stopAssistant("识别超时，辅助已停止");
            }
        }, 10000);
    }

    private void inspectComposer(Image image, ChatGateService.Snapshot gate) {
        if (composerCallback == null || arrivedAt < composerRequestedAt) {
            return;
        }
        ComposerProof proof = null;
        if (!stopped && geometryCurrent() && gate.valid() && composerTarget != null && AssistantState.matches(composerTarget)
                && gate.generation == composerTarget.generation && !rules.excludes(composerTarget.title)
                && image.getWidth() == metrics.widthPixels && image.getHeight() == metrics.heightPixels
                && image.getPlanes()[0].getPixelStride() == 4) {
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer pixels = plane.getBuffer();
            int stride = plane.getRowStride();
            ChatViewport viewport = gate.viewport;
            ChatLayout layout = ChatLayout.detect(viewport.width(), viewport.height(), metrics.density,
                    0, 0, -1, (horizontal, vertical) -> {
                        int offset = (vertical + viewport.bounds.top) * stride + (horizontal + viewport.bounds.left) * 4;
                        return 0xff000000 | (pixels.get(offset) & 255) << 16
                                | (pixels.get(offset + 1) & 255) << 8 | (pixels.get(offset + 2) & 255);
                    });
            if (layout != null) {
                proof = new ComposerProof(composerTarget, new Rect(viewport.bounds.left + geometry.dp(40),
                        viewport.bounds.top + layout.contentBottom, viewport.bounds.right - geometry.dp(48), viewport.bounds.bottom));
            }
        }
        ComposerProof result = proof;
        long request = composerRequest;
        handler.post(() -> {
            if (composerRequest == request) {
                deliverComposer(proofCurrent(result) ? result : null);
            }
        });
    }

    private void deliverComposer(ComposerProof proof) {
        Consumer<ComposerProof> callback = composerCallback;
        composerCallback = null;
        composerTarget = null;
        composerRequest++;
        if (callback != null) {
            callback.accept(proof);
        }
    }

    /** SDK已经结束后才转交图像所有权；每次续接单独读取新几何，禁止跨阶段复用。 */
    private void withFreshGeometry(Bitmap image, ChatGateService.Snapshot gate, long ticket, long policy, Runnable continuation) {
        if (!geometryReady()) {
            finishImage(image);
            return;
        }
        ImageReader expectedReader = reader;
        ProjectionSession expectedOwner = projectionSession;
        long expectedVersion = expectedOwner.version();
        ScreenGeometry expectedGeometry = geometry;
        boolean accepted = resultGeometry.request(reading -> {
            if (reading == null || !geometryReady() || reader != expectedReader || projectionSession != expectedOwner
                    || expectedOwner.version() != expectedVersion || geometry != expectedGeometry
                    || !expectedGeometry.same(reading.geometry) || !validScope(gate, ticket, policy)
                    || !geometryReady() || reader != expectedReader || projectionSession != expectedOwner
                    || expectedOwner.version() != expectedVersion || geometry != expectedGeometry
                    || !reading.fresh(SystemClock.elapsedRealtime())) {
                finishImage(image);
                return;
            }
            continuation.run();
        });
        if (!accepted) finishImage(image);
    }

    private void recognizeHeader(Bitmap image, ChatLayout layout, ChatGateService.Snapshot gate, long started,
                                 long frameChangedAt, int sequence, long ticket, long policy, boolean headerOnly) {
        long headerStarted = SystemClock.elapsedRealtime();
        int left=image.getWidth()*15/100, top=layout.headerTop;
        int width=image.getWidth()*70/100, height=layout.contentTop-layout.headerTop;
        int[] pixels=new int[width*height];
        processingHeaderPixels=pixels;
        for(int row=0;row<height;row++)
            System.arraycopy(processingPixels,(top+row)*image.getWidth()+left,pixels,row*width,width);
        boolean lookedUp=!headerOnly&&session.confirmed(ticket,gate.generation);
        String cached=!lookedUp?null:headerCache.get(ticket,gate.generation,rulesRevision,left,top,width,height,pixels);
        if(cached!=null) {
            acceptHeader(image,layout,gate,started,frameChangedAt,sequence,ticket,policy,false,
                    headerStarted,0,cached,left,top,width,height,pixels,true);
            return;
        }
        Bitmap header = Bitmap.createBitmap(image, left, top, width, height);
        OcrCallbackClock headerClock = new OcrCallbackClock();
        headerClock.watch(recognizer.process(InputImage.fromBitmap(header, 0))).addOnSuccessListener(result -> {
            long headerQueueMs = headerClock.queueMs();
            header.recycle();
            String title = result.getText().replace('\n', ' ').trim();
            acceptHeader(image,layout,gate,started,frameChangedAt,sequence,ticket,policy,headerOnly,
                    headerStarted,headerQueueMs,title,left,top,width,height,pixels,false);
        }).addOnFailureListener(exception -> {
            header.recycle();
            headerCache.clear();
            finishImage(image);
            stopAssistant("标题识别失败，辅助已停止");
        });
    }

    /** 缓存只省去SDK计算；本帧几何、会话确认及排除规则仍走同一入口。 */
    private void acceptHeader(Bitmap image, ChatLayout layout, ChatGateService.Snapshot gate, long started,
                              long frameChangedAt,int sequence,long ticket,long policy,boolean headerOnly,
                              long headerStarted,long headerQueueMs,String title,int left,int top,int width,int height,
                              int[] pixels,boolean cached) {
        withFreshGeometry(image, gate, ticket, policy, () -> {
            String identity = ConversationSession.normalize(title);
            if (identity.isEmpty() || identity.length() > 80) {
                headerCache.clear();
                bubbleOcr.clearCache();
                AssistantState.invalidateScope("无法可靠识别聊天标题，已暂停正文处理");
                finishImage(image);
                processedHash = Long.MIN_VALUE;
                return;
            }
            boolean confirmed = session.confirm(ticket, title);
            // 提前帧只做首张标题确认，即使状态意外已确认也不能把未稳定正文交给OCR。
            if (!confirmed || headerOnly) {
                headerCache.clear();
                bubbleOcr.quarantineCache();
                AssistantState.invalidate("正在确认当前聊天，旧会话内容已清空");
                tracker.clear();
                finishImage(image);
                processedHash = Long.MIN_VALUE;
                return;
            }
            if (rules.excludes(title)) {
                headerCache.clear();
                AssistantState.block(gate.generation, title, session.token());
                tracker.clear();
                bubbleOcr.clearCache();
                frameHandled = true;
                finishImage(image);
                return;
            }
            if(!cached)headerCache.put(ticket,gate.generation,rulesRevision,left,top,width,height,pixels,title);
            Log.i("ChatAssistant","HEADER_REUSE frame="+sequence+" hit="+cached);
            recognizeBody(image, layout, gate, started, frameChangedAt, sequence, title, ticket, policy,
                    SystemClock.elapsedRealtime() - headerStarted, headerQueueMs);
        });
    }
    private void recognizeBody(Bitmap image, ChatLayout layout, ChatGateService.Snapshot gate, long started,
                               long frameChangedAt, int sequence, String title, long ticket, long policy, long headerMs, long headerQueueMs) {
        long bodyStarted = SystemClock.elapsedRealtime();
        // 复用本次识别的已遮蔽像素；完成时释放，绝不跨帧当作新画面。
        int width = image.getWidth();
        int[] classifiedPixels = processingPixels;
        ChatLayout classified = layout.withBubbles(width, metrics.density,
                (horizontal, vertical) -> classifiedPixels[vertical * width + horizontal]);
        List<ChatLayout.Box> visible = new ArrayList<>();
        for (ChatLayout.Box box : classified.bubbles) {
            if (!processingViewport.occludes(box)) {
                visible.add(box);
            }
        }
        long classifyMs = SystemClock.elapsedRealtime() - bodyStarted;
        bubbleOcr.read(image, visible, metrics.density, () -> valid(gate, ticket, policy), new BubbleOcr.Callback() {
            @Override
            public void success(List<ChatMessage> messages, BubbleOcr.Stats stats) {
                withFreshGeometry(image, gate, ticket, policy, () -> {
                    String token = session.token();
                    MessageTracker.Update increment = tracker.update(token, messages);
                    long completed = SystemClock.elapsedRealtime();
                    long elapsed = completed - started;
                    long bodyMs = completed - bodyStarted;
                    AssistantState.publish(gate.generation, title, token, messages, elapsed, increment,
                            new CaptureCoverage(processingViewport.masks.size(), classified.bubbles.size() - visible.size(), true),
                            new AssistantState.CaptureTiming(frameChangedAt, started, completed));
                    frameHandled = true;
                    if (AssistantState.coverage.obscured()) {
                        AssistantState.status = "正在识别可见聊天；浮窗附近可能漏字或截断，请核对原文。局部内容默认不上传分析";
                    }
                    long uncertain = messages.stream().filter(message -> message.uncertain).count();
                    StringBuilder sampleText = new StringBuilder();
                    for (ChatMessage message : messages) {
                        sampleText.append(message.text).append('\n');
                    }
                    Log.i("ChatAssistant", "FRAME number=" + sequence + " messages=" + messages.size()
                            + " visibleAdditions=" + increment.additions + " increment=" + increment.kind
                            + " uncertain=" + uncertain + " ocrMs=" + elapsed
                            + " cacheHits=" + stats.cacheHits + " fresh=" + stats.freshBubbles
                            + " latinCalls=" + stats.latinCalls + " graphics=" + stats.graphics
                            + " headerMs=" + headerMs + " bodyMs=" + bodyMs
                            + " classifyMs=" + classifyMs + " prepareMs=" + stats.prepareMs
                            + " chineseWallMs=" + stats.chineseWallMs + " latinWallMs=" + stats.latinWallMs
                            + " headerQueueMs=" + headerQueueMs + " chineseQueueMs=" + stats.chineseQueueMs
                            + " latinQueueMs=" + stats.latinQueueMs
                            + " validationCalls=" + stats.validationCalls + " validationMs=" + stats.validationMs
                            + " samples=" + String.join(",", ConversationSamples.matchedCodes(sampleText.toString())));
                    finishImage(image);
                    });
            }

            @Override
            public void cancelled() {
                finishImage(image);
            }

            @Override
            public void failure() {
                finishImage(image);
                stopAssistant("正文识别失败，辅助已停止");
            }
        });
    }

    private boolean valid(ChatGateService.Snapshot gate, long ticket, long policy) {
        if (!geometryCurrent()) {
            return false;
        }
        return validScope(gate, ticket, policy);
    }

    /** 仍须主线程核对新帧/遮挡与会话；调用方另提供本次新几何读取。 */
    private boolean validScope(ChatGateService.Snapshot gate, long ticket, long policy) {
        ChatGateService.Snapshot now = ChatGateService.verify();
        if (latest != null && now.valid() && !AssistantState.paused) {
            observeScope(latest, now);
        }
        boolean accepted = !stopped && !AssistantState.paused
                && processingOverlay != null && processingOverlay == ChatGateService.captureOcclusion()
                && now.valid() && gate.generation == now.generation
                && session.current(ticket, now.generation) && policy == AssistantState.policyVersion;
        if (!accepted) {
            Log.i("ChatAssistant", "DROP_RESULT sessionCurrent=" + session.current(ticket, now.generation)
                    + " policyCurrent=" + (policy == AssistantState.policyVersion) + " gateCurrent=" + now.valid());
        }
        return accepted;
    }

    private void observeScope(Image image, ChatGateService.Snapshot gate) {
        if (image.getWidth() != metrics.widthPixels || image.getHeight() != metrics.heightPixels
                || image.getPlanes()[0].getPixelStride() != 4) {
            stopAssistant("显示尺寸改变，请重新开启辅助");
            return;
        }
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer pixels = plane.getBuffer();
        int stride = plane.getRowStride();
        ChatViewport viewport = readingViewport(gate);
        if (viewport == null || image != latest || imageOverlay != readingOverlay) return;
        if (!gate.valid() || viewportGeneration != gate.generation || arrivedAt < gate.enteredAt + 250) {
            return;
        }
        int contentTop = Math.round(48 * metrics.density);
        int contentBottom = Math.max(contentTop, viewport.height() - Math.round(56 * metrics.density));
        fingerprints.prepare(image, viewport,
                () -> fingerprint(pixels, stride, Math.round(6 * metrics.density),
                        contentTop - Math.round(6 * metrics.density), 4, viewport),
                () -> fingerprint(pixels, stride, contentTop, contentBottom, 12, viewport));
        long headerHash = fingerprints.header();
        if (session.observe(gate.generation, headerHash)) {
            headerCache.clear();
            tracker.clear();
            if (bubbleOcr != null) {
                bubbleOcr.quarantineCache();
            }
            processedHash = Long.MIN_VALUE;
            AssistantState.invalidateScope("页面或标题变化，已清空旧结果，重新确认会话");
            Log.i("ChatAssistant", "SCOPE_CHANGED ticket=" + session.ticket());
        }
        long bodyHash = fingerprints.body();
        if (bodyHash != candidateHash) {
            candidateHash = bodyHash;
            processedHash = Long.MIN_VALUE;
            changedAt = SystemClock.elapsedRealtime();
            AssistantState.invalidate("可见内容变化，旧结果已清空，等待稳定识别");
        }
    }

    private long fingerprint(ByteBuffer pixels, int stride, int top, int bottom, int step, ChatViewport viewport) {
        long hash = 1469598103934665603L;
        for (int vertical = top; vertical < bottom; vertical += step) {
            for (int horizontal = viewport.width() * 15 / 100;
                 horizontal < viewport.width() * 85 / 100; horizontal += step) {
                int offset = (vertical + viewport.bounds.top) * stride + (horizontal + viewport.bounds.left) * 4;
                int color = viewport.masked(horizontal, vertical) ? 0x777777 : (pixels.get(offset) & 255) << 16 | (pixels.get(offset + 1) & 255) << 8
                        | (pixels.get(offset + 2) & 255);
                hash = (hash ^ color) * 1099511628211L;
            }
        }
        return hash;
    }

    private void clearHeaderPixels() {
        if(processingHeaderPixels!=null)java.util.Arrays.fill(processingHeaderPixels,0);
        processingHeaderPixels=null;
    }

    private void finishImage(Bitmap image) {
        clearHeaderPixels();
        image.recycle();
        busy = false;
        if (!frameHandled) {
            processedHash = Long.MIN_VALUE;
        }
        frameHandled = false;
        processingOverlay = null;
        processingViewport = null;
        processingPixels = null;
        if (analysis != null && !stopped) analysis.poll();
        scheduleTick(SystemClock.elapsedRealtime() + 16);
    }

    private void discardLatest() {
        fingerprints.clear();
        imageOverlay = null;
        if (latest != null) {
            latest.close();
            latest = null;
        }
    }

    private void pauseFrameProcessing(ChatGateService.Snapshot gate) {
        // 用户可在范围已失效期间再暂停；清理不能依赖暂停原因字符串发生变化。
        if (AssistantState.paused) bubbleOcr.clearCache();
        discardLatest();
        processedHash = Long.MIN_VALUE;
        candidateHash = Long.MIN_VALUE;
        String reason = !gate.valid() ? "OUTSIDE_WECHAT" : AssistantState.paused ? "USER_PAUSE" : "CARD_OPEN";
        if (!reason.equals(lastPause)) {
            if (!gate.valid() || AssistantState.paused) {
                session.close(); headerCache.clear();
                AssistantState.clearContext("范围暂停，本轮背景已解除");
                tracker.clear();
                if (!AssistantState.paused) bubbleOcr.quarantineCache();
            }
            Log.i("ChatAssistant", "PAUSE reason=" + reason + " frames=" + frameCount);
            lastPause = reason;
        }
    }

    private void suspendOutput() {
        pendingResumeGeneration = -1;
        ImageReader previous = reader;
        if (previous == null) {
            return;
        }
        reader = null;
        resetGeometryScope("画面输出已暂停；返回微信或继续后重新确认，结束共享请点停止");
        previous.setOnImageAvailableListener(null, null);
        try {
            projectionSession.update(geometry, null);
            Log.i("ChatAssistant", "OUTPUT state=PAUSE_REQUESTED queue=closed");
        } catch (RuntimeException exception) {
            stopAssistant("暂停画面输出失败，请重新开启辅助");
        } finally {
            previous.close();
        }
    }

    private boolean resumeOutput(ChatGateService.Snapshot gate) {
        if (reader != null) {
            pendingResumeGeneration = -1;
            return projectionSession.ready();
        }
        // 每次恢复只重置一次；太早创建的首帧会被稳定门槛拒绝，静止页面可能不再补帧。
        if (pendingResumeGeneration != gate.generation) {
            resetGeometryScope("画面输出准备恢复，重新确认当前微信聊天");
            pendingResumeGeneration = gate.generation;
        }
        OverlayOcclusion overlay = ChatGateService.captureOcclusion();
        if (overlay == null || !overlay.known || gate.viewport == null
                || gate.viewport.withOverlay(overlay.bounds) == null) return false;
        long stableAt = Math.max(gate.enteredAt + 250,
                Math.max(geometryEnteredAt + 350, overlay.changedAt + 350));
        if (SystemClock.elapsedRealtime() < stableAt) {
            scheduleTick(stableAt);
            return false;
        }
        if (stopped || AssistantState.paused || !regionAligned || !regionVisible
                || projectionSession == null || !projectionSession.ready() || !gate.valid()) return false;
        ImageReader replacement = null;
        try {
            firstFrame.consume(gate.generation);
            replacement = createReader(geometry);
            // 分配与几何读取可能消耗租约；提交输出前复核，定时器本身不是来源证明。
            boolean currentGeometry = geometry != null && geometry.supported()
                    && geometry.same(ScreenGeometryReader.read(this));
            OverlayOcclusion currentOverlay = ChatGateService.captureOcclusion();
            ChatGateService.Snapshot current = ChatGateService.snapshot();
            if (!currentGeometry || currentOverlay != overlay || current.generation != gate.generation
                    || pendingResumeGeneration != current.generation || stopped || AssistantState.paused
                    || !regionAligned || !regionVisible || !current.valid()) {
                replacement.setOnImageAvailableListener(null, null);
                replacement.close();
                pendingResumeGeneration = -1;
                return false;
            }
            reader = replacement;
            pendingResumeGeneration = -1;
            projectionSession.update(geometry, replacement.getSurface());
            Log.i("ChatAssistant", "OUTPUT state=RESUME_REQUESTED queue=fresh");
            return false;
        } catch (RuntimeException exception) {
            reader = null;
            if (replacement != null) {
                replacement.close();
            }
            stopAssistant("恢复画面输出失败，请重新开启辅助");
            return false;
        }
    }

    private ImageReader createReader(ScreenGeometry screen) {
        readerRequestedAt = SystemClock.elapsedRealtime();
        ImageReader result = ImageReader.newInstance(screen.width, screen.height, PixelFormat.RGBA_8888, 3);
        result.setOnImageAvailableListener(source -> {
            if (stopped || source != reader) {
                return;
            }
            Image image = source.acquireLatestImage();
            if (image == null) {
                return;
            }
            if (projectionSession == null) {
                image.close();
                return;
            }
            discardLatest();
            latest = image;
            imageOverlay = ChatGateService.captureOcclusion();
            arrivedAt = SystemClock.elapsedRealtime();
            if (!regionAligned || !regionVisible || geometry == null || !geometry.supported()) {
                discardLatest();
                return;
            }
            // 帧先于 IPC 回执到达时只保留在内存；后续仍经过 ready、范围和稳定时间检查。
            if (!projectionSession.ready()) return;
            ChatGateService.Snapshot gate = ChatGateService.verify();
            reconcileViewport(gate);
            if (latest == null) {
                return;
            }
            if (!gate.valid() || AssistantState.paused) {
                suspendOutput();
                return;
            }
            if (gate.valid() && arrivedAt >= gate.enteredAt + 250 && !AssistantState.paused) {
                observeScope(image, gate);
            }
            if (latest != image) return;
            inspectComposer(image, gate);
            if (!busy && candidateHash != processedHash) {
                // 首标题无需等正文稳定；仅提前唤醒，inspectFrame仍独立检查全部门槛并领取一次额度。
                boolean firstTitleReady = session.canPrepareInitialHeader(gate.generation)
                        && arrivedAt >= geometryEnteredAt + 350 && imageOverlay != null && imageOverlay.known
                        && arrivedAt >= imageOverlay.changedAt + 350;
                long contentDue = firstTitleReady ? 0 : changedAt + 350;
                scheduleTick(Math.max(SystemClock.elapsedRealtime() + 16,
                        Math.max(lastOcrAt + 600, contentDue)));
            }
            if (!gate.valid() || AssistantState.paused) {
                discardLatest();
            }
        }, handler);
        return result;
    }

    /** 保持原稳定门槛，替换停滞的输出队列来请求真实新帧；不重置会话或伪造到达时间。 */
    private boolean refreshMissingFrame(ChatGateService.Snapshot gate) {
        boolean missing = latest == null ? processedHash == Long.MIN_VALUE
                : arrivedAt < gate.enteredAt + 250 || arrivedAt < geometryEnteredAt + 350;
        long now = SystemClock.elapsedRealtime();
        long stableAt = Math.max(gate.enteredAt + 250, geometryEnteredAt + 350);
        if (!missing) firstFrame.consume(gate.generation);
        OverlayOcclusion confirmationOverlay = ChatGateService.captureOcclusion();
        boolean knownOverlay = confirmationOverlay != null && confirmationOverlay.known;
        long preparationStableAt = knownOverlay ? Math.max(stableAt, confirmationOverlay.changedAt + 350) : stableAt;
        boolean firstAvailable = firstFrame.available(gate.generation, preparationStableAt, readerRequestedAt);
        boolean initialPreparation = firstAvailable && knownOverlay;
        boolean earlyConfirmation = !initialPreparation && knownOverlay
                && session.canPrepareConfirmationFrame(gate.generation);
        boolean expedited = initialPreparation || earlyConfirmation;
        if (expedited) stableAt = preparationStableAt;
        // 新范围首帧只等稳定门槛；第二确认帧可领取一次600ms机会，普通恢复仍是1秒。
        // 不修改lastOcrAt或到帧时间，新图继续通过OCR节流、稳定与完整来源校验。
        long recoveryOcrAt = session.awaitingConfirmation(gate.generation) ? 0 : lastOcrAt;
        boolean needed = initialPreparation ? firstFrame.needed(now, stableAt, busy, missing) : earlyConfirmation
                ? FreshFrameRecovery.confirmationNeeded(now, stableAt, readerRequestedAt, arrivedAt, busy, missing)
                : FreshFrameRecovery.needed(now, stableAt, readerRequestedAt, arrivedAt, recoveryOcrAt, busy, missing);
        if (!needed) {
            if (!busy && missing) {
                long due = initialPreparation ? stableAt : earlyConfirmation ? FreshFrameRecovery.confirmationDueAt(stableAt, readerRequestedAt, arrivedAt)
                        : FreshFrameRecovery.dueAt(stableAt, readerRequestedAt, arrivedAt, recoveryOcrAt);
                if (due > now) scheduleTick(due);
            }
            return false;
        }
        // 调用方已验证来源；替换前再查，期限跨过时不发起新输出。
        if (!geometryCurrent() || stopped || AssistantState.paused
                || !gate.valid()) return false;
        ImageReader previous = reader;
        ProjectionSession expectedProjection = projectionSession;
        long expectedTicket = session.ticket();
        long expectedPolicy = AssistantState.policyVersion;
        if (earlyConfirmation && !session.claimConfirmationFrame(gate.generation)) return false;
        firstFrame.consume(gate.generation);
        ImageReader replacement = null;
        try {
            replacement = createReader(geometry);
            discardLatest();
            deliverComposer(null);
            // 几何读取及输入回调可能耗时或撤权，所有检查完成后最后读取证书期限。
            boolean currentGeometry = geometryCurrent();
            ChatGateService.Snapshot current = ChatGateService.snapshot();
            if (!currentGeometry || current.generation != gate.generation || stopped
                    || AssistantState.paused || reader != previous || projectionSession != expectedProjection
                    || expedited && (session.ticket() != expectedTicket || AssistantState.policyVersion != expectedPolicy
                        || earlyConfirmation && !session.awaitingConfirmation(gate.generation)
                        || ChatGateService.captureOcclusion() != confirmationOverlay) || !current.valid()) {
                replacement.close();
                return true;
            }
            previous.setOnImageAvailableListener(null, null);
            reader = replacement;
            projectionSession.update(geometry, replacement.getSurface());
            previous.setOnImageAvailableListener(null, null);
            previous.close();
            return true;
        } catch (RuntimeException failure) {
            discardLatest();
            reader = null;
            if (replacement != null) {
                replacement.setOnImageAvailableListener(null, null);
                replacement.close();
            }
            if (previous != null) {
                previous.setOnImageAvailableListener(null, null);
                previous.close();
            }
            stopAssistant("恢复新画面失败，请重新开启辅助");
            return true;
        }
    }

    /** 自有遮挡变化撤销旧识别，不改变范围租约；未知布局或标题/输入带被盖住时拒绝。 */
    private ChatViewport readingViewport(ChatGateService.Snapshot gate) {
        OverlayOcclusion current = ChatGateService.captureOcclusion();
        if (readingOverlay != current) {
            readingOverlay = current;
            fingerprints.clear();
            processedHash = Long.MIN_VALUE;
            candidateHash = Long.MIN_VALUE;
            changedAt = SystemClock.elapsedRealtime();
            AssistantState.invalidate("显示区域变化，正在更新可见聊天");
            deliverComposer(null);
        }
        if (current == null || !current.known || gate.viewport == null) return null;
        ChatViewport result = gate.viewport.withOverlay(current.bounds);
        if (result == null) {
            AssistantState.status = "请将助手移离聊天标题或输入区，才能继续识别";
            return null;
        }
        if (latest != null && (imageOverlay != current || arrivedAt < current.changedAt + 350)) {
            discardLatest();
            processedHash = Long.MIN_VALUE;
        }
        return result;
    }

    private boolean geometryCurrent() {
        return geometryReady() && geometry.same(ScreenGeometryReader.read(this));
    }

    private boolean geometryReady() {
        return !stopped && reader != null && projectionSession != null && projectionSession.ready()
                && regionAligned && regionVisible && geometry != null && geometry.supported();
    }

    private void reconcileViewport(ChatGateService.Snapshot gate) {
        if (viewportGeneration != gate.generation) {
            viewportGeneration = gate.generation;
            resetGeometryScope("微信窗口或遮挡范围变化，重新确认当前聊天");
        }
    }

    private void applyGeometry(ScreenGeometry current) {
        if (current == null) {
            throw new IllegalArgumentException("Screen geometry unavailable");
        }
        geometry = current;
        metrics = new DisplayMetrics();
        metrics.widthPixels = current.width;
        metrics.heightPixels = current.height;
        metrics.densityDpi = current.densityDpi;
        metrics.density = current.densityDpi / 160f;
        statusHeight = current.topInset;
        navigationHeight = current.bottomInset;
    }

    private void resetGeometryScope(String reason) {
        pendingResumeGeneration = -1;
        AssistantState.invalidateScope(reason);
        AssistantState.expanded = false;
        deliverComposer(null);
        discardLatest();
        session.close(); headerCache.clear();
        tracker.clear();
        if (bubbleOcr != null) {
            bubbleOcr.quarantineCache();
        }
        candidateHash = Long.MIN_VALUE;
        processedHash = Long.MIN_VALUE;
        lastLayout = "";
        geometryEnteredAt = SystemClock.elapsedRealtime();
        if (analysis != null) {
            analysis.poll();
        }
    }

    private boolean reconcileGeometry() {
        if (stopped || geometry == null) {
            return false;
        }
        ScreenGeometry current = ScreenGeometryReader.read(this);
        if (current == null) {
            if (!geometryUnavailable) {
                geometryUnavailable = true;
                resetGeometryScope("屏幕范围暂无法确认，已暂停识别与填入");
            }
            return false;
        }
        if (!geometry.same(current)) {
            resetGeometryScope("屏幕尺寸、方向或边界变化，重新确认当前聊天");
            ImageReader previous = reader;
            reader = null;
            if (previous != null) {
                previous.setOnImageAvailableListener(null, null);
            }
            ImageReader replacement = null;
            try {
                if (previous != null) {
                    replacement = createReader(current);
                }
                projectionSession.update(current, replacement == null ? null : replacement.getSurface());
                applyGeometry(current);
                reader = replacement;
                Log.i("ChatAssistant", "RESIZE width=" + current.width + " height=" + current.height + " rotation=" + current.rotation
                        + " insets=" + current.leftInset + "," + current.topInset + "," + current.rightInset + "," + current.bottomInset);
            } catch (RuntimeException exception) {
                if (replacement != null) {
                    replacement.close();
                }
                stopAssistant("显示适配失败：" + exception.getClass().getSimpleName());
                return false;
            } finally {
                if (previous != null) {
                    previous.close();
                }
            }
        }
        if (geometryUnavailable) {
            geometryUnavailable = false;
            resetGeometryScope("屏幕范围已恢复，重新确认当前聊天");
        }
        if (!geometry.supported()) {
            AssistantState.status = "当前屏幕边界或空间暂无法确认，已暂停识别与填入；恢复布局后可继续";
            return false;
        }
        return true;
    }

    private void stopAssistant(String reason) {
        if (stopped) {
            return;
        }
        stopped = true;
        headerCache.clear();
        clearHeaderPixels();
        pendingResumeGeneration = -1;
        processingPixels = null;
        if (bubbleOcr != null) bubbleOcr.clearCache();
        AssistantState.invalidate(reason);
        if (projectionSession != null) {
            projectionSession.requestStop();
        }
        Log.i("ChatAssistant", "STOP frames=" + frameCount);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopped = true;
        headerCache.clear();
        clearHeaderPixels();
        // 当前SDK已结束才会登记几何读取；显式回收其图像，不能依赖待移除的主线程回调。
        resultGeometry.close();
        resultGeometryWorker.shutdownNow();
        pendingResumeGeneration = -1;
        processingPixels = null;
        active = false;
        if (instance == this) {
            instance = null;
        }
        deliverComposer(null);
        handler.removeCallbacksAndMessages(null);
        if (displayManager != null) {
            displayManager.unregisterDisplayListener(displayListener);
        }
        if (analysis != null) {
            analysis.close();
        }
        discardLatest();
        if (projectionSession != null) {
            projectionSession.close();
        }
        if (reader != null) {
            reader.setOnImageAvailableListener(null, null);
            reader.close();
        }
        if (bubbleOcr != null) {
            bubbleOcr.close();
        }
        if (recognizer != null) {
            recognizer.close();
        }
        AssistantState.clear();
        tracker.clear();
        session.close(); headerCache.clear();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
