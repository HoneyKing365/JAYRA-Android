package ai.jayra.companion.model

/** Permissions / special accesses walked through by the first-launch onboarding wizard. */
enum class AppPermission(val title: String, val rationale: String, val required: Boolean) {
    MICROPHONE(
        "Microphone",
        "Needed for voice prompts and for using this phone as a 16 kHz wireless microphone for JAYRA.",
        true
    ),
    NOTIFICATIONS(
        "Notifications",
        "High-priority task alerts, authorization prompts and file-transfer banners.",
        true
    ),
    CAMERA(
        "Camera",
        "Scan the QR code on your PC screen for instant pairing.",
        true
    ),
    ACCESSIBILITY(
        "Accessibility Service",
        "Lets JAYRA inspect the screen and tap, scroll or launch apps when you ask. Password and PIN fields are never read.",
        true
    ),
    ACTIVITY_RECOGNITION(
        "Physical activity (optional)",
        "Step telemetry so Ambient Vibe Shifting knows when you have been sitting for a long time.",
        false
    ),
    DND_ACCESS(
        "Do Not Disturb access (optional)",
        "Lets Deep Focus mode silence notifications (alarms still ring).",
        false
    )
}
