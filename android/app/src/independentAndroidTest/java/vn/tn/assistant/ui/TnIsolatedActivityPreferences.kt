package vn.tn.assistant.ui

import android.content.Context

/** Pure UI scenarios must not rotate a real account's saved refresh token. */
internal class TnIsolatedActivityPreferences(context: Context) : AutoCloseable {
    private val preferences = context.getSharedPreferences("tn-session-v1", Context.MODE_PRIVATE)
    private val saved = preferences.all.mapValues { (_, value) ->
        if (value is Set<*>) value.toSet() else requireNotNull(value)
    }
    init { check(preferences.edit().clear().commit()) }
    override fun close() {
        val editor = preferences.edit().clear()
        saved.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unsupported preference type")
            }
        }
        check(editor.commit())
    }
}
