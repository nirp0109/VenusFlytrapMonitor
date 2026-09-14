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

    public enum TrapState {
        OPEN,
        CLOSING,
        CLOSED
    }

    public static class Thresholds {
        public float baselineAdaptMeanDiffThreshold = 8.0f;
        public float cellChangeThreshold = 15.0f;
        public float closedMeanDiffThreshold = 18.0f;
        public float closedChangedFractionThreshold = 0.18f;

        // Hysteresis: once the per-frame signal first crosses the thresholds
        // above (OPEN -> CLOSING), CLOSING only confirms to CLOSED once BOTH
        // of these are satisfied while the signal stays active.
        public int minClosingFrames = 5;
        public long minClosingDurationMs = 800;

        public JSONObject toJson() {
            try {
                JSONObject json = new JSONObject();
                json.put("baselineAdaptMeanDiffThreshold", baselineAdaptMeanDiffThreshold);
                json.put("cellChangeThreshold", cellChangeThreshold);
                json.put("closedMeanDiffThreshold", closedMeanDiffThreshold);
                json.put("closedChangedFractionThreshold", closedChangedFractionThreshold);
                json.put("minClosingFrames", minClosingFrames);
                json.put("minClosingDurationMs", minClosingDurationMs);
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

                if (json.has("minClosingFrames")) {
                    thresholds.minClosingFrames = Math.max(1, json.getInt("minClosingFrames"));
                }

                if (json.has("minClosingDurationMs")) {
                    thresholds.minClosingDurationMs = Math.max(0, json.getLong("minClosingDurationMs"));
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
    private final TrapState[] trapStates;
    private final float[] lastMeanDiffs;
    private final float[] lastChangedFractions;
    private final float[] lastLightingShifts;

    // Hysteresis bookkeeping while a ROI is in CLOSING.
    private final long[] closingStartTime;
    private final int[] closingFrameCount;

    private long calibrationStartTime = 0;
    private boolean calibrated = false;
    private long lastLogTime = 0;
    private Thresholds thresholds = new Thresholds();

    public TrapDetector(JSONArray traps) {

        this.traps = traps;

        int trapCount = traps.length();

        baselineGrids = new float[trapCount][GRID_CELLS];
        calibrationSampleCount = new int[trapCount];
        trapStates = new TrapState[trapCount];
        lastMeanDiffs = new float[trapCount];
        lastChangedFractions = new float[trapCount];
        lastLightingShifts = new float[trapCount];
        closingStartTime = new long[trapCount];
        closingFrameCount = new int[trapCount];

        for (int i = 0; i < trapCount; i++) {
            trapStates[i] = TrapState.OPEN;
        }
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
                item.put("state", trapStates[i].name());
                item.put("closed", trapStates[i] == TrapState.CLOSED);
                item.put("meanDiff", lastMeanDiffs[i]);
                item.put("changedFraction", lastChangedFractions[i]);
                // Diagnostic only: how much of the frame's overall
                // brightness shift (e.g. a light being turned on/off) was
                // subtracted out before computing meanDiff/changedFraction
                // above. A large lightingShift with a small meanDiff is the
                // normalization working as intended.
                item.put("lightingShift", lastLightingShifts[i]);
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
                return trapStates[i] == TrapState.CLOSED;
            }
        }
        return false;
    }

    public TrapState getTrapState(int trapId) {
        for (int i = 0; i < traps.length(); i++) {
            JSONObject trap = traps.optJSONObject(i);
            if (trap != null && trap.optInt("id", i + 1) == trapId) {
                return trapStates[i];
            }
        }
        return TrapState.OPEN;
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

                    // Uniform-illumination normalization: estimate how much
                    // the ROI's overall brightness shifted vs baseline (e.g.
                    // a room light being switched on/off shifts every cell
                    // by roughly the same amount), and subtract that shift
                    // out before comparing individual cells. This way a
                    // global lighting change - which affects all cells
                    // equally - cancels out to near zero, while a real trap
                    // closure - which changes some cells much more than
                    // others - still shows up clearly.
                    float currentMean = 0f;
                    float baselineMean = 0f;

                    for (int c = 0; c < GRID_CELLS; c++) {
                        currentMean += currentGrid[c];
                        baselineMean += baselineGrids[i][c];
                    }

                    currentMean /= GRID_CELLS;
                    baselineMean /= GRID_CELLS;

                    float lightingShift = currentMean - baselineMean;
                    lastLightingShifts[i] = lightingShift;

                    float meanDiff = 0f;
                    int changedCells = 0;

                    for (int c = 0; c < GRID_CELLS; c++) {

                        float normalizedCell = currentGrid[c] - lightingShift;
                        float cellDiff = Math.abs(normalizedCell - baselineGrids[i][c]);
                        meanDiff += cellDiff;

                        if (cellDiff > thresholds.cellChangeThreshold) {
                            changedCells++;
                        }
                    }

                    meanDiff /= GRID_CELLS;
                    float changedFraction = (float) changedCells / GRID_CELLS;
                    lastMeanDiffs[i] = meanDiff;
                    lastChangedFractions[i] = changedFraction;

                    boolean signalActive = meanDiff >= thresholds.closedMeanDiffThreshold
                            && changedFraction >= thresholds.closedChangedFractionThreshold;

                    TrapState previousState = trapStates[i];
                    updateTrapState(i, signalActive, now);

                    // Baseline is only allowed to adapt while OPEN, using the
                    // normalized meanDiff - so genuine lighting shifts (now
                    // already cancelled out above) no longer need to "wait"
                    // for this slow adapt to correct them; this remains for
                    // real slow spatial drift (e.g. a shadow's path changing
                    // over the day, minor camera drift, dust).
                    if (trapStates[i] == TrapState.OPEN
                            && meanDiff < thresholds.baselineAdaptMeanDiffThreshold) {

                        for (int c = 0; c < GRID_CELLS; c++) {
                            baselineGrids[i][c] =
                                    baselineGrids[i][c] * (1f - BASELINE_ADAPT_ALPHA)
                                            + currentGrid[c] * BASELINE_ADAPT_ALPHA;
                        }
                    }

                    if (shouldLog || trapStates[i] != previousState) {
                        Log.d(TAG, "ROI " + trap.getInt("id")
                                + ": meanDiff=" + meanDiff
                                + ", changedFraction=" + changedFraction
                                + ", lightingShift=" + lightingShift
                                + ", state=" + trapStates[i]
                                + (trapStates[i] == TrapState.CLOSING
                                ? " (frames=" + closingFrameCount[i]
                                + ", elapsedMs=" + (now - closingStartTime[i]) + ")"
                                : ""));
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

    // Advances trapStates[i] through OPEN -> CLOSING -> CLOSED based on the
    // current frame's signalActive flag. CLOSED is sticky (a trap does not
    // spontaneously revert once confirmed shut); CLOSING reverts straight
    // back to OPEN if the signal drops before confirmation, treating it as
    // a spurious spike rather than a real event.
    private void updateTrapState(int i, boolean signalActive, long now) {

        switch (trapStates[i]) {

            case OPEN:
                if (signalActive) {
                    trapStates[i] = TrapState.CLOSING;
                    closingStartTime[i] = now;
                    closingFrameCount[i] = 1;
                }
                break;

            case CLOSING:
                if (signalActive) {
                    closingFrameCount[i]++;

                    long elapsed = now - closingStartTime[i];

                    if (closingFrameCount[i] >= thresholds.minClosingFrames
                            && elapsed >= thresholds.minClosingDurationMs) {
                        trapStates[i] = TrapState.CLOSED;
                    }
                } else {
                    trapStates[i] = TrapState.OPEN;
                    closingFrameCount[i] = 0;
                }
                break;

            case CLOSED:
                // Sticky: revisit only if automatic reopening is needed.
                break;
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