# Manifest-declared components are kept automatically. Extra rules:
-keep class ai.jayra.companion.service.JayraAccessibilityService { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel { <init>(...); }
-dontwarn org.slf4j.**
