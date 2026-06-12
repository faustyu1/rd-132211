package com.mojang.rubydung;

import java.io.*;
import java.util.Properties;

public class Settings {
    public boolean vsync       = true;
    public int     renderDist  = 2;
    public int     sensitivity = 2;
    public int     fovIndex    = 0;
    public int     resIndex    = 1;   // index into RESOLUTIONS
    public boolean fullscreen  = false;

    public static final float[]  FOV_VALUES  = {70, 80, 90, 100, 110};
    // render distance: chunk radius loaded around the player + matching fog end (blocks)
    public static final int[]    DIST_CHUNKS = {3, 6, 10, 16, 24};
    public static final float[]  DIST_END    = {48, 96, 152, 240, 360};
    public static final float[]  SENS_VALUES = {0.08f, 0.12f, 0.15f, 0.22f};
    public static final String[] DIST_LABELS = {"TINY", "SHORT", "NORMAL", "FAR", "EXTREME"};
    public static final String[] SENS_LABELS = {"LOW", "MED", "HIGH", "MAX"};
    public static final String[] FOV_LABELS  = {"70", "80", "90", "100", "110"};

    /** Chunk radius to stream around the player for the current render-distance setting. */
    public int chunkRadius() { return DIST_CHUNKS[renderDist]; }

    public static final int[][] RESOLUTIONS = {
        {854, 480}, {1024, 768}, {1280, 720}, {1280, 800},
        {1440, 900}, {1600, 900}, {1920, 1080}, {2560, 1440}
    };

    public static String resLabel(int idx) {
        return RESOLUTIONS[idx][0] + "x" + RESOLUTIONS[idx][1];
    }

    private static final String FILE = "settings.properties";

    public void load() {
        var p = new Properties();
        try (var r = new FileReader(FILE)) {
            p.load(r);
            vsync      = Boolean.parseBoolean(p.getProperty("vsync",       "true"));
            fullscreen = Boolean.parseBoolean(p.getProperty("fullscreen",  "false"));
            renderDist = Math.clamp(Integer.parseInt(p.getProperty("renderDist",  "2")), 0, DIST_END.length - 1);
            sensitivity= Math.clamp(Integer.parseInt(p.getProperty("sensitivity", "2")), 0, SENS_VALUES.length - 1);
            fovIndex   = Math.clamp(Integer.parseInt(p.getProperty("fovIndex",    "0")), 0, FOV_VALUES.length - 1);
            resIndex   = Math.clamp(Integer.parseInt(p.getProperty("resIndex",    "1")), 0, RESOLUTIONS.length - 1);
        } catch (Exception ignored) {}
    }

    public void save() {
        var p = new Properties();
        p.setProperty("vsync",       String.valueOf(vsync));
        p.setProperty("fullscreen",  String.valueOf(fullscreen));
        p.setProperty("renderDist",  String.valueOf(renderDist));
        p.setProperty("sensitivity", String.valueOf(sensitivity));
        p.setProperty("fovIndex",    String.valueOf(fovIndex));
        p.setProperty("resIndex",    String.valueOf(resIndex));
        try (var w = new FileWriter(FILE)) { p.store(w, null); } catch (Exception ignored) {}
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
