# LTM UniversalVoice Browser v3
# Fix for R8 release build with ML Kit / SnakeYAML optional Java Beans references.
# Android runtime does not include java.beans; these paths are not used by the app.

-dontwarn java.beans.**
-dontwarn org.yaml.snakeyaml.**
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit

# Keep GeckoView public API used from Java.
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.util.** { *; }
