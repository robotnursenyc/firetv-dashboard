# Fire TV Dashboard ProGuard Rules

# Keep all app classes
-keep class com.hermes.firetv.** { *; }

# AndroidX
-dontwarn androidx.**
-keep class androidx.** { *; }

# Material Components
-dontwarn com.google.android.material.**
-keep class com.google.android.material.** { *; }

# WebView
-keep class android.webkit.** { *; }

# Kotlin
-dontwarn kotlin.**
-keep class kotlin.** { *; }
