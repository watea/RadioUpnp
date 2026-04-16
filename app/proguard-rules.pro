# Gson: keep TypeToken subclasses (used in Radios.java for JSON parsing)
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Missing classes flagged by R8
-dontwarn com.google.re2j.Matcher
-dontwarn com.google.re2j.Pattern

# JitPack libs (no bundled consumer rules)
-keep class com.github.watea.** { *; }

# Android services/activities/receivers are kept via manifest, but explicit for safety
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver