package de.robv.android.xposed;

/** Compile-time stub; the real implementation is provided by LSPosed at runtime. */
public class XposedBridge {
    public static void log(String text) {}
    public static void log(Throwable t) {}
    public static java.util.Set<XC_MethodHook.Unhook> hookAllMethods(
            Class<?> hookClass, String methodName, XC_MethodHook callback) {
        return null;
    }
}
