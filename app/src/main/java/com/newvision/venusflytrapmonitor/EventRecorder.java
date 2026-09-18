package com.newvision.venusflytrapmonitor;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Manages a rolling buffer of short video segments and preserves a window of
 * segments around a detected trap closure event.
 *
 * Approach: record continuously in fixed-length segments (SEGMENT_DURATION_MS).
 * The most recent ROLLING_BUFFER_SEGMENTS completed segments are always kept
 * on disk as a pre-event buffer; older ones are deleted. When a trap enters
 * CLOSING, the segments currently in the rolling buffer are copied into a new
 * event folder, and any segment that completes while the event is still
 * "active" (through CLOSED confirmation plus a short post-event window) is
 * copied into that same folder too.
 *
 * KNOWN LIMITATION: this does not stitch segments into a single file. An
 * event folder can contain multiple 15s segment files whose combined
 * duration exceeds the "<=30s single clip" ideal from the spec. Trimming
 * these into one exact clip would need MediaMuxer-based re-encoding - left
 * as a follow-up once this capture pipeline is verified working.
 *
 * NOT YET HANDLED: unbounded growth of the events/ folder over time (see
 * Phase F "prevent unbounded storage usage" in the handoff doc).
 */
public class EventRecorder {

    private static final String TAG = "EventRecorder";

    private static final long SEGMENT_DURATION_MS = 15000;
    private static final int ROLLING_BUFFER_SEGMENTS = 2;

    // How long to keep tagging new segments into an event folder after a
    // trap is confirmed CLOSED, to capture some post-event context.
    private static final long POST_EVENT_WINDOW_MS = 10000;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Recorder recorder;
    private Recording currentRecording;

    private final File segmentsDir;
    private final File eventsDir;

    // Completed segment files still in the rolling buffer, oldest first.
    private final List<File> rollingSegments = new ArrayList<>();

    // trapId -> active event bookkeeping.
    private final Map<Integer, ActiveEvent> activeEvents = new HashMap<>();

    private boolean running = false;

    private static class ActiveEvent {
        final String eventId;
        final File folder;
        final long startTime;
        boolean closedConfirmed = false;
        long closedConfirmedTime = 0;
        final JSONArray segmentFiles = new JSONArray();

        ActiveEvent(String eventId, File folder, long startTime) {
            this.eventId = eventId;
            this.folder = folder;
            this.startTime = startTime;
        }
    }

    public EventRecorder(Context context) {
        this.context = context.getApplicationContext();
        this.segmentsDir = new File(context.getExternalFilesDir(null), "segments");
        this.eventsDir = new File(context.getExternalFilesDir(null), "events");
        //noinspection ResultOfMethodCallIgnored
        segmentsDir.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        eventsDir.mkdirs();
    }

    /**
     * Begins rolling segmented recording using the given Recorder. The
     * Recorder must already be bound into an active CameraX VideoCapture
     * use case before this is called.
     */
    public void start(Recorder recorder) {
        this.recorder = recorder;
        running = true;
        startNextSegment();
    }

    public void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);

        if (currentRecording != null) {
            currentRecording.stop();
            currentRecording = null;
        }
    }

    private void startNextSegment() {

        if (!running || recorder == null) {
            return;
        }

        String fileName = "segment_" + System.currentTimeMillis() + ".mp4";
        File outputFile = new File(segmentsDir, fileName);

        FileOutputOptions outputOptions =
                new FileOutputOptions.Builder(outputFile).build();

        currentRecording = recorder
                .prepareRecording(context, outputOptions)
                .start(ContextCompat.getMainExecutor(context), this::onVideoRecordEvent);

        handler.postDelayed(this::rotateSegment, SEGMENT_DURATION_MS);
    }

    private void rotateSegment() {

        if (currentRecording != null) {
            currentRecording.stop();
            currentRecording = null;
        }
        // The next segment starts once Finalize arrives in
        // onVideoRecordEvent(), to avoid overlapping recordings.
    }

    private void onVideoRecordEvent(@NonNull VideoRecordEvent event) {

        if (!(event instanceof VideoRecordEvent.Finalize)) {
            return;
        }

        VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) event;

        if (finalizeEvent.hasError()) {
            Log.e(TAG, "Segment recording error: " + finalizeEvent.getError());
        }

        File finishedFile = new File(
                finalizeEvent.getOutputResults().getOutputUri().getPath()
        );

        onSegmentFinished(finishedFile);

        if (running) {
            startNextSegment();
        }
    }

    private void onSegmentFinished(File segmentFile) {

        rollingSegments.add(segmentFile);

        for (ActiveEvent activeEvent : activeEvents.values()) {
            copySegmentIntoEvent(segmentFile, activeEvent);
        }

        while (rollingSegments.size() > ROLLING_BUFFER_SEGMENTS) {

            File oldest = rollingSegments.get(0);

            if (isReferencedByActiveEvent(oldest)) {
                break;
            }

            rollingSegments.remove(0);
            //noinspection ResultOfMethodCallIgnored
            oldest.delete();
        }

        long now = System.currentTimeMillis();
        List<Integer> finishedTrapIds = new ArrayList<>();

        for (Map.Entry<Integer, ActiveEvent> entry : activeEvents.entrySet()) {
            ActiveEvent activeEvent = entry.getValue();
            if (activeEvent.closedConfirmed
                    && now - activeEvent.closedConfirmedTime >= POST_EVENT_WINDOW_MS) {
                finalizeEvent(activeEvent);
                finishedTrapIds.add(entry.getKey());
            }
        }

        for (Integer trapId : finishedTrapIds) {
            activeEvents.remove(trapId);
        }
    }

    private boolean isReferencedByActiveEvent(File segmentFile) {
        for (ActiveEvent activeEvent : activeEvents.values()) {
            for (int i = 0; i < activeEvent.segmentFiles.length(); i++) {
                if (segmentFile.getName().equals(activeEvent.segmentFiles.optString(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void copySegmentIntoEvent(File segmentFile, ActiveEvent activeEvent) {

        try {
            File dest = new File(activeEvent.folder, segmentFile.getName());

            if (!dest.exists()) {
                copyFile(segmentFile, dest);
                activeEvent.segmentFiles.put(segmentFile.getName());
            }

        } catch (IOException e) {
            Log.e(TAG, "Failed to copy segment into event folder", e);
        }
    }

    private void copyFile(File source, File dest) throws IOException {

        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(dest)) {

            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    /**
     * Called from TrapDetector's state-change listener.
     */
    public void onTrapStateChanged(
            int trapId,
            TrapDetector.TrapState oldState,
            TrapDetector.TrapState newState
    ) {

        if (oldState == TrapDetector.TrapState.OPEN
                && newState == TrapDetector.TrapState.CLOSING) {

            beginEvent(trapId);

        } else if (newState == TrapDetector.TrapState.OPEN
                && activeEvents.containsKey(trapId)) {

            // Reverted before confirming - spurious spike, discard.
            abortEvent(trapId);

        } else if (newState == TrapDetector.TrapState.CLOSED
                && activeEvents.containsKey(trapId)) {

            ActiveEvent activeEvent = activeEvents.get(trapId);
            activeEvent.closedConfirmed = true;
            activeEvent.closedConfirmedTime = System.currentTimeMillis();
        }
    }

    private void beginEvent(int trapId) {

        if (activeEvents.containsKey(trapId)) {
            return;
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date());
        String eventId = "trap" + trapId + "_" + timestamp;

        File folder = new File(eventsDir, eventId);
        //noinspection ResultOfMethodCallIgnored
        folder.mkdirs();

        ActiveEvent activeEvent = new ActiveEvent(eventId, folder, System.currentTimeMillis());
        activeEvents.put(trapId, activeEvent);

        // Seed with whatever's already in the rolling buffer as pre-event
        // context.
        for (File segment : rollingSegments) {
            copySegmentIntoEvent(segment, activeEvent);
        }

        Log.d(TAG, "Event started for trap " + trapId + ": " + eventId);
    }

    private void abortEvent(int trapId) {

        ActiveEvent activeEvent = activeEvents.remove(trapId);

        if (activeEvent != null) {
            deleteRecursive(activeEvent.folder);
            Log.d(TAG, "Event aborted for trap " + trapId + " (spurious signal)");
        }
    }

    private void finalizeEvent(ActiveEvent activeEvent) {

        try {
            JSONObject metadata = new JSONObject();
            metadata.put("eventId", activeEvent.eventId);
            metadata.put("startTime", activeEvent.startTime);
            metadata.put("closedConfirmedTime", activeEvent.closedConfirmedTime);
            metadata.put("finalizedTime", System.currentTimeMillis());
            metadata.put("segments", activeEvent.segmentFiles);

            File metadataFile = new File(activeEvent.folder, "metadata.json");

            try (FileWriter writer = new FileWriter(metadataFile)) {
                writer.write(metadata.toString());
            }

            Log.d(TAG, "Event finalized: " + activeEvent.eventId
                    + " (" + activeEvent.segmentFiles.length() + " segment(s))");

        } catch (Exception e) {
            Log.e(TAG, "Failed to write event metadata", e);
        }
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    public File getEventsDir() {
        return eventsDir;
    }
}