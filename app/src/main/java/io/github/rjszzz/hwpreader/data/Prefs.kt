package io.github.rjszzz.hwpreader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class RecentDoc(val uri: String, val name: String, val format: String, val openedAt: Long)

/** 설정과 최근 문서(F6). 백업 대상에서 빠져 있다(backup_rules·data_extraction_rules). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var recentEnabled: Boolean
        get() = sp.getBoolean(KEY_RECENT_ENABLED, RECENT_DEFAULT)
        set(value) {
            sp.edit().putBoolean(KEY_RECENT_ENABLED, value).apply()
            if (!value) clearRecents()
        }

    /** 읽기 모드 글자 크기 3단계: 0 작게, 1 보통, 2 크게 (F4) */
    var fontStep: Int
        get() = sp.getInt(KEY_FONT_STEP, 1).coerceIn(0, 2)
        set(value) = sp.edit().putInt(KEY_FONT_STEP, value.coerceIn(0, 2)).apply()

    /** 형식별 마지막 보기 방식 (F5) */
    fun modeFor(format: String): String? = sp.getString("$KEY_MODE_PREFIX$format", null)

    fun setModeFor(format: String, mode: String) = sp.edit().putString("$KEY_MODE_PREFIX$format", mode).apply()

    /** 원본 보기가 있는 형식들의 기억된 보기 방식 — 뷰어가 형식을 판별한 뒤 고른다 */
    fun modes(): Map<String, String> = listOf("hwp", "hwpx").mapNotNull { f -> modeFor(f)?.let { f to it } }.toMap()

    fun recents(): List<RecentDoc> {
        val raw = sp.getString(KEY_RECENTS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                RecentDoc(o.getString("uri"), o.getString("name"), o.getString("format"), o.getLong("at"))
            }
        }.getOrDefault(emptyList())
    }

    fun addRecent(doc: RecentDoc) {
        if (!recentEnabled) return
        saveRecents((listOf(doc) + recents().filter { it.uri != doc.uri }).take(MAX_RECENTS))
    }

    fun removeRecent(uri: String) = saveRecents(recents().filter { it.uri != uri })

    private fun saveRecents(list: List<RecentDoc>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("uri", it.uri).put("name", it.name).put("format", it.format).put("at", it.openedAt))
        }
        sp.edit().putString(KEY_RECENTS, arr.toString()).apply()
    }

    fun clearRecents() = sp.edit().remove(KEY_RECENTS).apply()

    companion object {
        /** PRD 15장 D5 — 기본값 미정. 병원 환경이면 false 도 검토한다. */
        const val RECENT_DEFAULT = true
        const val MAX_RECENTS = 20
        private const val KEY_RECENT_ENABLED = "recent_enabled"
        private const val KEY_FONT_STEP = "font_step"
        private const val KEY_MODE_PREFIX = "mode_"
        private const val KEY_RECENTS = "recents"
    }
}
