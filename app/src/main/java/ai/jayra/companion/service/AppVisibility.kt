package ai.jayra.companion.service

/** Tracks whether the app UI is in the foreground (background activity starts are restricted on Android 10+). */
object AppVisibility {
    @Volatile
    var foreground: Boolean = false
}
