# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Keep generic signature information for Gson and Retrofit
-keepattributes Signature
-keepattributes *Annotation*

# Keep Retrofit and Gson classes from being stripped/obfuscated
-dontwarn sun.misc.**
-keep class com.google.gson.stream.** { *; }
-keep class retrofit2.** { *; }

# Keep your data models so Gson can parse JSON into them automatically
-keep class com.sumitgouthaman.busdash.wear.data.** { *; }
