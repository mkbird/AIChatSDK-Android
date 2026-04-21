# Keep AIChat SDK public API
-keep class com.aichatsdk.models.** { *; }
-keep class com.aichatsdk.protocols.** { *; }
-keep class com.aichatsdk.services.** { *; }
-keep class com.aichatsdk.config.** { *; }
-keepclassmembers class com.aichatsdk.audio.NativeBridge { native <methods>; }
