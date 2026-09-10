package com.newvision.venusflytrapmonitor;

import android.graphics.Bitmap;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class TrapDetector {

    private static final String TAG = "TrapDetector";

    private static final long CALIBRATION_TIME_MS = 15000;
    private static final long LOG_INTERVAL_MS = 2000;

    // Each ROI is resampled into a GRID_SIZE x GRID_SIZE grid of average
    // brightness cells instead of a single overall brightness number. This
    // gives a coarse spatial signature of the ROI, so a shape change (a trap
    // closing) can start to be distinguished from a uniform lighting change,
    // which affects all cells roughly equally.
    private static final int GRID_SIZE = 12;
    private static final int GRID_CELLS = GRID_SIZE * GRID_SIZE;

    // How fast the baseline drifts toward the current frame when no event is
    // suspected. Small on purpose so brief flickers don't move the baseline.
    private static final float BASELINE_ADAPT_ALPHA = 0.02f;

    public static class Thresholds {
        public float baselineAdaptMeanDiffThreshold = 8.0f;
        public float cellChangeThreshold = 15.0f;
        public float closedMeanDiffThreshold = 18.0f;
        public float closedChangedFractionThreshold = 0.18f;

        public JSONObject toJson() {
            try {
                JSONObject json = new JSONObject();
                json.put("baselineAdaptMeanDiffThreshold", baselineAdaptMeanDiffThreshold);
                json.put("cellChangeThreshold", cellChangeThreshold);
                json.put("closedMeanDiffThreshold", closedMeanDiffThreshold);
                json.put("closedChangedFractionThreshold", closedChangedFractionThreshold);
                return json;
            } catch (JSONException e) {
                return new JSONObject();
            }
        }

        public static Thresholds fromJson(JSONObject json) {
            if (json == null) {
                return null;
            }

            Thresholds thresholds = new Thresholds();

            try {
                if (json.has("baselineAdaptMeanDiffThreshold")) {
                    thresholds.baselineAdaptMeanDiffThreshold = (float) json.getDouble("baselineAdaptMeanDiffThreshold");
                }

                if (json.has("cellChangeThreshold")) {
                    thresholds.cellChangeThreshold = (float) json.getDouble("cellChangeThreshold");
                }

                if (json.has("closedMeanDiffThreshold")) {
                    thresholds.closedMeanDiffThreshold = (float) json.getDouble("closedMeanDiffThreshold");
                }

                if (json.has("closedChangedFractionThreshold")) {
                    thresholds.closedChangedFractionThreshold = (float) json.getDouble("closedChangedFractionThreshold");
                }
            } catch (JSONException e) {
                return thresholds;
            }

            return thresholds;
        }
    }

    private final JSONArray traps;

    // baselineGrids[roiIndex][cellIndex], brightness 0-255 per cell.
    private final float[][] baselineGrids;
    private final int[] calibrationSampleCount;
    private final boolean[] closedStates;
    private final float[] lastMeanDiffs;
    private final float[] lastChangedFractions;

    private long calibrationStartTime = 0;
    private boolean calibrated = false;
    private long lastLogTime = 0;
    private Thresholds thresholds = new Thresholds();

    public TrapDetector(JSONArray traps) {

        this.traps = traps;

        int trapCount = traps.length();

        baselineGrids = new float[trapCount][GRID_CELLS];
        calibrationSampleCount = new int[trapCount];
        closedStates = new boolean[trapCount];
        lastMeanDiffs = new float[trapCount];
        lastChangedFractions = new float[trapCount];
    }

    public boolean hasConfiguredRois() {
        return traps != null && traps.length() > 0;
    }

    public boolean isCalibrated() {
        return calibrated;
    }

    public void setThresholds(Thresholds newThresholds) {
        if (newThresholds != null) {
            thresholds = newThresholds;
        }
    }

    public void setThresholds(JSONObject config) {
        Thresholds parsed = Thresholds.fromJson(config);
        if (parsed != null) {
            thresholds = parsed;
        }
    }

    public Thresholds getThresholds() {
        return thresholds;
    }

    public JSONObject getThresholdSettingsJson() {
        return thresholds.toJson();
    }

    public JSONArray getStatusJson() {
        JSONArray items = new JSONArray();

        for (int i = 0; i < traps.length(); i++) {
            JSONObject trap = traps.optJSONObject(i);
            if (trap == null) {
                continue;
            }

            try {
                JSONObject item = new JSONObject();
                item.put("id", trap.optInt("id", i + 1));
                item.put("closed", closedStates[i]);
                item.put("meanDiff", lastMeanDiffs[i]);
                item.put("changedFraction", lastChangedFractions[i]);
                item.put("calibrated", calibrated);
                items.put(item);
            } catch (JSONException e) {
                Log.w(TAG, "Failed to encode trap status for ROI " + i, e);
            }
        }

        return items;
    }

    public boolean isTrapClosed(int trapId) {
        for (int i = 0; i < traps.length(); i++) {
            JSONObject trap = traps.optJSONObject(i);
            if (trap != null && trap.optInt("id", i + 1) == trapId) {
                return closedStates[i];
            }
        }
        return false;
    }

    public void analyzeFrame(Bitmap bitmap) {

        try {

            if (calibrationStartTime == 0) {
                calibrationStartTime = System.currentTimeMillis();
            }

            long now = System.currentTimeMillis();

            boolean shouldLog = calibrated && (now - lastLogTime >= LOG_INTERVAL_MS);

            for (int i = 0; i < traps.length(); i++) {

                JSONObject trap = traps.getJSONObject(i);

                int[] bounds = roiPixelBounds(trap, bitmap);

                float[] currentGrid = computeGrid(
                        bitmap,
                        bounds[0],
                        bounds[1],
                        bounds[2],
                        bounds[3]
                );

                if (!calibrated) {

                    for (int c = 0; c < GRID_CELLS; c++) {
                        baselineGrids[i][c] += currentGrid[c];
                    }

                    calibrationSampleCount[i]++;

                } else {

                    float meanDiff = 0f;
                    int changedCells = 0;

                    for (int c = 0; c < GRID_CELLS; c++) {

                        float cellDiff = Math.abs(currentGrid[c] - baselineGrids[i][c]);
                        meanDiff += cellDiff;

                        if (cellDiff > thresholds.cellChangeThreshold) {
                            changedCells++;
                        }
                    }

                    meanDiff /= GRID_CELLS;
                    float changedFraction = (float) changedCells / GRID_CELLS;
                    lastMeanDiffs[i] = meanDiff;
                    lastChangedFractions[i] = changedFraction;

                    boolean trapClosed = meanDiff >= thresholds.closedMeanDiffThreshold
                            && changedFraction >= thresholds.closedChangedFractionThreshold;
                    closedStates[i] = trapClosed;

                    if (meanDiff < thresholds.baselineAdaptMeanDiffThreshold) {

                        for (int c = 0; c < GRID_CELLS; c++) {
                            baselineGrids[i][c] =
                                    baselineGrids[i][c] * (1f - BASELINE_ADAPT_ALPHA)
                                            + currentGrid[c] * BASELINE_ADAPT_ALPHA;
                        }
                    }

                    if (shouldLog) {
                        Log.d(TAG, "ROI " + trap.getInt("id")
                                + ": meanDiff=" + meanDiff
                                + ", changedFraction=" + changedFraction
                                + ", closed=" + trapClosed);
                    }
                }
            }

            if (!calibrated && now - calibrationStartTime >= CALIBRATION_TIME_MS) {

                for (int i = 0; i < traps.length(); i++) {

                    if (calibrationSampleCount[i] > 0) {

                        for (int c = 0; c < GRID_CELLS; c++) {
                            baselineGrids[i][c] /= calibrationSampleCount[i];
                        }
                    }

                    Log.d(TAG, "ROI " + traps.getJSONObject(i).getInt("id")
                            + ": baseline grid captured ("
                            + calibrationSampleCount[i] + " samples)");
                }

                calibrated = true;
                Log.d(TAG, "Calibration complete");
            }

            if (shouldLog) {
                lastLogTime = now;
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to analyze ROI", e);
        }
    }

    private int[] roiPixelBounds(JSONObject trap, Bitmap bitmap) throws Exception {

        float left = (float) trap.getDouble("left");
        float top = (float) trap.getDouble("top");
        float roiWidth = (float) trap.getDouble("width");
        float roiHeight = (float) trap.getDouble("height");

        int x = Math.round(left * bitmap.getWidth());
        int y = Math.round(top * bitmap.getHeight());
        int width = Math.round(roiWidth * bitmap.getWidth());
        int height = Math.round(roiHeight * bitmap.getHeight());

        return new int[]{x, y, width, height};
    }

    // Resamples the ROI into a GRID_SIZE x GRID_SIZE grid of average
    // grayscale brightness values (0-255), giving a coarse spatial signature
    // instead of a single overall brightness number.
    private float[] computeGrid(Bitmap bitmap, int x, int y, int width, int height) {

        float[] grid = new float[GRID_CELLS];

        int right = Math.min(x + width, bitmap.getWidth());
        int bottom = Math.min(y + height, bitmap.getHeight());
        int left = Math.max(0, x);
        int top = Math.max(0, y);

        if (left >= right || top >= bottom) {
            return grid;
        }

        float cellWidth = (right - left) / (float) GRID_SIZE;
        float cellHeight = (bottom - top) / (float) GRID_SIZE;

        for (int row = 0; row < GRID_SIZE; row++) {

            for (int col = 0; col < GRID_SIZE; col++) {

                int cellLeft = left + Math.round(col * cellWidth);
                int cellRight = left + Math.round((col + 1) * cellWidth);
                int cellTop = top + Math.round(row * cellHeight);
                int cellBottom = top + Math.round((row + 1) * cellHeight);

                grid[row * GRID_SIZE + col] =
                        averageBrightness(bitmap, cellLeft, cellTop, cellRight, cellBottom);
            }
        }

        return grid;
    }

    private float averageBrightness(Bitmap bitmap, int left, int top, int right, int bottom) {

        right = Math.min(right, bitmap.getWidth());
        bottom = Math.min(bottom, bitmap.getHeight());
        left = Math.max(0, left);
        top = Math.max(0, top);

        if (left >= right || top >= bottom) {
            return 0;
        }

        long total = 0;
        long samples = 0;

        int step = Math.max(1, Math.min(right - left, bottom - top) / 6);

        for (int py = top; py < bottom; py += step) {

            for (int px = left; px < right; px += step) {

                int pixel = bitmap.getPixel(px, py);

                int red = (pixel >> 16) & 0xFF;
                int green = (pixel >> 8) & 0xFF;
                int blue = pixel & 0xFF;

                total += (red + green + blue) / 3;
                samples++;
            }
        }

        if (samples == 0) {
            return 0;
        }

        return (float) total / samples;
    }
}