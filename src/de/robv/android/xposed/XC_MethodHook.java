package de.robv.android.xposed;

/** Compile-time stub; the real implementation is provided by LSPosed at runtime. */
public class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static class MethodHookParam {
        public Object[] args;
        public Object thisObject;

        public Object getResult() { return null; }
        public void setResult(Object result) {}
        public Throwable getThrowable() { return null; }
        public void setThrowable(Throwable t) {}
        public boolean hasThrowable() { return false; }
    }

    public class Unhook {}
}
