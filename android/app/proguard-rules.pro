# Keep JNI native bridge methods
-keepclassmembers class * {
    native <methods>;
}
-keep class app.finn.deepsystem.engine.** { *; }
-keep class app.finn.deepsystem.model.** { *; }
