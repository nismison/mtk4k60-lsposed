package com.eta.mtk4k60;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaRecorder;
import android.util.Range;
import android.util.Size;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * LSPosed module that adds a third "camera API" level to Secret Video Recorder:
 * {@code MTK 4K60（厂商高速）}, implemented with the MediaTek vendor high-frame-rate
 * session tags ({@code com.mediatek.streamingfeature.hfpsMode} / {@code smvrMode})
 * that the stock Xiaomi/MTK camera uses for 4K60 recording.
 *
 * <p>The public Camera2 API on this SoC only exposes 3840x2160 at 30 fps and 60 fps
 * only for the constrained high-speed session (1080p/720p), which is exactly why the
 * unmodified app silently downgrades a "4K + 60fps" request to 1080p60.</p>
 */
public final class HookEntry implements IXposedHookLoadPackage {

    static final String TAG = "MTK4K60";
    static final String TARGET = "com.kimcy929.secretvideorecorder";

    /* Secret Video Recorder internals (v1.3.6.3) */
    static final String PREFS = "SecretVideoRecorder";
    static final String MODE_KEY = "MTK_HFPS";
    static final String API2_KEY = "ENABLE_CAMERA_API2";
    static final int ARRAY_CAMERA_APIS = 0x7f03000d;        // R.array.camera_apis
    static final int STRING_CAMERA_API_DESC = 0x7f12003b;   // R.string.camera_api_description
    static final String LABEL = "MTK 4K60（厂商高速）";

    /* MediaRecorder video encoder constants (avoid deprecation noise) */
    private static final int ENC_H264 = 2;
    private static final int ENC_HEVC = 5;

    static Context app;
    static SharedPreferences prefs;

    /** true while the recorder about to be configured is the 4K60 one (back / wide lens). */
    static volatile boolean forceRecorder;
    static volatile boolean loggedPipeline;
    static volatile String lastVendorLog;
    static volatile boolean loggedKeys;

    private static volatile boolean installed;

    @Override
    public void handleLoadPackage(LoadPackageParam lp) throws Throwable {
        if (!TARGET.equals(lp.packageName)) {
            return;
        }
        XposedBridge.log(TAG + ": package loaded, pid=" + android.os.Process.myPid());
        try {
            app = currentApplication();
        } catch (Throwable t) {
            app = null;
        }
        if (app != null) {
            install(lp);
            return;
        }
        // The module is loaded before Application#attach in most processes: wait for the
        // app context, then register the real hooks.
        try {
            XposedHelpers.findAndHookMethod("android.app.Instrumentation", lp.classLoader,
                    "callApplicationOnCreate", android.app.Application.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            Object a = p.args[0];
                            if (!(a instanceof android.app.Application)) {
                                return;
                            }
                            app = (android.app.Application) a;
                            install(lp);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": cannot wait for application: " + t);
        }
    }

    private static void install(LoadPackageParam lp) {
        if (installed) {
            return;
        }
        installed = true;
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        XposedBridge.log(TAG + ": application ready pid=" + android.os.Process.myPid()
                + " mode=" + mode() + " cfg=" + describeCfg());
        log("loaded, mode=" + mode() + ", " + describeCfg());

        run("ui labels", new Runnable() {
            @Override
            public void run() {
                hookUiLabels(lp.classLoader);
            }
        });
        run("settings dialog", new Runnable() {
            @Override
            public void run() {
                try {
                    hookSettings(lp.classLoader);
                } catch (Throwable t) {
                    log("hook settings: " + t);
                }
            }
        });
        run("session", new Runnable() {
            @Override
            public void run() {
                try {
                    hookSession(lp.classLoader);
                } catch (Throwable t) {
                    log("hook session: " + t);
                }
            }
        });
        run("media recorder", new Runnable() {
            @Override
            public void run() {
                hookMediaRecorder();
            }
        });
        run("session params", new Runnable() {
            @Override
            public void run() {
                hookSessionParameters();
            }
        });
    }

    private static void run(String name, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            log("hook [" + name + "] failed: " + t);
        }
    }

    /* ------------------------------------------------------------------ state */

    static boolean mode() {
        try {
            return prefs != null && prefs.getBoolean(MODE_KEY, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 0 = original camera API, 1 = Camera2, 2 = MTK 4K60. */
    static int apiIndex() {
        try {
            if (mode()) {
                return 2;
            }
            return prefs != null && prefs.getBoolean(API2_KEY, true) ? 1 : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    static String describeCfg() {
        Cfg c = Cfg.get(app);
        return c.width + "x" + c.height + "@" + c.fps
                + " bitrate=" + c.bitrate + " hevc=" + c.hevc
                + " hfps=" + c.hfps + " smvr=" + c.smvr
                + " ae=\"" + c.aeRange + "\" forceQuality=" + c.forceQuality;
    }

    static void log(String s) {
        if (Cfg.get(app).log == 0) {
            return;
        }
        XposedBridge.log(TAG + ": " + s);
        try {
            if (app == null) {
                return;
            }
            File f = new File(app.getFilesDir(), "mtk4k60.log");
            if (f.length() > 256 * 1024) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            FileWriter w = new FileWriter(f, true);
            String ts = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            w.write(ts + " " + s + "\n");
            w.close();
        } catch (Throwable ignored) {
            // logging must never break a hook
        }
    }

    static Context currentApplication() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Method m = at.getMethod("currentApplication");
        return (Context) m.invoke(null);
    }

    /* ------------------------------------------------------------------- UI */

    private static void hookUiLabels(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(Resources.class, "getTextArray", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        Object arg = p.args[0];
                        if (!(arg instanceof Integer) || ((Integer) arg) != ARRAY_CAMERA_APIS) {
                            return;
                        }
                        CharSequence[] r = (CharSequence[]) p.getResult();
                        if (r == null || r.length != 2) {
                            return;
                        }
                        CharSequence[] out = new CharSequence[3];
                        out[0] = r[0];
                        out[1] = r[1];
                        out[2] = LABEL;
                        p.setResult(out);
                    }
                });

        XposedHelpers.findAndHookMethod(Resources.class, "getStringArray", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        Object arg = p.args[0];
                        if (!(arg instanceof Integer) || ((Integer) arg) != ARRAY_CAMERA_APIS) {
                            return;
                        }
                        String[] r = (String[]) p.getResult();
                        if (r == null || r.length != 2) {
                            return;
                        }
                        String[] out = new String[3];
                        out[0] = r[0];
                        out[1] = r[1];
                        out[2] = LABEL;
                        p.setResult(out);
                    }
                });

        // The single-choice dialog itself: force the checked item to our tri-state value.
        try {
            Class<?> builder = cl.loadClass("c7.b");
            XposedHelpers.findAndHookMethod(builder, "z", int.class, int.class,
                    DialogInterface.OnClickListener.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        Object arg = p.args[0];
                            if (arg instanceof Integer && ((Integer) arg) == ARRAY_CAMERA_APIS) {
                                p.args[1] = Integer.valueOf(apiIndex());
                            }
                        }
                    });
        } catch (Throwable t) {
            log("hook dialog builder: " + t);
        }

        // Cosmetic: the summary line of the "Camera API" row reads labels[C() ? 1 : 0].
        XposedHelpers.findAndHookMethod(TextView.class, "setText", CharSequence.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (!mode()) {
                            return;
                        }
                        Object t = p.args[0];
                        if (!(t instanceof CharSequence)) {
                            return;
                        }
                        String s = t.toString();
                        if (s.length() > 400 || s.indexOf('\n') < 0) {
                            return;
                        }
                        try {
                            String desc = app.getResources().getString(STRING_CAMERA_API_DESC);
                            if (desc != null && !desc.isEmpty() && s.contains(desc)) {
                                p.args[0] = s.substring(0, s.lastIndexOf('\n') + 1) + LABEL;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
    }

    private static void hookSettings(ClassLoader cl) throws Throwable {
        final Class<?> settings =
                cl.loadClass("com.kimcy929.secretvideorecorder.tasksettings.SettingsActivity");
        XposedHelpers.findAndHookMethod(settings, "A2", settings, DialogInterface.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        Object a = p.args[2];
                        if (!(a instanceof Integer)) {
                            return;
                        }
                        int idx = (Integer) a;
                        if (idx == 2) {
                            prefs.edit()
                                    .putBoolean(MODE_KEY, true)
                                    .putBoolean(API2_KEY, true)
                                    .commit();
                            // Let the app call O1(true) so it re-renders the Camera2-only rows.
                            p.args[2] = Integer.valueOf(1);
                        } else {
                            prefs.edit().putBoolean(MODE_KEY, false).commit();
                        }
                        Cfg.invalidate();
                        log("camera API selection -> index=" + idx + " mode=" + mode());
                    }
                });
    }

    /* --------------------------------------------------------------- session */

    private static void hookSession(ClassLoader cl) throws Throwable {
        final Cfg cfg = Cfg.get(app);

        // 1. Keep the app away from its constrained-high-speed (1080p/720p) session, but
        //    leave the "video frame rate" preference untouched so the UI still shows 60fps.
        //    highFps = Y0(characteristics, 0) && frameRatePreference > 0
        Class<?> settings = cl.loadClass("i9.c");
        Class<?> sessionCls = cl.loadClass("r8.a");
        XposedHelpers.findAndHookMethod(sessionCls, "Y0",
                CameraCharacteristics.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        Object lvl = p.args[1];
                        if (mode() && lvl instanceof Integer && ((Integer) lvl) == 0) {
                            p.setResult(Boolean.FALSE);
                        }
                    }
                });

        // 2. Force 4K for back / wide lens video quality (front camera untouched).

        if (cfg.forceQuality != 0) {
            String[] qualityGetters = {"k", "g1"};   // BACK_VIDEO_QUALITY, WIDE_LENS_VIDEO_QUALITY
            for (String name : qualityGetters) {
                XposedHelpers.findAndHookMethod(settings, name, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        if (mode()) {
                            p.setResult(Integer.valueOf(5));   // UHD 4K
                        }
                    }
                });
            }
        }

        // 3. Decide whether the recorder that is being prepared is the 4K60 one.
        Class<?> session = sessionCls;
        XposedHelpers.findAndHookMethod(session, "n1", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                forceRecorder = mode() && canForce4k(p.thisObject);
                loggedPipeline = false;
                if (mode()) {
                    log("record prepare: force4k60=" + forceRecorder + " " + describeCfg());
                }
            }
        });

        // 4. Vendor tags on the repeating capture request (and right before it is used).
        XC_MethodHook vendorHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                applyVendorToRequest(p.thisObject);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                applyVendorToRequest(p.thisObject);
            }
        };
        XposedHelpers.findAndHookMethod(session, "m1", vendorHook);
        XposedHelpers.findAndHookMethod(session, "l1", vendorHook);
    }

    private static boolean canForce4k(Object session) {
        try {
            int facing = XposedHelpers.getIntField(session, "W");
            if (facing == 0) {
                log("front camera session: keep stock behaviour");
                return false;
            }
            Cfg cfg = Cfg.get(app);
            Object chObj = XposedHelpers.getObjectField(session, "S");
            if (!(chObj instanceof CameraCharacteristics)) {
                return false;
            }
            StreamConfigurationMap map = ((CameraCharacteristics) chObj)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                return false;
            }
            Size[] sizes = map.getOutputSizes(MediaRecorder.class);
            if (sizes == null) {
                return false;
            }
            for (Size s : sizes) {
                if (s.getWidth() == cfg.width && s.getHeight() == cfg.height) {
                    return true;
                }
            }
            log("camera facing " + facing + " has no "
                    + cfg.width + "x" + cfg.height + " MediaRecorder size");
            return false;
        } catch (Throwable t) {
            log("canForce4k: " + t);
            return false;
        }
    }

    private static void applyVendorToRequest(Object session) {
        if (!mode() || !forceRecorder) {
            return;
        }
        try {
            Object b = XposedHelpers.getObjectField(session, "R");
            Object ch = XposedHelpers.getObjectField(session, "S");
            if (b instanceof CaptureRequest.Builder && ch instanceof CameraCharacteristics) {
                applyVendor((CaptureRequest.Builder) b, (CameraCharacteristics) ch);
            }
        } catch (Throwable t) {
            log("applyVendorToRequest: " + t);
        }
    }

    /* ------------------------------------------------------- vendor metadata */

    private static void applyVendor(CaptureRequest.Builder b, CameraCharacteristics ch) {
        Cfg cfg = Cfg.get(app);
        StringBuilder sb = new StringBuilder();
        if (cfg.hfps != 0) {
            sb.append("hfpsMode=").append(setVendor(b, ch, "hfpsMode", cfg.hfps) ? "ok" : "nokey");
        }
        if (cfg.smvr != 0) {
            sb.append(" smvrMode=").append(setVendor(b, ch, "smvrMode", cfg.smvr) ? "ok" : "nokey");
        }
        String ae = cfg.aeRange;
        if (ae != null && ae.indexOf('-') > 0) {
            try {
                String[] parts = ae.split("-");
                int lo = Integer.parseInt(parts[0].trim());
                int hi = Integer.parseInt(parts[1].trim());
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        new Range<Integer>(Integer.valueOf(lo), Integer.valueOf(hi)));
                sb.append(" aeRange=").append(lo).append("-").append(hi);
            } catch (Throwable t) {
                sb.append(" aeRange=fail(").append(t).append(')');
            }
        }
        if (!loggedPipeline) {
            loggedPipeline = true;
            log("vendor request: " + sb);
        } else if (!sb.toString().equals(lastVendorLog)) {
            lastVendorLog = sb.toString();
            log("vendor request changed: " + sb);
        }
    }

    private static boolean setVendor(CaptureRequest.Builder b, CameraCharacteristics ch,
                                    String suffix, int value) {
        for (CaptureRequest.Key<?> k : vendorKeys(ch)) {
            String name = k.getName();
            if (name == null) {
                continue;
            }
            if (!name.equals(suffix) && !name.endsWith("." + suffix)) {
                continue;
            }
            // MTK vendor keys are declared as arrays (int[] / byte[]), a plain Integer
            // is rejected with "Not an array", so try every plausible container.
            Object[] candidates = new Object[]{
                    Integer.valueOf(value),
                    new int[]{value},
                    new byte[]{(byte) value},
                    new long[]{value}
            };
            String[] kinds = new String[]{"int", "int[]", "byte[]", "long[]"};
            for (int i = 0; i < candidates.length; i++) {
                try {
                    //noinspection unchecked,rawtypes
                    b.set((CaptureRequest.Key) (Object) k, candidates[i]);
                    log("vendor key set: " + name + "=" + value + " (" + kinds[i] + ")");
                    return true;
                } catch (Throwable t) {
                    if (i == candidates.length - 1) {
                        log("vendor key rejected: " + name + " -> " + t);
                    }
                }
            }
        }
        return false;
    }

    /**
     * Vendor tags are not part of {@code CameraCharacteristics.getKeys()} on MTK devices:
     * they are exposed through the request/session key lists instead.
     */
    private static java.util.List<CaptureRequest.Key<?>> vendorKeys(CameraCharacteristics ch) {
        java.util.List<CaptureRequest.Key<?>> out = new java.util.ArrayList<CaptureRequest.Key<?>>();
        try {
            java.util.List<CaptureRequest.Key<?>> l = ch.getAvailableSessionKeys();
            if (l != null) {
                out.addAll(l);
            }
        } catch (Throwable t) {
            log("getAvailableSessionKeys: " + t);
        }
        try {
            java.util.List<CaptureRequest.Key<?>> l = ch.getAvailableCaptureRequestKeys();
            if (l != null) {
                for (CaptureRequest.Key<?> k : l) {
                    if (!out.contains(k)) {
                        out.add(k);
                    }
                }
            }
        } catch (Throwable t) {
            log("getAvailableCaptureRequestKeys: " + t);
        }
        if (!loggedKeys) {
            loggedKeys = true;
            StringBuilder sb = new StringBuilder();
            for (CaptureRequest.Key<?> k : out) {
                String n = k.getName();
                if (n == null) {
                    continue;
                }
                String ln = n.toLowerCase(Locale.US);
                if (ln.contains("fps") || ln.contains("smvr") || ln.contains("hfps")) {
                    sb.append(n).append(" | ");
                }
            }
            log("vendor keys total=" + out.size() + " fps-ish: " + sb);
        }
        return out;
    }

    private static CameraCharacteristics characteristics(String cameraId) {
        try {
            CameraManager cm = (CameraManager) app.getSystemService(Context.CAMERA_SERVICE);
            return cm == null ? null : cm.getCameraCharacteristics(cameraId);
        } catch (Throwable t) {
            log("characteristics(" + cameraId + "): " + t);
            return null;
        }
    }

    /**
     * The vendor high-frame-rate mode is a session parameter in MTK's HAL, so inject it
     * into the {@link SessionConfiguration} the app hands to Camera2.
     */
    private static void hookSessionParameters() {
        XC_MethodHook inject = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!mode()) {
                    return;
                }
                try {
                    Object arg = p.args[0];
                    if (!(arg instanceof SessionConfiguration)) {
                        return;
                    }
                    CameraDevice dev = (CameraDevice) p.thisObject;
                    SessionConfiguration sc = (SessionConfiguration) arg;
                    CameraCharacteristics ch = characteristics(dev.getId());
                    if (ch == null) {
                        return;
                    }
                    Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing.intValue() == 0) {
                        return;
                    }
                    if (sc.getSessionType() == SessionConfiguration.SESSION_HIGH_SPEED) {
                        SessionConfiguration regular = new SessionConfiguration(
                                SessionConfiguration.SESSION_REGULAR,
                                sc.getOutputConfigurations(), sc.getExecutor(), sc.getStateCallback());
                        sc = regular;
                        p.args[0] = regular;
                        log("session type forced HIGH_SPEED -> REGULAR");
                    }
                    CaptureRequest base = sc.getSessionParameters();
                    CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                    if (base != null) {
                        for (CaptureRequest.Key<?> k : base.getKeys()) {
                            try {
                                //noinspection unchecked,rawtypes
                                b.set((CaptureRequest.Key) (Object) k, base.get(k));
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    applyVendor(b, ch);
                    sc.setSessionParameters(b.build());
                    log("session parameters injected camera=" + dev.getId()
                            + " type=" + sc.getSessionType());
                } catch (Throwable t) {
                    log("session parameters: " + t);
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(CameraDevice.class, "createCaptureSession",
                    SessionConfiguration.class, inject);
        } catch (Throwable t) {
            log("hook CameraDevice: " + t);
        }
        try {
            Class<?> impl = Class.forName("android.hardware.camera2.impl.CameraDeviceImpl");
            XposedHelpers.findAndHookMethod(impl, "createCaptureSession",
                    SessionConfiguration.class, inject);
        } catch (Throwable t) {
            log("hook CameraDeviceImpl: " + t);
        }
    }

    /* --------------------------------------------------------- MediaRecorder */

    private static void hookMediaRecorder() {
        XposedHelpers.findAndHookMethod(MediaRecorder.class, "setVideoSize", int.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (!forceRecorder) {
                            return;
                        }
                        Cfg cfg = Cfg.get(app);
                        p.args[0] = Integer.valueOf(cfg.width);
                        p.args[1] = Integer.valueOf(cfg.height);
                        log("MediaRecorder.setVideoSize -> " + cfg.width + "x" + cfg.height);
                    }
                });

        XposedHelpers.findAndHookMethod(MediaRecorder.class, "setVideoFrameRate", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (!forceRecorder) {
                            return;
                        }
                        Cfg cfg = Cfg.get(app);
                        p.args[0] = Integer.valueOf(cfg.fps);
                        log("MediaRecorder.setVideoFrameRate -> " + cfg.fps);
                    }
                });

        XposedHelpers.findAndHookMethod(MediaRecorder.class, "setVideoEncodingBitRate", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (!forceRecorder) {
                            return;
                        }
                        Cfg cfg = Cfg.get(app);
                        p.args[0] = Integer.valueOf(cfg.bitrate);
                        log("MediaRecorder.setVideoEncodingBitRate -> " + cfg.bitrate);
                    }
                });

        XposedHelpers.findAndHookMethod(MediaRecorder.class, "setVideoEncoder", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (!forceRecorder) {
                            return;
                        }
                        Object a = p.args[0];
                        int requested = a instanceof Integer ? (Integer) a : ENC_H264;
                        int enc = resolveEncoder(requested, Cfg.get(app));
                        p.args[0] = Integer.valueOf(enc);
                        log("MediaRecorder.setVideoEncoder " + requested + " -> " + enc
                                + " (" + (enc == ENC_HEVC ? "HEVC" : enc == ENC_H264 ? "H.264" : "?")
                                + ")");
                    }
                });
    }

    private static int resolveEncoder(int requested, Cfg cfg) {
        if ("off".equals(cfg.hevc)) {
            return requested;
        }
        if ("on".equals(cfg.hevc)) {
            return ENC_HEVC;
        }
        if (supports("video/hevc", cfg.width, cfg.height, cfg.fps)) {
            return ENC_HEVC;
        }
        if (supports("video/avc", cfg.width, cfg.height, cfg.fps)) {
            return ENC_H264;
        }
        log("no hardware encoder advertises " + cfg.width + "x" + cfg.height + "@" + cfg.fps);
        return requested;
    }

    private static boolean supports(String mime, int w, int h, int fps) {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) {
                    continue;
                }
                String[] types;
                try {
                    types = info.getSupportedTypes();
                } catch (Throwable t) {
                    continue;
                }
                for (String type : types) {
                    if (type == null || !type.equalsIgnoreCase(mime)) {
                        continue;
                    }
                    try {
                        MediaCodecInfo.VideoCapabilities vc =
                                info.getCapabilitiesForType(type).getVideoCapabilities();
                        if (vc == null || !vc.isSizeSupported(w, h)) {
                            continue;
                        }
                        Range<Double> rates = vc.getSupportedFrameRatesFor(w, h);
                        if (rates != null && rates.contains(Double.valueOf(fps))) {
                            log("encoder candidate: " + info.getName() + " " + mime);
                            return true;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            log("codec scan: " + t);
        }
        return false;
    }
}
