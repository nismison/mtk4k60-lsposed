package com.eta.mtk4k60;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Runtime configuration for the module.
 *
 * <p>Values are read from {@code <target app files dir>/mtk4k60.conf} (key=value per line) and
 * cached for 1.5 s so the file can be edited from a root shell while the app is running.
 * Missing keys keep the defaults below.</p>
 */
final class Cfg {

    private static final String FILE_NAME = "mtk4k60.conf";
    private static final long CACHE_MS = 1500L;

    int width = 3840;
    int height = 2160;
    int fps = 60;
    int bitrate = 80000000;
    String hevc = "auto";   // auto | on | off
    String aeRange = "60-60"; // required by MTK HFPS: without it the sensor stays at 30fps
    int hfps = 1;           // inject vendor "hfpsMode"
    int smvr = 0;           // inject vendor "smvrMode"
    int forceQuality = 1;   // force back / wide lens video quality to 4K
    int log = 1;

    private static Cfg sCached;
    private static long sAt;

    static synchronized Cfg get(Context ctx) {
        long now = System.currentTimeMillis();
        if (sCached != null && now - sAt < CACHE_MS) {
            return sCached;
        }
        Cfg c = new Cfg();
        sAt = now;
        sCached = c;
        if (ctx == null) {
            return c;
        }
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        if (!f.isFile()) {
            return c;
        }
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String k = line.substring(0, eq).trim().toLowerCase();
                String v = line.substring(eq + 1).trim();
                try {
                    if ("width".equals(k)) {
                        c.width = Integer.parseInt(v);
                    } else if ("height".equals(k)) {
                        c.height = Integer.parseInt(v);
                    } else if ("fps".equals(k)) {
                        c.fps = Integer.parseInt(v);
                    } else if ("bitrate".equals(k)) {
                        c.bitrate = Integer.parseInt(v);
                    } else if ("hevc".equals(k)) {
                        c.hevc = v.toLowerCase();
                    } else if ("ae_range".equals(k)) {
                        c.aeRange = v;
                    } else if ("hfps".equals(k)) {
                        c.hfps = Integer.parseInt(v);
                    } else if ("smvr".equals(k)) {
                        c.smvr = Integer.parseInt(v);
                    } else if ("force_quality".equals(k)) {
                        c.forceQuality = Integer.parseInt(v);
                    } else if ("log".equals(k)) {
                        c.log = Integer.parseInt(v);
                    }
                } catch (Throwable ignored) {
                    // keep the default for a broken value
                }
            }
        } catch (Throwable ignored) {
            // keep defaults
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return c;
    }

    static synchronized void invalidate() {
        sCached = null;
        sAt = 0L;
    }
}
