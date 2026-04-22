# Fire TV Dashboard ProGuard Rules

# Keep all app classes (no minification in release, but rules are here for future use).
-keep class com.hermes.firetv.** { *; }

# AndroidX
-dontwarn androidx.**
-keep class androidx.** { *; }

# Material Components
-dontwarn com.google.android.material.**
-keep class com.google.android.material.** { *; }

# WebView
-dontwarn android.webkit.**
-keep class android.webkit.** { *; }

# Kotlin
-dontwarn kotlin.**
-keep class kotlin.** { *; }

# Keep BuildConfig fields (AUTH_TOKEN etc.) accessible at runtime.
-keep class com.hermes.firetv.BuildConfig { *; }

# Keep CrashLogger (used by DashboardActivity).
-keep class com.hermes.firetv.CrashLogger { *; }

# Keep @JavascriptInterface methods (public API called from JS).
-keepclassmembers class com.hermes.firetv.DashboardActivity {
    @android.webkit.JavascriptInterface <methods>;
}
