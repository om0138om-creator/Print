# Network Printer ProGuard Rules

# Keep application classes
-keep class com.networkprinter.app.** { *; }

# Keep Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep reflection for Coil
-keep class coil.** { *; }

# Keep PrintService
-keep class * extends android.printservice.PrintService { *; }
