package com.localai.app.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Metadata for a saved conversation. */
data class ConversationMeta(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int
)

/**
 * Persists chat sessions as JSON files in filesDir/conversations/.
 * One file per conversation — simple, atomic, no DB.
 */
object ConversationStore {

    private fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "conversations")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun fileFor(ctx: Context, id: String) = File(dir(ctx), "$id.json")

    /** List all saved conversations, newest first. */
    fun list(ctx: Context): List<ConversationMeta> {
        val files = dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                val obj = JSONObject(f.readText())
                ConversationMeta(
                    id = obj.getString("id"),
                    title = obj.optString("title", "Chat"),
                    updatedAt = obj.optLong("updatedAt", f.lastModified()),
                    messageCount = obj.optJSONArray("messages")?.length() ?: 0
                )
            } catch (_: Exception) { null }
        }.sortedByDescending { it.updatedAt }
    }

    /** Load the message list for a conversation. */
    fun load(ctx: Context, id: String): List<ChatMessage> {
        val f = fileFor(ctx, id)
        if (!f.exists()) return emptyList()
        return try {
            val obj = JSONObject(f.readText())
            val arr = obj.optJSONArray("messages") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                ChatMessage(
                    text = m.getString("text"),
                    isUser = m.getBoolean("isUser"),
                    timestamp = m.optLong("timestamp", System.currentTimeMillis()),
                    imagePath = if (m.has("imagePath") && !m.isNull("imagePath")) m.getString("imagePath") else null
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    /** Save a conversation — creates or overwrites. */
    fun save(ctx: Context, id: String, title: String, messages: List<ChatMessage>) {
        try {
            val obj = JSONObject().apply {
                put("id", id)
                put("title", title)
                put("updatedAt", System.currentTimeMillis())
                put("messages", JSONArray().apply {
                    messages.forEach { m ->
                        put(JSONObject().apply {
                            put("text", m.text)
                            put("isUser", m.isUser)
                            put("timestamp", m.timestamp)
                            if (m.imagePath != null) put("imagePath", m.imagePath)
                            else put("imagePath", JSONObject.NULL)
                        })
                    }
                })
            }
            fileFor(ctx, id).writeText(obj.toString())
        } catch (_: Exception) {}
    }

    /** Delete a conversation file. */
    fun delete(ctx: Context, id: String) {
        try { fileFor(ctx, id).delete() } catch (_: Exception) {}
    }

    /** Derive a short title from the first user message. */
    fun titleFrom(messages: List<ChatMessage>): String {
        val first = messages.firstOrNull { it.isUser }?.text?.trim().orEmpty()
        if (first.isBlank()) return "New chat"
        val cleaned = first.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
        return if (cleaned.length <= 40) cleaned else cleaned.take(37) + "…"
    }
}
