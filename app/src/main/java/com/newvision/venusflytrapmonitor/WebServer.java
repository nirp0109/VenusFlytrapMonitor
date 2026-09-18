package com.newvision.venusflytrapmonitor;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.function.Consumer;

public class WebServer {

    private static final String TAG = "VenusWebServer";
    private static final float MIN_ZOOM = 1.0f;
    private static final float MAX_ZOOM = 3.0f;

    private final Context context;
    private final SharedPreferences preferences;

    private ServerSocket serverSocket;
    private Thread serverThread;

    private volatile byte[] latestJpeg;
    private volatile Consumer<Float> zoomChangeListener;
    private volatile Consumer<JSONArray> trapConfigChangeListener;
    private volatile TrapDetector trapDetector;
    private volatile int batteryLevelPercent = 0;
    private volatile EventRecorder eventRecorder;

    public WebServer(Context context) {
        this.context = context.getApplicationContext();

        preferences =
                this.context.getSharedPreferences(
                        "venus_monitor",
                        Context.MODE_PRIVATE
                );
    }

    public void setLatestJpeg(byte[] jpeg) {
        latestJpeg = jpeg;
    }

    public void setZoomChangeListener(Consumer<Float> listener) {
        zoomChangeListener = listener;
    }

    public void setTrapConfigChangeListener(Consumer<JSONArray> listener) {
        trapConfigChangeListener = listener;
    }

    public void setBatteryLevelPercent(int percent) {
        batteryLevelPercent = Math.max(0, Math.min(100, percent));
    }

    public void setEventRecorder(EventRecorder recorder) {
        eventRecorder = recorder;
    }

    public void setTrapDetector(TrapDetector detector) {
        trapDetector = detector;

        if (detector != null) {
            try {
                String savedThresholds =
                        preferences.getString(
                                "detector_thresholds",
                                detector.getThresholdSettingsJson().toString()
                        );
                detector.setThresholds(new JSONObject(savedThresholds));
            } catch (Exception e) {
                Log.w(TAG, "Failed to restore saved detector thresholds", e);
            }
        }
    }

    public void start() {

        serverThread = new Thread(() -> {

            try {

                serverSocket = new ServerSocket(8080);

                Log.d(
                        TAG,
                        "Server started on port 8080"
                );

                while (!serverSocket.isClosed()) {

                    Socket socket =
                            serverSocket.accept();

                    Log.d(
                            TAG,
                            "Client connected: "
                                    + socket.getInetAddress()
                    );

                    handleRequest(socket);

                    socket.close();
                }

            } catch (IOException e) {

                Log.e(
                        TAG,
                        "Server error",
                        e
                );
            }

        });

        serverThread.start();
    }

    private void handleRequest(Socket socket) {

        try {

            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    socket.getInputStream()
                            )
                    );

            String requestLine =
                    reader.readLine();

            Log.d(
                    TAG,
                    "Request: " + requestLine
            );

            if (requestLine == null) {
                return;
            }

            int contentLength = 0;

            String line;

            while ((line = reader.readLine()) != null &&
                    !line.isEmpty()) {

                if (line.toLowerCase()
                        .startsWith("content-length:")) {

                    String value =
                            line.substring(
                                    "content-length:".length()
                            ).trim();

                    contentLength =
                            Integer.parseInt(value);
                }
            }

            if (requestLine.startsWith("GET /preview")) {

                sendPreview(socket);

            } else if (
                    requestLine.startsWith("GET /traps")
            ) {

                String traps =
                        preferences.getString(
                                "traps",
                                "[]"
                        );

                sendJson(socket, traps);

            } else if (
                    requestLine.startsWith("GET /status")
            ) {

                sendJson(socket, getStatusPayload());

            } else if (
                    requestLine.startsWith("GET /thresholds")
            ) {

                sendJson(socket, getThresholdsPayload());

            } else if (
                    requestLine.startsWith("GET /battery")
            ) {

                sendJson(socket, getBatteryPayload());

            } else if (
                    requestLine.startsWith("GET /events")
            ) {

                handleEventsGetRequest(socket, requestLine);

            } else if (
                    requestLine.startsWith("DELETE /events")
            ) {

                handleEventsDeleteRequest(socket, requestLine);

            } else if (
                    requestLine.startsWith("POST /traps")
            ) {

                String body =
                        readRequestBody(
                                reader,
                                contentLength
                        );

                JSONArray sanitizedTraps = sanitizeTrapList(body);

                preferences
                        .edit()
                        .putString("traps", sanitizedTraps.toString())
                        .apply();

                try {
                    if (trapDetector != null) {
                        trapDetector = new TrapDetector(sanitizedTraps);
                        trapDetector.setThresholds(getSavedThresholds());
                    }

                    if (trapConfigChangeListener != null) {
                        trapConfigChangeListener.accept(sanitizedTraps);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to reload detector after trap save", e);
                }

                Log.d(
                        TAG,
                        "Traps saved: " + sanitizedTraps.toString()
                );

                sendText(
                        socket,
                        "Traps saved"
                );

            } else if (
                    requestLine.startsWith("POST /zoom")
            ) {

                handleZoomRequest(
                        socket,
                        reader,
                        contentLength
                );

            } else if (
                    requestLine.startsWith("POST /thresholds")
            ) {

                handleThresholdSettingsRequest(
                        socket,
                        reader,
                        contentLength
                );

            } else {

                sendHtml(socket);
            }
        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Failed to handle request",
                    e
            );
        }
    }

    private String readRequestBody(
            BufferedReader reader,
            int contentLength
    ) throws IOException {

        char[] buffer =
                new char[contentLength];

        int totalRead = 0;

        while (totalRead < contentLength) {

            int count =
                    reader.read(
                            buffer,
                            totalRead,
                            contentLength - totalRead
                    );

            if (count == -1) {
                break;
            }

            totalRead += count;
        }

        return new String(
                buffer,
                0,
                totalRead
        );
    }

    private void handleZoomRequest(
            Socket socket,
            BufferedReader reader,
            int contentLength
    ) throws IOException {

        try {
            String body = readRequestBody(reader, contentLength);
            JSONObject payload = new JSONObject(body);

            if (!payload.has("zoom")) {
                sendText(socket, "Missing zoom value", 400);
                return;
            }

            float requestedZoom = (float) payload.getDouble("zoom");

            String traps = preferences.getString("traps", "[]");
            JSONArray configuredTraps = new JSONArray();

            try {
                configuredTraps = new JSONArray(traps);
            } catch (Exception ignored) {
                Log.w(TAG, "Stored ROI list is invalid; treating as empty");
            }

            if (configuredTraps.length() > 0) {
                sendText(socket, "Zoom disabled while ROI exists", 400);
                return;
            }

            float clampedZoom =
                    Math.max(
                            MIN_ZOOM,
                            Math.min(MAX_ZOOM, requestedZoom)
                    );

            preferences
                    .edit()
                    .putFloat("zoom", clampedZoom)
                    .apply();

            if (zoomChangeListener != null) {
                zoomChangeListener.accept(clampedZoom);
            }

            sendText(socket, "Zoom set to " + clampedZoom, 200);
        } catch (Exception e) {
            Log.e(TAG, "Failed to process zoom request", e);
            sendText(socket, "Invalid zoom payload", 400);
        }
    }

    private void handleThresholdSettingsRequest(
            Socket socket,
            BufferedReader reader,
            int contentLength
    ) throws IOException {

        try {
            String body = readRequestBody(reader, contentLength);
            JSONObject payload = new JSONObject(body);

            if (trapDetector != null) {
                trapDetector.setThresholds(payload);
            }

            preferences
                    .edit()
                    .putString("detector_thresholds", payload.toString())
                    .apply();

            sendText(socket, "Thresholds saved");
        } catch (Exception e) {
            Log.e(TAG, "Failed to process threshold request", e);
            sendText(socket, "Invalid threshold payload", 400);
        }
    }

    // Dispatches GET /events (list) vs GET /events/<eventId>/<filename>
    // (serve one file). requestLine looks like "GET /events/foo/bar.mp4
    // HTTP/1.1" - split on spaces to isolate the path.
    private void handleEventsGetRequest(
            Socket socket,
            String requestLine
    ) throws IOException {

        String[] requestParts = requestLine.split(" ");

        if (requestParts.length < 2) {
            sendText(socket, "Bad request", 400);
            return;
        }

        String path = requestParts[1];

        if (path.equals("/events") || path.equals("/events/")) {
            sendJson(socket, getEventsListPayload());
        } else if (path.startsWith("/events/")) {
            handleEventFileRequest(socket, path);
        } else {
            sendText(socket, "Not found", 404);
        }
    }

    // Lists every event folder under EventRecorder's events directory.
    // Finalized events (metadata.json present) include their full metadata;
    // still-active events report just a segment count, since metadata.json
    // is only written once the event's post-event window has closed.
    private String getEventsListPayload() {

        JSONArray events = new JSONArray();

        try {
            if (eventRecorder == null) {
                return events.toString();
            }

            File eventsDir = eventRecorder.getEventsDir();
            File[] eventFolders = eventsDir.listFiles();

            if (eventFolders != null) {

                Arrays.sort(
                        eventFolders,
                        (a, b) -> b.getName().compareTo(a.getName())
                );

                for (File folder : eventFolders) {

                    if (!folder.isDirectory()) {
                        continue;
                    }

                    JSONObject entry = new JSONObject();
                    entry.put("eventId", folder.getName());

                    File metadataFile = new File(folder, "metadata.json");

                    if (metadataFile.exists()) {

                        String metadataText =
                                new String(
                                        readFileBytes(metadataFile),
                                        StandardCharsets.UTF_8
                                );

                        entry.put("finalized", true);
                        entry.put("metadata", new JSONObject(metadataText));

                    } else {

                        entry.put("finalized", false);

                        File[] segmentFiles = folder.listFiles(
                                (dir, name) -> name.endsWith(".mp4")
                        );

                        entry.put(
                                "segmentCount",
                                segmentFiles != null ? segmentFiles.length : 0
                        );
                    }

                    events.put(entry);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to build events payload", e);
        }

        return events.toString();
    }

    // Serves one file (a segment .mp4 or metadata.json) from inside an
    // event folder. Rejects any path containing ".." or an extra "/" in the
    // filename component, since eventId/fileName come straight from the
    // request path.
    private void handleEventFileRequest(
            Socket socket,
            String requestPath
    ) throws IOException {

        if (eventRecorder == null) {
            sendText(socket, "Not found", 404);
            return;
        }

        String remainder = requestPath.substring("/events/".length());
        String[] parts = remainder.split("/", 2);

        if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            sendText(socket, "Not found", 404);
            return;
        }

        String eventId = parts[0];
        String fileName = parts[1];

        if (eventId.contains("..")
                || fileName.contains("..")
                || fileName.contains("/")) {
            sendText(socket, "Invalid path", 400);
            return;
        }

        File eventFolder = new File(eventRecorder.getEventsDir(), eventId);
        File requestedFile = new File(eventFolder, fileName);

        if (!requestedFile.exists() || !requestedFile.isFile()) {
            sendText(socket, "Not found", 404);
            return;
        }

        String contentType = fileName.endsWith(".json")
                ? "application/json; charset=UTF-8"
                : "video/mp4";

        sendFile(socket, requestedFile, contentType);
    }

    private void handleEventsDeleteRequest(
            Socket socket,
            String requestLine
    ) throws IOException {

        String[] requestParts = requestLine.split(" ");

        if (requestParts.length < 2) {
            sendText(socket, "Bad request", 400);
            return;
        }

        String path = requestParts[1];

        if (!path.startsWith("/events/")) {
            sendText(socket, "Not found", 404);
            return;
        }

        String remainder = path.substring("/events/".length());
        String[] parts = remainder.split("/", 2);

        String eventId = parts[0];

        if (eventId.isEmpty() || eventId.contains("..")) {
            sendText(socket, "Invalid path", 400);
            return;
        }

        if (eventRecorder == null) {
            sendText(socket, "Not found", 404);
            return;
        }

        File eventFolder = new File(eventRecorder.getEventsDir(), eventId);

        if (!eventFolder.exists() || !eventFolder.isDirectory()) {
            sendText(socket, "Not found", 404);
            return;
        }

        // If only the eventId is provided, delete the whole event folder
        if (parts.length == 1) {

            boolean ok = deleteRecursively(eventFolder);

            if (!ok) {
                sendText(socket, "Failed to delete event", 500);
                return;
            }

            sendText(socket, "Event deleted");
            return;
        }

        // Otherwise delete a specific file inside the event folder
        String fileName = parts[1];

        if (fileName.isEmpty() || fileName.contains("..") || fileName.contains("/")) {
            sendText(socket, "Invalid path", 400);
            return;
        }

        File target = new File(eventFolder, fileName);

        if (!target.exists() || !target.isFile()) {
            sendText(socket, "Not found", 404);
            return;
        }

        if (!target.delete()) {
            sendText(socket, "Failed to delete file", 500);
            return;
        }

        sendText(socket, "File deleted");
    }

    private boolean deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    if (!deleteRecursively(c)) return false;
                }
            }
        }
        return f.delete();
    }

    private byte[] readFileBytes(File file) throws IOException {

        try (InputStream inputStream = new FileInputStream(file)) {

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            return outputStream.toByteArray();
        }
    }

    private void sendFile(
            Socket socket,
            File file,
            String contentType
    ) throws IOException {

        byte[] data = readFileBytes(file);

        String headers =
                "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: " + contentType + "\r\n" +
                        "Content-Length: " + data.length + "\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";

        OutputStream outputStream = socket.getOutputStream();

        outputStream.write(headers.getBytes(StandardCharsets.UTF_8));
        outputStream.write(data);
        outputStream.flush();
    }

    private String getStatusPayload() {
        try {
            if (trapDetector == null) {
                JSONObject payload = new JSONObject();
                payload.put("calibrated", false);
                payload.put("traps", new JSONArray());
                return payload.toString();
            }

            JSONObject payload = new JSONObject();
            payload.put("calibrated", trapDetector.isCalibrated());
            payload.put("traps", trapDetector.getStatusJson());
            return payload.toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to build status payload", e);
            try {
                JSONObject payload = new JSONObject();
                payload.put("calibrated", false);
                payload.put("traps", new JSONArray());
                return payload.toString();
            } catch (Exception fallbackError) {
                Log.e(TAG, "Failed to build fallback status payload", fallbackError);
                return "{\"calibrated\":false,\"traps\":[]}";
            }
        }
    }

    private JSONArray sanitizeTrapList(String body) {
        JSONArray parsed = new JSONArray();

        try {
            JSONArray source = new JSONArray(body);
            HashMap<Integer, JSONObject> uniqueTraps = new HashMap<>();

            for (int i = 0; i < source.length(); i++) {
                JSONObject trap = source.optJSONObject(i);
                if (trap == null) {
                    continue;
                }

                int trapId = trap.optInt("id", i + 1);
                if (trapId < 1) {
                    trapId = i + 1;
                }

                uniqueTraps.put(trapId, trap);
            }

            for (JSONObject trap : uniqueTraps.values()) {
                parsed.put(trap);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to sanitize trap list; using original payload", e);
            try {
                return new JSONArray(body);
            } catch (Exception ignored) {
                return new JSONArray();
            }
        }

        return parsed;
    }

    private JSONObject getSavedThresholds() {
        try {
            String saved = preferences.getString(
                    "detector_thresholds",
                    "{\"baselineAdaptMeanDiffThreshold\":8.0,\"cellChangeThreshold\":15.0,\"closedMeanDiffThreshold\":18.0,\"closedChangedFractionThreshold\":0.18,\"minClosingFrames\":5,\"minClosingDurationMs\":800}"
            );
            return new JSONObject(saved);
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse saved thresholds, using defaults", e);
            return new JSONObject();
        }
    }

    private String getThresholdsPayload() {
        try {
            if (trapDetector == null) {
                return getSavedThresholds().toString();
            }
            return trapDetector.getThresholdSettingsJson().toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to build threshold payload", e);
            return "{\"baselineAdaptMeanDiffThreshold\":8.0,\"cellChangeThreshold\":15.0,\"closedMeanDiffThreshold\":18.0,\"closedChangedFractionThreshold\":0.18,\"minClosingFrames\":5,\"minClosingDurationMs\":800}";
        }
    }

    private String getBatteryPayload() {
        try {
            JSONObject payload = new JSONObject();
            payload.put("level", batteryLevelPercent);
            return payload.toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to build battery payload", e);
            return "{\"level\":0}";
        }
    }

    private void sendJson(
            Socket socket,
            String body
    ) throws IOException {

        byte[] bodyBytes =
                body.getBytes(
                        StandardCharsets.UTF_8
                );

        String headers =
                "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json; charset=UTF-8\r\n" +
                        "X-Zoom: " +
                        preferences.getFloat("zoom", 1.0f) +
                        "\r\n" +
                        "Content-Length: " +
                        bodyBytes.length +
                        "\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";

        OutputStream outputStream =
                socket.getOutputStream();

        outputStream.write(
                headers.getBytes(
                        StandardCharsets.UTF_8
                )
        );

        outputStream.write(bodyBytes);
        outputStream.flush();
    }

    private void sendText(
            Socket socket,
            String body
    ) throws IOException {
        sendText(socket, body, 200);
    }

    private void sendText(
            Socket socket,
            String body,
            int statusCode
    ) throws IOException {

        byte[] bodyBytes =
                body.getBytes(
                        StandardCharsets.UTF_8
                );

        String headers =
                "HTTP/1.1 " + statusCode + " " +
                        (statusCode == 200 ? "OK" : "Bad Request") + "\r\n" +
                        "Content-Type: text/plain; charset=UTF-8\r\n" +
                        "Content-Length: " +
                        bodyBytes.length +
                        "\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";

        OutputStream outputStream =
                socket.getOutputStream();

        outputStream.write(
                headers.getBytes(
                        StandardCharsets.UTF_8
                )
        );

        outputStream.write(bodyBytes);
        outputStream.flush();
    }

    private void sendPreview(Socket socket) {

        try {

            byte[] jpeg = latestJpeg;

            if (jpeg == null) {

                String body =
                        "Camera image not ready yet";

                byte[] bodyBytes =
                        body.getBytes(
                                StandardCharsets.UTF_8
                        );

                String response =
                        "HTTP/1.1 503 Service Unavailable\r\n" +
                                "Content-Type: text/plain; charset=UTF-8\r\n" +
                                "Content-Length: " +
                                bodyBytes.length +
                                "\r\n" +
                                "Connection: close\r\n" +
                                "\r\n";

                OutputStream outputStream =
                        socket.getOutputStream();

                outputStream.write(
                        response.getBytes(
                                StandardCharsets.UTF_8
                        )
                );

                outputStream.write(bodyBytes);
                outputStream.flush();

                return;
            }

            OutputStream outputStream =
                    socket.getOutputStream();

            String headers =
                    "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: image/jpeg\r\n" +
                            "Content-Length: " +
                            jpeg.length +
                            "\r\n" +
                            "Connection: close\r\n" +
                            "\r\n";

            outputStream.write(
                    headers.getBytes(
                            StandardCharsets.UTF_8
                    )
            );

            outputStream.write(jpeg);
            outputStream.flush();

        } catch (IOException e) {

            Log.e(
                    TAG,
                    "Failed to send preview",
                    e
            );
        }
    }

    private void sendHtml(Socket socket) {

        try {

            String body = loadHtml();

            byte[] bodyBytes =
                    body.getBytes(
                            StandardCharsets.UTF_8
                    );

            String headers =
                    "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/html; charset=UTF-8\r\n" +
                            "Content-Length: " +
                            bodyBytes.length +
                            "\r\n" +
                            "Connection: close\r\n" +
                            "\r\n";

            OutputStream outputStream =
                    socket.getOutputStream();

            outputStream.write(
                    headers.getBytes(
                            StandardCharsets.UTF_8
                    )
            );

            outputStream.write(bodyBytes);
            outputStream.flush();

        } catch (IOException e) {

            Log.e(
                    TAG,
                    "Failed to send HTML",
                    e
            );
        }
    }

    private String loadHtml() throws IOException {

        InputStream inputStream =
                context.getAssets().open("index.html");

        ByteArrayOutputStream outputStream =
                new ByteArrayOutputStream();

        byte[] buffer = new byte[4096];

        int bytesRead;

        while ((bytesRead =
                inputStream.read(buffer)) != -1) {

            outputStream.write(
                    buffer,
                    0,
                    bytesRead
            );
        }

        inputStream.close();

        return new String(
                outputStream.toByteArray(),
                StandardCharsets.UTF_8
        );
    }

    public void stop() {

        try {

            if (serverSocket != null) {
                serverSocket.close();
            }

        } catch (IOException e) {

            Log.e(
                    TAG,
                    "Error stopping server",
                    e
            );
        }
    }
}