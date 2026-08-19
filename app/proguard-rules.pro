# TellMe -- no obfuscation needed for a debug/dev build.
# Keep MediaPipe native entrypoints if you ever enable minify.
-keep class com.google.mediapipe.** { *; }
-keepclassmembers class com.google.mediapipe.** { *; }
