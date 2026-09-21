package com.newvision.venusflytrapmonitor;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;

import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.VideoCapture;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.graphics.Bitmap;

/**
 * Phase F, step 3: MonitoringService now owns everything that used to live
 * in MainActivity - camera binding, TrapDetector, EventRecorder, and
 * WebServer - so monitoring survives the screen turning off and the
 * Activity going to the background. MainActivity is now just a thin
 * permissions/start-stop shell (see MainActivity.java).
 *
 * Deliberately dropped in this move, not preserved: the on-screen
 * `Preview` use case and `PreviewView`, and pinch-to-zoom-by-touching-the-
 * phone-screen. Both existed purely for someone physically looking at the
 * phone during setup - the browser's live low-fps preview (GET /preview,
 * sourced from ImageAnalysis frames via `latestJpeg`, independent of
 * `Preview`) already covers ROI setup, and a Service has no screen to show
 * anything on regardless. This also removes the old wantPreview/
 * previewBound mutual-exclusion dance entirely: ImageAnalysis is always
 * bound, and VideoCapture binds alongside it once ROIs exist - there's no
 * competing use case to make exclusive anymore.
 */
public class MonitoringService extends LifecycleService {

    private static final String TAG = "MonitoringService";

    private static final String CHANNEL_ID = "monitoring_service_channel";
    private static final int NOTIFICATION_ID = 1;

    private static final String ACTION_START =
            "com.newvision.venusflytrapmonitor.action.START_MONITORING";

    private static final String ACTION_STOP =
            "com.newvision.venusflytrapmonitor.action.STOP_MONITORING";

    // Frames are only fully processed (bitmap conversion, detection, JPEG
    // encode) at most this often. Nothing currently consumes frames faster
    // than this (the browser polls /preview every 300ms), so processing
    // every camera frame - likely ~30fps - was pure wasted CPU/battery.
    private static final long FRAME_PROCESS_INTERVAL_MS = 300;

    private ExecutorService analysisExecutor;

    private WebServer webServer;

    private TrapDetector trapDetector;

    private ProcessCameraProvider cameraProvider;

    private Camera camera;

    private Recorder videoRecorder;

    private VideoCapture<Recorder> videoCapture;

    private EventRecorder eventRecorder;

    // Renamed from MainActivity's previewBound: tracks whether the camera
    // is currently bound for monitoring (ImageAnalysis + VideoCapture) as
    // opposed to just ImageAnalysis alone with no ROIs configured yet.
    // There's no "preview" state to track anymore - see the class comment.
    private boolean monitoringBound = false;

    private float currentZoomRatio = 1.0f;

    private long lastProcessedFrameTime = 0;

    // Dispatches camera-binding work onto the main thread. CameraX's
    // ProcessCameraProvider.bindToLifecycle() must be called from the main
    // thread, and WebServer's own background server thread still calls
    // into reloadTrapConfiguration() via the /traps save callback - the
    // same class of cross-thread problem that caused the
    // CalledFromWrongThreadException bug in MainActivity (see handoff
    // §17 #41), just against a different API this time. A Service has no
    // runOnUiThread(), so a plain Handler on the main Looper does the job.
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());

    private final Handler batteryHandler = new Handler(Looper.getMainLooper());
    private final Runnable batteryRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (webServer != null) {
                webServer.setBatteryLevelPercent(getBatteryPercentage());
            }
            batteryHandler.postDelayed(this, 15000L);
        }
    };

    public static void start(Context context) {
        Intent intent = new Intent(context, MonitoringService.class);
        intent.setAction(ACTION_START);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, MonitoringService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
        ) != PackageManager.PERMISSION_GRANTED) {

            // MainActivity is responsible for requesting CAMERA before
            // ever calling MonitoringService.start() - a Service cannot
            // itself show a runtime permission dialog. This is a defensive
            // check, not the primary permission-request path.
            Log.e(
                    TAG,
                    "CAMERA permission not granted - monitoring cannot start"
            );

            return;
        }

        analysisExecutor = Executors.newSingleThreadExecutor();

        eventRecorder = new EventRecorder(this);

        webServer = new WebServer(this);

        webServer.setZoomChangeListener(this::applyZoomRatio);
        webServer.setTrapConfigChangeListener(this::reloadTrapConfiguration);
        webServer.setBatteryLevelPercent(getBatteryPercentage());
        webServer.setEventRecorder(eventRecorder);
        webServer.start();

        batteryHandler.post(batteryRefreshRunnable);

        loadTrapConfiguration();

        startCamera();

        Log.d(TAG, "MonitoringService created and monitoring started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        // Dispatches the Lifecycle ON_START event via LifecycleService's
        // onStart(); the int it returns is Service's own default and is
        // intentionally ignored here in favor of the value this method
        // returns below.
        super.onStartCommand(intent, flags, startId);

        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            Log.d(TAG, "Stopping MonitoringService");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        Log.d(TAG, "Starting MonitoringService in the foreground");

        Notification notification = buildNotification();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 3-arg overload available since API 29 (Android 10) - the
            // exact API level the real device runs.
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        // If the system kills this service under memory pressure, ask it
        // to be recreated once resources free up rather than staying
        // dead - onCreate() re-runs the full monitoring setup in that case.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        batteryHandler.removeCallbacks(batteryRefreshRunnable);

        if (eventRecorder != null) {
            eventRecorder.stop();
        }

        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }

        if (analysisExecutor != null) {
            analysisExecutor.shutdown();
        }

        if (webServer != null) {
            webServer.stop();
        }

        Log.d(TAG, "MonitoringService destroyed");
    }

    // Called both at startup (via onCreate() -> loadTrapConfiguration())
    // and from WebServer's background server thread (POST /traps triggers
    // this via trapConfigChangeListener). applyCameraBinding() below calls
    // cameraProvider.bindToLifecycle(), which CameraX requires to happen
    // on the main thread - hence dispatching through mainThreadHandler
    // rather than calling straight through. See the class-level comment
    // on mainThreadHandler for why this matters.
    private void reloadTrapConfiguration(JSONArray traps) {
        if (traps == null) {
            traps = new JSONArray();
        }

        final JSONArray finalTraps = traps;

        mainThreadHandler.post(() -> {
            trapDetector = new TrapDetector(finalTraps);
            webServer.setTrapDetector(trapDetector);

            trapDetector.setStateListener((trapId, oldState, newState) -> {
                if (eventRecorder != null) {
                    eventRecorder.onTrapStateChanged(trapId, oldState, newState);
                }
            });

            applyCameraBinding();

            Log.d(
                    TAG,
                    "Reloaded trap configuration: " +
                            finalTraps.length() +
                            " ROI(s)"
            );
        });
    }

    private void loadTrapConfiguration() {

        SharedPreferences preferences =
                getSharedPreferences(
                        "venus_monitor",
                        MODE_PRIVATE
                );

        currentZoomRatio =
                preferences.getFloat(
                        "zoom",
                        1.0f
                );

        String trapsJson =
                preferences.getString(
                        "traps",
                        "[]"
                );

        try {
            JSONArray traps =
                    new JSONArray(trapsJson);
            reloadTrapConfiguration(traps);

            Log.d(
                    TAG,
                    "Loaded " +
                            traps.length() +
                            " ROI(s), zoom=" +
                            currentZoomRatio
            );

        } catch (Exception e) {
            Log.e(
                    TAG,
                    "Failed to load trap configuration",
                    e
            );

            reloadTrapConfiguration(new JSONArray());
        }
    }

    private void startCamera() {

        ListenableFuture<ProcessCameraProvider>
                cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(
                () -> {

                    try {

                        cameraProvider =
                                cameraProviderFuture.get();

                        applyCameraBinding();

                    } catch (Exception e) {

                        Log.e(
                                TAG,
                                "Failed to start camera",
                                e
                        );
                    }

                },
                ContextCompat.getMainExecutor(this)
        );
    }

    // Always binds ImageAnalysis. Additionally binds VideoCapture (and
    // starts EventRecorder's rolling buffer) once at least one ROI is
    // configured - mirroring the monitoring-mode branch of MainActivity's
    // old applyCameraBinding(), minus the Preview/PreviewView branch,
    // which no longer exists (see class comment). Rebinding only happens
    // when the desired state actually changes, to avoid unnecessarily
    // restarting the camera pipeline on every ROI save.
    //
    // Must run on the main thread - see mainThreadHandler.
    private void applyCameraBinding() {

        if (cameraProvider == null) {
            return;
        }

        boolean hasRois =
                trapDetector != null && trapDetector.hasConfiguredRois();

        if (camera != null && hasRois == monitoringBound) {
            // Already in the desired state.
            return;
        }

        WindowManager windowManager =
                (WindowManager) getSystemService(WINDOW_SERVICE);

        int rotation = windowManager != null
                ? windowManager.getDefaultDisplay().getRotation()
                : android.view.Surface.ROTATION_0;

        ImageAnalysis imageAnalysis =
                new ImageAnalysis.Builder()
                        .setTargetRotation(rotation)
                        .setBackpressureStrategy(
                                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                        )
                        .build();

        imageAnalysis.setAnalyzer(
                analysisExecutor,
                this::analyzeFrame
        );

        CameraSelector cameraSelector =
                CameraSelector.DEFAULT_BACK_CAMERA;

        cameraProvider.unbindAll();

        if (hasRois) {

            // A pre-event rolling buffer requires always recording
            // something - this is the continuous CPU/battery cost noted
            // in handoff §18.12, now measured at ~23-25%/hour (§17 #42),
            // which is the whole reason Phase F exists.
            if (videoRecorder == null) {
                QualitySelector qualitySelector = QualitySelector.from(Quality.SD);
                videoRecorder = new Recorder.Builder()
                        .setQualitySelector(qualitySelector)
                        .build();
                videoCapture = VideoCapture.withOutput(videoRecorder);
            }

            camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    imageAnalysis,
                    videoCapture
            );

            if (eventRecorder != null) {
                eventRecorder.start(videoRecorder);
            }

        } else {

            if (eventRecorder != null) {
                eventRecorder.stop();
            }

            camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    imageAnalysis
            );
        }

        monitoringBound = hasRois;

        applyZoomRatio(currentZoomRatio);
    }

    private void applyZoomRatio(float zoomRatio) {

        if (camera == null) {
            return;
        }

        currentZoomRatio = Math.max(1.0f, Math.min(3.0f, zoomRatio));
        camera.getCameraControl().setZoomRatio(currentZoomRatio);
    }

    private void analyzeFrame(ImageProxy imageProxy) {

        // Throttle: skip full processing for frames arriving faster than
        // FRAME_PROCESS_INTERVAL_MS.
        long now = System.currentTimeMillis();

        if (now - lastProcessedFrameTime < FRAME_PROCESS_INTERVAL_MS) {
            imageProxy.close();
            return;
        }

        lastProcessedFrameTime = now;

        Bitmap bitmap = null;

        try {

            bitmap = imageProxy.toBitmap();

            if (trapDetector != null) {
                trapDetector.analyzeFrame(bitmap);
            }

            ByteArrayOutputStream outputStream =
                    new ByteArrayOutputStream();

            bitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    80,
                    outputStream
            );

            webServer.setLatestJpeg(outputStream.toByteArray());

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Failed to analyze frame",
                    e
            );

        } finally {

            if (bitmap != null) {
                bitmap.recycle();
            }

            imageProxy.close();
        }
    }

    private int getBatteryPercentage() {
        try {
            BatteryManager batteryManager =
                    (BatteryManager) getSystemService(BatteryManager.class);

            if (batteryManager != null) {
                return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read battery percentage", e);
        }

        return 0;
    }

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Venus Flytrap Monitoring",
                // LOW: a persistent status indicator, not an alert - no
                // sound, no heads-up popup.
                NotificationManager.IMPORTANCE_LOW
        );

        channel.setDescription(
                "Shown while the flytrap camera is monitoring in the background."
        );

        NotificationManager manager =
                getSystemService(NotificationManager.class);

        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {

        Intent tapIntent = new Intent(this, MainActivity.class);

        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                tapIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Venus Flytrap Monitor")
                .setContentText("Monitoring is running.")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .setContentIntent(contentIntent)
                .build();
    }
}