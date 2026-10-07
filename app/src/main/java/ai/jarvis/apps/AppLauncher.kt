package ai.jarvis.apps

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * ============================  APP LAUNCHER  ============================
 * Turns a resolved app into an actual launch, using the device's own launch
 * intent (so we never guess an activity name), and degrades gracefully to the
 * store listing when an app is not launchable.
 * ======================================================================
 */
class AppLauncher(private val context: Context) {

    /**
     * Launch by package using the device's own launch intent.
     *
     * This is the FINAL GATE: if the package is not installed, or has no
     * launchable activity, we return false and never call startActivity.
     * Combined with AppResolver.verify(), nothing the AI could invent can ever
     * reach the OS as a launch.
     */
    fun launch(packageName: String): Boolean {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(packageName)
            ?: pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Open a deep link / URI (e.g. a custom scheme, or a store URL). */
    fun openUri(uri: String): Boolean = try {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        true
    } catch (e: Exception) {
        false
    }

    fun openStoreListing(packageName: String): Boolean =
        openUri("market://details?id=$packageName")

    fun searchStore(name: String): Boolean =
        openUri("market://search?q=" + Uri.encode(name))
}
