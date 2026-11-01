# SORA Studio — release minification is disabled by default.
# Keep kotlinx.serialization generated serializers if minify is enabled later.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
