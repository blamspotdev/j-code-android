package dev.jcode

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes the app-preferences DataStore (the app-level settings behind the Settings > Global tab)
 * to/from a portable JSON document, for the Settings > Backup "Export/Import" actions. Each entry
 * carries a type tag so the typed Preferences key is reconstructed exactly on import; unknown types
 * are skipped. Theme/font-size/bundles live in the workspace .jcode config (they travel with the
 * workspace), so they are intentionally out of scope here.
 */
object SettingsBackup {
    private const val FORMAT = 1
    const val MIME = "application/json"
    const val SUGGESTED_NAME = "jcode-settings.json"

    suspend fun export(dataStore: DataStore<Preferences>): String {
        val prefs = dataStore.data.first()
        val entries = JSONArray()
        for ((key, value) in prefs.asMap()) {
            val o = JSONObject().put("name", key.name)
            when (value) {
                is Boolean -> o.put("type", "bool").put("value", value)
                is Int -> o.put("type", "int").put("value", value)
                is Long -> o.put("type", "long").put("value", value)
                is Float -> o.put("type", "float").put("value", value.toDouble())
                is Double -> o.put("type", "double").put("value", value)
                is String -> o.put("type", "string").put("value", value)
                is Set<*> -> o.put("type", "stringset")
                    .put("value", JSONArray().apply { value.forEach { put(it.toString()) } })
                else -> continue
            }
            entries.put(o)
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("app", "dev.jcode")
            .put("settings", entries)
            .toString(2)
    }

    /**
     * Apply a previously-exported document. Returns the number of settings restored. Throws
     * [IllegalArgumentException] if the document is not a J Code settings backup so the caller can
     * surface a clear error instead of silently importing nothing.
     */
    suspend fun import(dataStore: DataStore<Preferences>, document: String): Int {
        val root = JSONObject(document)
        val entries = root.optJSONArray("settings")
            ?: throw IllegalArgumentException("Not a J Code settings backup.")
        var applied = 0
        dataStore.edit { prefs ->
            for (i in 0 until entries.length()) {
                val o = entries.optJSONObject(i) ?: continue
                val name = o.optString("name")
                if (name.isBlank()) continue
                when (o.optString("type")) {
                    "bool" -> prefs[booleanPreferencesKey(name)] = o.optBoolean("value")
                    "int" -> prefs[intPreferencesKey(name)] = o.optInt("value")
                    "long" -> prefs[longPreferencesKey(name)] = o.optLong("value")
                    "float" -> prefs[floatPreferencesKey(name)] = o.optDouble("value").toFloat()
                    "double" -> prefs[doublePreferencesKey(name)] = o.optDouble("value")
                    "string" -> prefs[stringPreferencesKey(name)] = o.optString("value")
                    "stringset" -> {
                        val arr = o.optJSONArray("value") ?: JSONArray()
                        prefs[stringSetPreferencesKey(name)] =
                            (0 until arr.length()).map { arr.optString(it) }.toSet()
                    }
                    else -> continue
                }
                applied++
            }
        }
        return applied
    }
}
