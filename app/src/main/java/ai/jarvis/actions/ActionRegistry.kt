package ai.jarvis.actions

import ai.jarvis.apps.AppLauncher
import ai.jarvis.apps.InstalledApp

/**
 * ============================  ACTION REGISTRY  ============================
 * How a resolved app is actually driven.
 *
 *   AppResolver  →  finds and verifies the app
 *   ActionRegistry → decides HOW to act on it
 *
 * Today only LAUNCH is implemented, which is all "open X" needs. Deep links,
 * explicit intents and accessibility hooks are declared here so they can be
 * added later WITHOUT touching AppResolver or the UI — you just fill in the
 * `overrides` map (or a lookup) for the apps that expose them.
 *
 *   ActionRegistry
 *     ├── launch        ← implemented
 *     ├── deep_link     ← add a package -> URI entry in `overrides`
 *     ├── intent        ← add an explicit action/URI
 *     └── accessibility ← only where a legitimate interface is missing
 * =========================================================================
 */
class ActionRegistry(private val launcher: AppLauncher) {

    sealed interface Action {
        /** Open the app with the device's own launch intent. */
        object Launch : Action

        /** Open a documented deep link inside the app, e.g. whatsapp://send?text=… */
        data class DeepLink(val uri: String) : Action

        /** Fire an explicit intent the app advertises. */
        data class ExplicitIntent(val action: String, val uri: String? = null) : Action
    }

    /**
     * Extension point. Empty today on purpose — deep-link support is the next
     * step, and it is a data change, not a code change.
     *
     * Example of what will go here later:
     *   "com.whatsapp" to Action.DeepLink("whatsapp://send?text=")
     */
    private val overrides: Map<String, Action> = emptyMap()

    fun actionFor(packageName: String): Action = overrides[packageName] ?: Action.Launch

    /** Perform the resolved action. Returns true if the device accepted it. */
    fun perform(app: InstalledApp): Boolean = when (val a = actionFor(app.packageName)) {
        is Action.Launch -> launcher.launch(app.packageName)
        is Action.DeepLink -> launcher.openUri(a.uri)
        is Action.ExplicitIntent -> launcher.openUri(a.uri ?: "")
    }
}
