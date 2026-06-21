package cn.vibewriting.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class LocalArticle(
    val id: String,
    var title: String,
    var content: String,
    var contentHtml: String,
    var referencesJson: String,
    val createdAt: Long,
    var updatedAt: Long,
    var wordCount: Int
)

class ArticleStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("vibe_writing_articles", Context.MODE_PRIVATE)
    private val legacy =
        context.getSharedPreferences("vibe_writing_draft", Context.MODE_PRIVATE)

    fun loadOrMigrate(): MutableList<LocalArticle> {
        val saved = preferences.getString("articles", null)
        if (!saved.isNullOrBlank()) return decode(saved)

        val now = System.currentTimeMillis()
        val legacyContent = legacy.getString("content", "").orEmpty()
        val article = LocalArticle(
            id = UUID.randomUUID().toString(),
            title = legacy.getString("title", "").orEmpty(),
            content = legacyContent,
            contentHtml = legacy.getString("content_html", "").orEmpty(),
            referencesJson = legacy.getString("references", "[]") ?: "[]",
            createdAt = now,
            updatedAt = now,
            wordCount = countWords(legacyContent)
        )
        val articles = mutableListOf(article)
        saveAll(articles, article.id)
        return articles
    }

    fun currentId(fallback: String): String {
        return preferences.getString("current_article_id", fallback) ?: fallback
    }

    fun saveAll(articles: List<LocalArticle>, currentId: String) {
        val array = JSONArray()
        articles.forEach { article ->
            array.put(
                JSONObject()
                    .put("id", article.id)
                    .put("title", article.title)
                    .put("content", article.content)
                    .put("contentHtml", article.contentHtml)
                    .put("references", article.referencesJson)
                    .put("createdAt", article.createdAt)
                    .put("updatedAt", article.updatedAt)
                    .put("wordCount", article.wordCount)
            )
        }
        preferences.edit()
            .putString("articles", array.toString())
            .putString("current_article_id", currentId)
            .apply()
    }

    fun newArticle(): LocalArticle {
        val now = System.currentTimeMillis()
        return LocalArticle(
            UUID.randomUUID().toString(),
            "",
            "",
            "",
            "[]",
            now,
            now,
            0
        )
    }

    fun duplicate(source: LocalArticle): LocalArticle {
        val now = System.currentTimeMillis()
        return source.copy(
            id = UUID.randomUUID().toString(),
            title = source.title.ifBlank { "无标题文章" } + " 副本",
            createdAt = now,
            updatedAt = now
        )
    }

    private fun decode(value: String): MutableList<LocalArticle> {
        val articles = runCatching {
            val array = JSONArray(value)
            MutableList(array.length()) { index ->
                val item = array.getJSONObject(index)
                LocalArticle(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    title = item.optString("title"),
                    content = item.optString("content"),
                    contentHtml = item.optString("contentHtml"),
                    referencesJson = item.optString("references", "[]"),
                    createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = item.optLong("updatedAt", System.currentTimeMillis()),
                    wordCount = item.optInt("wordCount", countWords(item.optString("content")))
                )
            }
        }.getOrElse { mutableListOf() }
        if (articles.isEmpty()) articles.add(newArticle())
        return articles
    }

    companion object {
        fun countWords(value: String): Int = value.replace(Regex("\\s+"), "").length
    }
}
