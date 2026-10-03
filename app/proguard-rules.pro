# Disable obfuscation (keep readable names); R8 still shrinks dead code
-dontobfuscate

# Missing classes flagged by R8
-dontwarn com.google.re2j.Matcher
-dontwarn com.google.re2j.Pattern

# JitPack libs (no bundled consumer rules)
-keep class com.github.watea.** { *; }

# Android services/activities/receivers are kept via manifest, but explicit for safety
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver