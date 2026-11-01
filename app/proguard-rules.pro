# ---------------------------------------------------------------------------
# NetSpeed Meter — release hardening for the paid build.
#
# Applied on top of the R8 defaults ("proguard-android-optimize.txt", which
# already keeps every entry point the Android platform needs: the launcher
# activity, services, receivers and manifest-referenced classes).
# ---------------------------------------------------------------------------

# Aggressive obfuscation: flatten every obfuscated class into the root
# package and re-issue short names, so the original package/class structure
# is unrecoverable from the DEX.
-repackageclasses ''
-allowaccessmodification
-optimizationpasses 5

# Silently drop debug logging (and anything it references) from release code.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Attribute shedding: keep only what the androidx support libraries need.
-keepattributes *Annotation*,InnerClasses,EnclosingMethod
