package com.newvision.venusflytrapmonitor;

import android.graphics.Color;
import android.graphics.Matrix;
import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.graphics.Bitmap;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "VenusMonitor";

    // Frames are only fully processed (bitmap conversion, detection, JPEG
    // encode) at most this often. Nothing currently consumes frames faster
    // than this (the browser polls /preview every 300ms), so processing
    // every camera frame - likely ~30fps - was pure wasted CPU/battery.
    private static final long FRAME_PROCESS_INTERVAL_MS = 300;

    // Fallback dim level, kept as a safety net underneath the black overlay
    // below (e.g. in case the overlay doesn't fully cover system bars on
    // some device). The overlay, not this, is what actually saves battery
    // on an AMOLED panel - dimming alone still renders a bright, colorful
    // camera image at reduced scale, which still draws real power per
    // pixel.
    private static final float DIMMED_SCREEN_BRIGHTNESS = 0.01f;

    private PreviewView previewView;

    private View blackoutView;

    private ExecutorService analysisExecutor;

    private WebServer webServer;

    private TrapDetector trapDetector;

    private ProcessCameraProvider cameraProvider;

    private Camera camera;

    private boolean previewBound = false;

    private float currentZoomRatio = 1.0f;

    private float pinchStartDistance = 0.0f;

    private byte[] latestJpeg;

    private long lastProcessedFrameTime = 0;

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

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {

                        if (granted) {
                            startCamera();
                        } else {
                            Log.e(
                                    TAG,
                                    "Camera permission denied"
                            );
                        }
                    }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        setContentView(R.layout.activity_main);

        previewView =
                findViewById(R.id.previewView);

        previewView.setOnTouchListener(this::handlePreviewTouch);

        // Solid black overlay, added on top of the whole window content
        // regardless of activity_main.xml's layout structure. This is what
        // actually saves power on an AMOLED display once monitoring is
        // unattended - true black pixels draw near-zero current, unlike a
        // dimmed-but-still-colorful camera image.
        blackoutView = new View(this);
        blackoutView.setBackgroundColor(Color.BLACK);
        blackoutView.setVisibility(View.GONE);

        addContentView(
                blackoutView,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );

        analysisExecutor =
                Executors.newSingleThreadExecutor();

        webServer =
                new WebServer(this);

        webServer.setZoomChangeListener(this::applyZoomRatio);
        webServer.setTrapConfigChangeListener(this::reloadTrapConfiguration);
        webServer.setBatteryLevelPercent(getBatteryPercentage());
        webServer.start();
        batteryHandler.post(batteryRefreshRunnable);

        loadTrapConfiguration();

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED) {

            startCamera();

        } else {

            cameraPermissionLauncher.launch(
                    Manifest.permission.CAMERA
            );
        }
    }

    private void reloadTrapConfiguration(JSONArray traps) {
        if (traps == null) {
            traps = new JSONArray();
        }

        trapDetector = new TrapDetector(traps);
        webServer.setTrapDetector(trapDetector);

        applyUnattendedDisplayState();

        Log.d(
                TAG,
                "Reloaded trap configuration: " +
                        traps.length() +
                        " ROI(s)"
        );
    }

    // Once at least one ROI is saved, zoom/ROI editing is already locked
    // (see WebServer's /zoom rule) and there is nothing left on-screen that
    // needs to be visible. At that point: black out the display for real
    // (not just dim it), and drop the CameraX Preview use case entirely so
    // the display-compositing pipeline isn't doing that work either.
    // Reverts back to a normal, visible preview whenever there are no
    // saved ROIs (setup mode), since that's when the live image is
    // actually being looked at for zoom/ROI placement.
    private void applyUnattendedDisplayState() {

        boolean hasRois =
                trapDetector != null && trapDetector.hasConfiguredRois();

        WindowManager.LayoutParams params = getWindow().getAttributes();

        params.screenBrightness = hasRois
                ? DIMMED_SCREEN_BRIGHTNESS
                : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;

        getWindow().setAttributes(params);

        if (blackoutView != null) {
            blackoutView.setVisibility(hasRois ? View.VISIBLE : View.GONE);
        }

        applyCameraBinding();
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

    private boolean handlePreviewTouch(
            android.view.View view,
            MotionEvent event
    ) {

        if (trapDetector == null ||
                trapDetector.hasConfiguredRois()) {
            return false;
        }

        if (event.getPointerCount() != 2) {
            return false;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN:
                pinchStartDistance = getPointerDistance(event);
                break;

            case MotionEvent.ACTION_MOVE:
                float currentDistance = getPointerDistance(event);

                if (pinchStartDistance > 0f && currentDistance > 0f) {
                    float scale = currentDistance / pinchStartDistance;
                    applyZoomRatio(currentZoomRatio * scale);
                    pinchStartDistance = currentDistance;
                    return true;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pinchStartDistance = 0f;
                break;

            default:
                break;
        }

        return false;
    }

    private float getPointerDistance(MotionEvent event) {

        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);

        return (float) Math.hypot(dx, dy);
    }

    private void applyZoomRatio(float zoomRatio) {

        if (camera == null) {
            return;
        }

        currentZoomRatio = Math.max(1.0f, Math.min(3.0f, zoomRatio));
        camera.getCameraControl().setZoomRatio(currentZoomRatio);
    }

    private void startCamera() {

        ListenableFuture<ProcessCameraProvider>
                cameraProviderFuture =
                ProcessCameraProvider.getInstance(
                        this
                );

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

    // Binds ImageAnalysis always, and additionally binds Preview only while
    // there are no saved ROIs (setup mode). Rebinding only happens when the
    // desired state actually changes, to avoid unnecessarily restarting the
    // camera pipeline on every ROI save.
    private void applyCameraBinding() {

        if (cameraProvider == null) {
            return;
        }

        boolean hasRois =
                trapDetector != null && trapDetector.hasConfiguredRois();

        boolean wantPreview = !hasRois;

        if (camera != null && wantPreview == previewBound) {
            // Already in the desired state.
            return;
        }

        ImageAnalysis imageAnalysis =
                new ImageAnalysis.Builder()
                        .setTargetRotation(
                                getWindowManager()
                                        .getDefaultDisplay()
                                        .getRotation()
                        )
                        .setBackpressureStrategy(
                                ImageAnalysis
                                        .STRATEGY_KEEP_ONLY_LATEST
                        )
                        .build();

        imageAnalysis.setAnalyzer(
                analysisExecutor,
                this::analyzeFrame
        );

        CameraSelector cameraSelector =
                CameraSelector.DEFAULT_BACK_CAMERA;

        cameraProvider.unbindAll();

        if (wantPreview) {

            Preview preview =
                    new Preview.Builder()
                            .setTargetRotation(
                                    getWindowManager()
                                            .getDefaultDisplay()
                                            .getRotation()
                            )
                            .build();

            preview.setSurfaceProvider(
                    previewView.getSurfaceProvider()
            );

            camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
            );

        } else {

            // No Preview use case at all - the on-screen PreviewView isn't
            // needed once ROIs are locked, and this also drops the
            // display-compositing work that binding it would otherwise
            // require, regardless of the blackout overlay's visibility.
            camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    imageAnalysis
            );
        }

        previewBound = wantPreview;

        applyZoomRatio(currentZoomRatio);
    }

    private Bitmap rotateBitmap(
            Bitmap bitmap,
            int rotationDegrees
    ) {

        if (rotationDegrees == 0) {
            return bitmap;
        }

        Matrix matrix = new Matrix();

        matrix.postRotate(rotationDegrees);

        return Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.getWidth(),
                bitmap.getHeight(),
                matrix,
                true
        );
    }

    private void analyzeFrame(
            ImageProxy imageProxy
    ) {

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

            bitmap =
                    imageProxy.toBitmap();

            if (trapDetector != null) {

                trapDetector.analyzeFrame(
                        bitmap
                );
            }

            ByteArrayOutputStream outputStream =
                    new ByteArrayOutputStream();

            bitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    80,
                    outputStream
            );

            latestJpeg =
                    outputStream.toByteArray();

            webServer.setLatestJpeg(
                    latestJpeg
            );

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

    @Override
    protected void onDestroy() {

        super.onDestroy();

        batteryHandler.removeCallbacks(batteryRefreshRunnable);

        if (analysisExecutor != null) {
            analysisExecutor.shutdown();
        }

        if (webServer != null) {
            webServer.stop();
        }
    }
}