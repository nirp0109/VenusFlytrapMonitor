package com.newvision.venusflytrapmonitor;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/**
 * Phase F, step 3: MainActivity is now a thin permissions/start-stop shell.
 * Everything it used to own directly - camera binding, TrapDetector,
 * EventRecorder, WebServer, screen dimming, pinch-to-zoom - now lives in
 * MonitoringService (see that class for why each piece moved or was
 * dropped). All actual monitoring, detection, recording, and the web API
 * keep running via the service regardless of whether this Activity is
 * open, backgrounded, or the screen is off.
 *
 * FLAG_KEEP_SCREEN_ON is intentionally gone - forcing the screen to stay
 * on while this Activity is foregrounded would directly work against the
 * entire point of Phase F.
 *
 * activity_main.xml still declares a `previewView`; nothing binds to it
 * anymore since the `Preview` use case was removed (MonitoringService's
 * class comment explains why), so it just renders empty. Harmless -
 * cosmetic cleanup only, not touched here since it doesn't affect
 * behavior.
 *
 * The "Start/Stop Service (test)" buttons are the same temporary ones
 * added in step 2, kept for manual testing. Camera-permission grant now
 * auto-starts the service (replacing the old direct startCamera() call),
 * so normal use doesn't require touching them - remove once step 4's
 * broader MainActivity cleanup happens.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "VenusMonitor";

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {

                        if (granted) {
                            MonitoringService.start(this);
                        } else {
                            Log.e(
                                    TAG,
                                    "Camera permission denied"
                            );
                        }
                    }
            );

    // POST_NOTIFICATIONS is required at runtime on API 33+ to actually
    // show MonitoringService's persistent notification (the service still
    // runs without it - the notification just silently won't appear). Not
    // applicable to the real device (Note9, API 29), but requested anyway
    // so this keeps working correctly if this build ever runs on a newer
    // device.
    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {

                        if (!granted) {
                            Log.w(
                                    TAG,
                                    "Notification permission denied - MonitoringService will run without a visible notification"
                            );
                        }

                        MonitoringService.start(this);
                    }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        addMonitoringServiceTestButtons();

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED) {

            MonitoringService.start(this);

        } else {

            cameraPermissionLauncher.launch(
                    Manifest.permission.CAMERA
            );
        }
    }

    // Temporary (step 2/3): proves MonitoringService's foreground/
    // notification mechanics and gives manual start/stop control during
    // testing. To be deleted in step 4 once the service's always-on
    // lifecycle is the only path that matters.
    private void addMonitoringServiceTestButtons() {

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button startButton = new Button(this);
        startButton.setText("Start Service (test)");
        startButton.setOnClickListener(v -> requestNotificationPermissionThenStart());
        row.addView(startButton);

        Button stopButton = new Button(this);
        stopButton.setText("Stop Service (test)");
        stopButton.setOnClickListener(v -> MonitoringService.stop(this));
        row.addView(stopButton);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.TOP | Gravity.END;

        addContentView(row, params);
    }

    private void requestNotificationPermissionThenStart() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED) {

            notificationPermissionLauncher.launch(
                    Manifest.permission.POST_NOTIFICATIONS
            );

        } else {

            // Not applicable on the real device (API 29) - the permission
            // doesn't exist below API 33, so there's nothing to request.
            MonitoringService.start(this);
        }
    }
}