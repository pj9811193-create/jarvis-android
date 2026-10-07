package ai.jarvis.device

import android.content.Context
import org.json.JSONArray

/**
 * ============================  NOTES STORE  ============================
 * Notes and to-dos, persisted locally as JSON. Deliberately on-device only —
 * nothing leaves the phone unless the user explicitly asks the AI about it.
 * =====================================================================
 */
class NotesStore(context: Context) {

    private val prefs = context.getSharedPreferences("jarvis_notes", Context.MODE_PRIVATE)

    fun addNote(text: String): String {
        if (text.isBlank()) return "What should the note say, sir?"
        append(KEY_NOTES, text)
        return "Noted."
    }

    fun notes(): String {
        val list = read(KEY_NOTES)
        if (list.isEmpty()) return "You have no notes, sir."
        return "You have ${list.size} note${if (list.size == 1) "" else "s"}: " +
            list.takeLast(5).joinToString("; ")
    }

    fun clearNotes(): String {
        prefs.edit().remove(KEY_NOTES).apply()
        return "Notes cleared."
    }

    fun addTodo(text: String): String {
        if (text.isBlank()) return "What should I add to the list, sir?"
        append(KEY_TODOS, text)
        return "Added to your to-do list."
    }

    fun todos(): String {
        val list = read(KEY_TODOS)
        if (list.isEmpty()) return "Your to-do list is empty, sir."
        return "You have ${list.size} task${if (list.size == 1) "" else "s"}: " +
            list.takeLast(8).joinToString("; ")
    }

    fun clearTodos(): String {
        prefs.edit().remove(KEY_TODOS).apply()
        return "To-do list cleared."
    }

    private fun append(key: String, value: String) {
        val list = read(key).toMutableList()
        list += value
        prefs.edit().putString(key, JSONArray(list).toString()).apply()
    }

    private fun read(key: String): List<String> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private companion object {
        const val KEY_NOTES = "notes"
        const val KEY_TODOS = "todos"
    }
}
