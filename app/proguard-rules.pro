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

# ACRA
-dontwarn ch.acra.**
-keep class ch.acra.** { *; }

# Keep BuildConfig fields (AUTH_TOKEN etc.) accessible at runtime for crash reports.
-keep class com.hermes.firetv.BuildConfig { *; }
