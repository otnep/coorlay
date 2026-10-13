# Release only (see build.gradle.kts): android.util.Log calls are removed, so coorlay writes nothing to logcat
# and the firmware's KC2Log. The connection journal inside the app is not affected.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}
