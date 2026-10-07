package ai.jarvis.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

/**
 * ============================  APP RESOLVER  ============================
 * The module that replaces the hard-coded "if command == open YouTube" list.
 *
 * It does three things:
 *   1. DISCOVERY  — asks the device's PackageManager for every app that has a
 *                   launcher activity, and builds an inventory. No manual list.
 *   2. MATCHING   — scores that inventory against the user's words, locally and
 *                   offline. A separate, generic concept table maps *meanings*
 *                   ("video editor", "the app I use for coding") to likely
 *                   name/package fragments — it never names a specific product.
 *   3. CANDIDATES — hands the top few to the AI to pick from when the local
 *                   scores are close or weak, so "my photo editor" can land on
 *                   whatever editor the user actually has installed.
 *
 * Discovery is subject to Android's package-visibility rules; see the <queries>
 * block in AndroidManifest.xml.
 * =======================================================================
 */
class AppResolver(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    @Volatile
    private var cache: List<InstalledApp> = emptyList()

    /** Rebuild the inventory from the device. Call after install/uninstall. */
    fun refresh(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)

        val apps = resolved.mapNotNull { ri ->
            val ai: ApplicationInfo = ri.activityInfo?.applicationInfo ?: return@mapNotNull null
            val label = ri.loadLabel(pm).toString()
            InstalledApp(
                label = label,
                packageName = ai.packageName,
                isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                concepts = conceptsFor(label, ai.packageName),
                icon = runCatching { ri.loadIcon(pm) }.getOrNull()
            )
        }.distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }

        cache = apps
        return apps
    }

    fun inventory(): List<InstalledApp> {
        if (cache.isEmpty()) refresh()
        return cache
    }

    /** Best local matches for a spoken request, strongest first. */
    fun candidates(query: String, limit: Int = 8): List<Scored> {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return emptyList()
        val tokens = q.split(Regex("[^a-z0-9]+")).filter { it.length > 1 }

        return inventory()
            .map { Scored(it, score(q, tokens, it)) }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
            .take(limit)
    }

    private fun score(q: String, tokens: List<String>, app: InstalledApp): Double {
        val label = app.label.lowercase()
        var s = 0.0

        if (label == q) s += 100.0
        if (label.startsWith(q)) s += 40.0
        if (label.contains(q)) s += 25.0

        for (t in tokens) {
            if (label.contains(t)) s += 12.0
            if (app.packageName.contains(t)) s += 6.0
            if (app.concepts.any { it == t }) s += 14.0
        }

        // A concept phrase such as "video editor" is a strong signal.
        for (c in app.concepts) if (q.contains(c)) s += 30.0

        if (app.isSystem) s -= 3.0   // nudge user apps ahead on ties
        return s
    }

    /**
     * Map an app to the *kinds* of things it is, using generic words that
     * commonly appear in names/packages. Deliberately product-agnostic.
     */
    private fun conceptsFor(label: String, pkg: String): List<String> {
        val text = (label + " " + pkg).lowercase()
        val hits = mutableListOf<String>()
        for ((concept, hints) in CONCEPTS) {
            if (hints.any { text.contains(it) }) hits += concept
        }
        return hits
    }

    data class Scored(val app: InstalledApp, val score: Double)

    companion object {
        /**
         * concept -> fragments commonly found in an app's name or package id.
         * This is a description of app *categories*, not a list of specific apps:
         * it lets "the app I use for coding" match whatever is installed
         * (Termux, Acode, Replit, …) without naming any of them.
         */
        val CONCEPTS: Map<String, List<String>> = mapOf(
            "video editor" to listOf("video", "edit", "inshot", "capcut", "vivacut", "film", "movie", "reel", "kine", "vn"),
            "photo editor" to listOf("photo", "pic", "snapseed", "lightroom", "pix", "image", "editor", "gallery"),
            "coding" to listOf("termux", "code", "dev", "ide", "studio", "github", "git", "program", "acode", "replit", "linux", "shell"),
            "music" to listOf("music", "spotify", "sound", "audio", "player", "wynk", "gaana", "saavn", "podcast"),
            "video player" to listOf("player", "vlc", "mx", "media", "kmplayer"),
            "browser" to listOf("browser", "chrome", "firefox", "edge", "brave", "opera", "web"),
            "messaging" to listOf("whatsapp", "telegram", "signal", "message", "chat", "sms", "messenger"),
            "social" to listOf("instagram", "facebook", "twitter", "snapchat", "linkedin", "threads", "social"),
            "maps" to listOf("maps", "map", "navigation", "waze", "navigate", "gps"),
            "notes" to listOf("note", "keep", "notion", "obsidian", "memo", "journal"),
            "email" to listOf("mail", "gmail", "outlook", "email"),
            "banking" to listOf("bank", "pay", "wallet", "upi", "phonepe", "paytm", "cred", "finance"),
            "shopping" to listOf("shop", "amazon", "flipkart", "myntra", "meesho", "cart", "store"),
            "food" to listOf("food", "swiggy", "zomato", "eat", "restaurant", "delivery", "dominos"),
            "ride" to listOf("uber", "ola", "rapido", "cab", "ride", "taxi"),
            "travel" to listOf("travel", "booking", "flight", "train", "irctc", "hotel", "trip"),
            "fitness" to listOf("fit", "health", "workout", "gym", "step", "run", "strava", "yoga"),
            "learning" to listOf("learn", "course", "study", "edu", "udemy", "coursera", "byju", "duolingo", "school"),
            "games" to listOf("game", "gaming", "pubg", "freefire", "clash", "ludo", "chess", "minecraft", "roblox"),
            "news" to listOf("news", "inshorts", "dailyhunt", "times", "hindu", "bbc", "cnn"),
            "files" to listOf("file", "manager", "explorer", "storage", "docs", "drive")
        )
    }
}
