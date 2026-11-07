package de.robv.android.xposed.callbacks;

/** Compile-time stub; the real implementation is provided by LSPosed at runtime. */
public class XC_LoadPackage {
    public static class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
    }
}
