package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class SavedSnippetsRepository private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("saved_snippets_prefs", Context.MODE_PRIVATE)

    private val _snippets = MutableStateFlow<List<SavedSnippet>>(emptyList())
    val snippets: StateFlow<List<SavedSnippet>> = _snippets.asStateFlow()

    init {
        loadSnippets()
    }

    private fun loadSnippets() {
        val jsonString = prefs.getString(KEY_SNIPPETS, "[]") ?: "[]"
        val list = ArrayList<SavedSnippet>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    SavedSnippet(
                        id = obj.optString("id", ""),
                        text = obj.optString("text", ""),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load saved snippets", e)
        }
        _snippets.value = list
    }

    private fun persist(list: List<SavedSnippet>) {
        _snippets.value = list
        try {
            val jsonArray = JSONArray()
            for (item in list) {
                jsonArray.put(
                    JSONObject().apply {
                        put("id", item.id)
                        put("text", item.text)
                        put("timestamp", item.timestamp)
                    }
                )
            }
            prefs.edit().putString(KEY_SNIPPETS, jsonArray.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist saved snippets", e)
        }
    }

    fun addSnippet(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val newSnippet = SavedSnippet(text = trimmed)
        persist(listOf(newSnippet) + _snippets.value)
        return true
    }

    fun deleteSnippet(id: String) {
        persist(_snippets.value.filterNot { it.id == id })
    }

    fun clearAll() {
        persist(emptyList())
    }

    companion object {
        private const val TAG = "SavedSnippetsRepository"
        private const val KEY_SNIPPETS = "snippets_list_key"

        @Volatile
        private var instance: SavedSnippetsRepository? = null

        fun getInstance(context: Context): SavedSnippetsRepository {
            return instance ?: synchronized(this) {
                instance ?: SavedSnippetsRepository(context).also { instance = it }
            }
        }
    }
}
