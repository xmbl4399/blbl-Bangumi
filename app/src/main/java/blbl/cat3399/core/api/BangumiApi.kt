package blbl.cat3399.core.api

import android.content.Context
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.BangumiCalendarDay
import blbl.cat3399.core.model.BangumiCalendarItem
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.net.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Bangumi (bgm.tv) 数据源:季度番剧列表(统一当年/历史季度,不再按星期分组)。
 *
 * - 端点:GET /v0/subjects?type=2&year=&month=&limit=100&sort=rank
 *   —— 与网页 bangumi.tv/anime/browser/airtime/yyyy-m 同语义:返回该月开播的新番
 *   (季度首月 1/4/7/10;2025-4=25春、2025-7=25夏),实测单月 68-80 条
 *   —— 响应为 {data:[...]} 包裹结构,无星期字段
 * - browse 自带 tags(白名单过滤)与 eps 总话数,无需二次合并
 * - 每季度 12h 文件缓存(按 year_month 命名)
 *
 * 无需鉴权(匿名访问),B站番剧时间表 API 留作备源。
 */
object BangumiApi {
    private const val TAG = "BangumiApi"
    private const val BASE = "https://api.bgm.tv"
    private const val USER_AGENT = "blbl/0.1 (https://github.com/xmbl4399/blbl-Bangumi; bangumi calendar)"
    private const val QUARTER_CACHE_AGE_MS = 12 * 60 * 60 * 1000L
    private const val QUARTER_CACHE_AGE_MS_HISTORY = 30L * 24 * 60 * 60 * 1000L // 历史年份 30 天(数据固定,省 12 倍请求)

    private lateinit var cacheDir: File

    /**
     * 双层流派白名单(设计文档: bili-bgm-overlay/docs/tier-whitelist-design.md)。
     * 词表来自 bgm 列表接口 meta_tags 的真实受控小词表(四年 937 条去重仅 53 词),
     * 非直觉词表——原 46 词单层表里 39 词在列表数据零命中。
     * 覆盖率实测(T1+T2,只用列表数据,无详情补拉):
     * 2023=90.3% / 2024=90.0% / 2025=90.5% / 2026=94.6%。
     * 仅动画向(日剧/电影不显示 tag,见 BangumiCalendarAdapter.showTags)。
     */
    /** Tier1 题材(27 词)——显示优先 */
    private val TAG_T1 =
        setOf(
            "奇幻", "战斗", "恋爱", "日常", "校园", "科幻", "喜剧", "玄幻", "冒险", "悬疑",
            "百合", "穿越", "运动", "音乐", "历史", "剧情", "后宫", "武侠", "推理", "职场",
            "机战", "美食", "萌系", "BL", "恐怖", "惊悚", "耽美",
        )
    /** Tier2 来源·受众(12 词)——Tier1 无词时兜底,或补满槽位 */
    private val TAG_T2 =
        setOf(
            "漫画改", "原创", "小说改", "游戏改", "少年向", "青年向", "子供向",
            "女性向", "少女向", "同人", "影视改", "乙女",
        )

    // Bangumi 与 B站风控体系无关,使用独立客户端(不带 B站 UA/Referer/Origin 拦截器)。
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        cacheDir = File(context.cacheDir, "bangumi").apply { mkdirs() }
    }

    /** 清空全部 bangumi 浏览缓存:设置"隐藏无评分"开关时调用,强制下次按当前开关重新拉取(对全部页面生效) */
    fun clearAllBrowseCache() {
        val dir = cacheDir ?: return
        dir.listFiles()?.forEach { f ->
            if (f.name.startsWith("browse_")) {
                runCatching { f.delete() }
            }
        }
        AppLog.i(TAG, "cleared all browse cache")
    }

    /**
     * 纯缓存读:指定年月的条目(不触发网络,无缓存或已过期也返回缓存值,由调用方决定是否刷新)。
     * 用于年份页面流式加载第一步"先显缓存月"(秒出)。
     *
     * 必须 suspend 且切到 IO:年份页一次要扫 12 个月,「其他动画」= 3 分类 × 12 月 = 36 个
     * JSON 文件(实测 ≈2.8MB)读盘 + 解析。曾在主线程直接调用,实测触发
     * `Choreographer: Skipped 51 frames`(≈850ms 卡顿)。
     */
    suspend fun cachedYearMonth(type: Int, cat: Int, year: Int, month: Int, korean: Boolean = false): List<BangumiCalendarItem>? {
        val cacheName = "browse_${type}_${cat}_${year}_${month}_v6.json"
        return withContext(Dispatchers.IO) {
            val cached = readCache(cacheName) ?: return@withContext null
            val items = runCatching { parseItems(JSONArray(cached)) }.getOrNull() ?: return@withContext null
            if (korean) items.filter { it.metaTags.contains("韩国") } else items
        }
    }

    /**
     * 指定年份 + 月份 + 分类的条目(当月开播,单月独立缓存)。
     * 用于 TV动画/其他动画(type=2)与日剧/电影(type=6)的**年份流式按月加载**。
     *
     * - 统一不带 sort=rank(rank+month 组合服务端不稳定,cat=3/2 无 rank 条目会截断丢失)
     * - 最终按 rank 重排:动画按 rank 升序(无 rank 垫底),三次元纯评分降序,缓存内容一致
     * 缓存:当年 12h / 历史年份 30 天。
     */
    suspend fun browseYearMonth(type: Int, cat: Int, year: Int, month: Int, korean: Boolean = false, force: Boolean = false): List<BangumiCalendarItem> {
        val cacheName = "browse_${type}_${cat}_${year}_${month}_v6.json"
        // 当年 12h 缓存;历史年份 30 天(数据固定)
        val cacheAge =
            if (Calendar.getInstance().get(Calendar.YEAR) == year) QUARTER_CACHE_AGE_MS else QUARTER_CACHE_AGE_MS_HISTORY
        val cachedRaw = readCache(cacheName)
        // force=true(下拉刷新)时跳过新鲜缓存检查,强制重新拉取 bgm
        if (!force && cachedRaw != null && cacheAgeMs(cacheName) < cacheAge) {
            AppLog.i(TAG, "browseYearMonth type=$type cat=$cat $year-$month served from cache")
            val items =
                runCatching { withContext(Dispatchers.Default) { parseItems(JSONArray(cachedRaw!!)) } }
                    .getOrElse { fetchYearMonth(type, cat, year, month, cacheName, fallbackRaw = cachedRaw) }
            return if (korean) items.filter { it.metaTags.contains("韩国") } else items
        }
        val fetched = fetchYearMonth(type, cat, year, month, cacheName, fallbackRaw = cachedRaw)
        return if (korean) fetched.filter { it.metaTags.contains("韩国") } else fetched
    }

    /**
     * 其他动画月数据 = cat=5(WEB) ∪ cat=2(OVA) ∪ cat=3(剧场版) 合并去重(评分降序)。
     * 非电视放送的动画合集:WEB(网络播,现代新番大量在此,实测 2026-07 有 36 部)、
     * OVA(原创动画录像带/碟片,cat=2 实测为 OVA 分类)、剧场版(电影)。
     */
    suspend fun browseAnimeMovieMonth(year: Int, month: Int, force: Boolean = false): List<BangumiCalendarItem> {
        val web = runCatching { browseYearMonth(2, 5, year, month, force = force) }.getOrDefault(emptyList())
        val ova = runCatching { browseYearMonth(2, 2, year, month, force = force) }.getOrDefault(emptyList())
        val movie = runCatching { browseYearMonth(2, 3, year, month, force = force) }.getOrDefault(emptyList())
        return mergeByScore(mergeByScore(web, ova), movie)
    }

    /** 其他动画月数据(纯缓存读,合并三个分类);suspend 原因同 cachedYearMonth(IO 读盘) */
    suspend fun cachedAnimeMovieMonth(year: Int, month: Int): List<BangumiCalendarItem>? {
        val web = cachedYearMonth(2, 5, year, month).orEmpty()
        val ova = cachedYearMonth(2, 2, year, month).orEmpty()
        val movie = cachedYearMonth(2, 3, year, month).orEmpty()
        if (web.isEmpty() && ova.isEmpty() && movie.isEmpty()) return null
        return mergeByScore(mergeByScore(web, ova), movie)
    }

    /** 合并去重后按评分降序(无评分按日期垫底),与各月页面排序一致 */
    private fun mergeByScore(a: List<BangumiCalendarItem>, b: List<BangumiCalendarItem>): List<BangumiCalendarItem> {
        val byId = LinkedHashMap<Long, BangumiCalendarItem>()
        (a + b).forEach { byId.putIfAbsent(it.id, it) }
        return byId.values.sortedWith(
            compareByDescending<BangumiCalendarItem> { it.score ?: -1.0 }
                .thenBy { it.airDate.orEmpty() },
        )
    }

    private suspend fun fetchYearMonth(
        type: Int,
        cat: Int,
        year: Int,
        month: Int,
        cacheName: String,
        fallbackRaw: String?,
    ): List<BangumiCalendarItem> {
        return try {
            val rawItems = JSONArray()
            val seen = HashSet<Long>()
            // 不带 sort=rank:rank+month 组合在 bgm 服务端不稳定,实测 2026-08 cat=3(剧场版)
            // 无 sort 返回 11 条,带 sort=rank 只回 3 条(数据截断)。所有类型统一不带 sort,
            // 拉全后本地排序(统一评分降序,见下方 sorted 逻辑)。
            var offset = 0
            while (true) {
                val url = "$BASE/v0/subjects?type=$type&cat=$cat&year=$year&month=$month&limit=100&offset=$offset"
                val root = JSONObject(fetch(url))
                val data = root.optJSONArray("data") ?: JSONArray()
                for (i in 0 until data.length()) {
                    val obj = data.getJSONObject(i)
                    val id = obj.optLong("id", 0L)
                    if (seen.add(id)) rawItems.put(obj)
                }
                if (data.length() < 100) break
                offset += 100
                if (offset > 500) break
            }
            // 排序:所有类型统一按评分降序(score 从高到低;无评分按日期垫底)。
            // 注:动画原按 rank(热度排名),但 browse 接口不返回 rank 字段(详情接口的 rating.rank 才有),
            // 且 rank+month 服务端排序不稳定(实测截断),统一评分排序与三次元一致。
            val sorted = JSONArray().apply {
                val list = (0 until rawItems.length()).map { rawItems.getJSONObject(it) }
                val ordered =
                    list.sortedWith(
                        compareByDescending<JSONObject> { obj ->
                            val rating = obj.optJSONObject("rating")
                            val score = rating?.optDouble("score", 0.0) ?: 0.0
                            if (score > 0) score else -1.0
                        }.thenBy { obj -> obj.optString("date").orEmpty() },
                    )
                ordered.forEach { put(it) }
            }
            // 设置"隐藏无评分"开启时,全部类型剔除无评分条目(score<=0),缓存同样只存有评分的
            if (BiliClient.prefs.hideNoScoreMedia) {
                val filtered = JSONArray()
                for (i in 0 until sorted.length()) {
                    val obj = sorted.getJSONObject(i)
                    val score = obj.optJSONObject("rating")?.optDouble("score", 0.0) ?: 0.0
                    if (score > 0) filtered.put(obj)
                }
                writeCache(cacheName, filtered.toString())
                return withContext(Dispatchers.Default) { parseItems(filtered) }
            }
            writeCache(cacheName, sorted.toString())
            withContext(Dispatchers.Default) { parseItems(sorted) }
        } catch (t: Throwable) {
            if (fallbackRaw != null) {
                AppLog.w(TAG, "browseYearMonth type=$type cat=$cat $year-$month fetch failed, fallback to stale cache", t)
                return withContext(Dispatchers.Default) { parseItems(JSONArray(fallbackRaw)) }
            }
            throw t
        }
    }

    private suspend fun fetch(url: String): String {
        val req =
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()
        return client.newCall(req).await().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code} ${r.message}")
            r.body?.string().orEmpty()
        }
    }

    private fun parseItems(arr: JSONArray): List<BangumiCalendarItem> {
        val items = ArrayList<BangumiCalendarItem>(arr.length())
        for (i in 0 until arr.length()) {
            items += parseItem(arr.getJSONObject(i))
        }
        return items
    }

    private fun parseItem(it: JSONObject): BangumiCalendarItem {
        val images = it.optJSONObject("images")
        // 封面按"图片质量"设置选精度变体(缓存存原始 JSON 含全部变体,改设置即时生效):
        // small -> r/200 / medium -> common(400px,默认) / large -> medium(800px,避免原图过大)
        val cover =
            when (runCatching { BiliClient.prefs.imageQuality }.getOrDefault("medium")) {
                "small" ->
                    images?.optString("small").orEmpty().takeIf { c -> c.isNotBlank() }
                        ?: images?.optString("common").orEmpty().takeIf { c -> c.isNotBlank() }
                        ?: images?.optString("medium").orEmpty().takeIf { c -> c.isNotBlank() }
                "large" ->
                    images?.optString("medium").orEmpty().takeIf { c -> c.isNotBlank() }
                        ?: images?.optString("large").orEmpty().takeIf { c -> c.isNotBlank() }
                        ?: images?.optString("common").orEmpty().takeIf { c -> c.isNotBlank() }
                else ->
                    images?.optString("common").orEmpty().takeIf { c -> c.isNotBlank() }
                        ?: images?.optString("medium").orEmpty().takeIf { c -> c.isNotBlank() }
                        ?: images?.optString("large").orEmpty().takeIf { c -> c.isNotBlank() }
            }
        val rating = it.optJSONObject("rating")
        val scoreRaw = rating?.optDouble("score", Double.NaN)
        val metaTags = parseStringArray(it.optJSONArray("meta_tags"))
        val airDate =
            it.optString("air_date").orEmpty().takeIf { d -> d.isNotBlank() }
                ?: it.optString("date").orEmpty().takeIf { d -> d.isNotBlank() }
        return BangumiCalendarItem(
            id = it.optLong("id", 0L),
            name = it.optString("name").orEmpty(),
            nameCn = it.optString("name_cn").orEmpty(),
            coverUrl = cover,
            airDate = airDate,
            score = scoreRaw?.takeIf { s -> !s.isNaN() && s > 0.0 },
            rank = it.optInt("rank", -1).takeIf { r -> r > 0 },
            summary = it.optString("summary").orEmpty().takeIf { s -> s.isNotBlank() },
            tags = pickTags(parseTagNames(it.optJSONArray("tags")), metaTags),
            metaTags = metaTags,
            totalEpisodes = it.optInt("eps", 0).takeIf { e -> e > 0 },
            airedEpisodes = it.optInt("aired", 0).takeIf { a -> a > 0 }, // 缓存序列化用
        )
    }

    /** 提取 tags 数组的 name 列表,显式按 count 票数降序(不赌接口已排序,见 v0-api-guide) */
    private fun parseTagNames(tags: JSONArray?): List<String> {
        if (tags == null) return emptyList()
        val names = ArrayList<Pair<String, Int>>(tags.length())
        for (i in 0 until tags.length()) {
            val o = tags.optJSONObject(i) ?: continue
            val name = o.optString("name").orEmpty().trim()
            if (name.isNotEmpty()) names += name to o.optInt("count", 0)
        }
        return names.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * 双层白名单筛选(取前 2 个),对齐 bili-bgm-overlay v0-api-guide v1.4.0 方案:
     * 候选池 = tags(票数降序,内容词主力) ++ meta_tags(服务端结构化摘要),LinkedHashSet 并集去重;
     * 按池顺序筛 Tier1(题材)命中在前 → Tier2(来源·受众)补满槽位;都不命中返回空。
     * - tags 在前:meta_tags 为塞平台/地区词会挤掉题材词(如[276787]梅比乌斯之尘 meta_tags 只有"原创",
     *   tags 里却有 科幻54票/战斗29票),并集后才能显示"科幻/战斗"
     * - meta_tags 有服务端重复 bug(60 条中 21 条),靠并集去重
     * - 只用列表接口数据,不做详情补拉(详情 tags 与列表项逐字节一致,补拉零收益)
     */
    private fun pickTags(tagNames: List<String>, metaTags: List<String>): List<String> {
        val uniq = LinkedHashSet<String>().apply { addAll(tagNames); addAll(metaTags) }
        val out = ArrayList<String>(2)
        for (t in uniq) { if (t in TAG_T1) { out += t; if (out.size >= 2) return out } }
        for (t in uniq) { if (t in TAG_T2) { out += t; if (out.size >= 2) return out } }
        return out
    }

    /** 解析字符串数组(meta_tags 等),保序去重(v0 meta_tags 有服务端重复 bug,60 条中 21 条) */
    private fun parseStringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = LinkedHashSet<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i).orEmpty().trim()
            if (s.isNotEmpty()) out += s
        }
        return out.toList()
    }

    private fun readCache(name: String): String? {
        if (!::cacheDir.isInitialized) return null
        val f = File(cacheDir, name)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull()
    }

    private fun writeCache(name: String, raw: String) {
        if (!::cacheDir.isInitialized) return
        runCatching { File(cacheDir, name).writeText(raw) }
            .onFailure { AppLog.w(TAG, "write cache failed $name", it) }
    }

    private fun cacheAgeMs(name: String): Long {
        if (!::cacheDir.isInitialized) return Long.MAX_VALUE
        val f = File(cacheDir, name)
        return if (f.exists()) System.currentTimeMillis() - f.lastModified() else Long.MAX_VALUE
    }
}
