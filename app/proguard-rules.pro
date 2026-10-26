# Default Android optimize rules are applied via build.gradle.kts.
# The accessibility service is referenced from the manifest and resolved by name.
-keep class ai.jayra.companion.accessibility.JayraAccessibilityService { *; }
