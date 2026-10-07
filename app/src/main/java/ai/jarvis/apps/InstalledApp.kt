package ai.jarvis.apps

import android.graphics.drawable.Drawable

/**
 * One app that is actually installed on THIS device, as discovered at runtime.
 * Nothing here is hard-coded — the inventory comes from the device.
 */
data class InstalledApp(
    val label: String,
    val packageName: String,
    val isSystem: Boolean,
    val concepts: List<String> = emptyList(),
    val icon: Drawable? = null
) {
    /** Everything searchable about the app, lowercased. */
    val searchable: String
        get() = (label + " " + packageName + " " + concepts.joinToString(" ")).lowercase()
}
