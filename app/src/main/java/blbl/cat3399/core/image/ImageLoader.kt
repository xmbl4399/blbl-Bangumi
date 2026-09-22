package blbl.cat3399.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.ImageView
import androidx.collection.LruCache
import blbl.cat3399.R
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.net.await
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

object ImageLoader {
    private const val TAG = "ImageLoader"

    /** 等控件测量完成的最长时间(超时则退回最近一次成功尺寸/全尺寸) */
    private const val VIEW_SIZE_WAIT_MS = 300L

    private val placeholder = ColorDrawable(0xFF2A2A2A.toInt())
    private val inFlight = WeakHashMap<ImageView, Job>()

    /** 本会话最近一次成功量到的控件尺寸:同页面卡片尺寸稳定,用于极端情况兜底 */
    @Volatile
    private var lastKnownSize: Pair<Int, Int>? = null

    /** 上一条解码日志内容,用于去重(只记录尺寸组合变化) */
    @Volatile
    private var lastDecodeLog: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val cache = object : LruCache<String, Bitmap>(maxCacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private lateinit var diskCacheDir: File

    // 非 B站 CDN(如 lain.bgm.tv)走独立客户端,不带 B站 UA/Referer/Origin(避免 CDN 策略干扰)
    private val plainOkHttp by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        diskCacheDir = File(context.cacheDir, "images").apply { mkdirs() }
    }

    fun loadInto(view: ImageView, url: String?) {
        val normalized = normalizeImageUrl(url)

        if (normalized == null) {
            view.setTag(R.id.tag_image_loader_url, null)
            inFlight.remove(view)?.cancel()
            if (view.drawable !== placeholder) view.setImageDrawable(placeholder)
            return
        }

        val lastUrl = view.getTag(R.id.tag_image_loader_url) as? String
        if (lastUrl == normalized) {
            // If we already have a non-placeholder image for the same URL, keep it to prevent
            // flicker on rebind (e.g. switching tabs triggers notifyItemRangeChanged).
            val drawable = view.drawable
            if (drawable != null && drawable !== placeholder) {
                inFlight.remove(view)?.cancel()
                return
            }
            // If the same URL is already loading, keep the current placeholder.
            val inFlightJob = inFlight[view]
            if (inFlightJob != null && inFlightJob.isActive) return
        } else {
            view.setTag(R.id.tag_image_loader_url, normalized)
            inFlight.remove(view)?.cancel()
        }

        // 内存缓存键带"目标显示尺寸档位":同一 URL 在 7 列 / 5 列布局下需要的精度不同,
        // 不区分会复用错档位(过糊或过大)的位图。磁盘缓存仍按 URL,不受影响。
        val knownBucket = bucketOf(view.width, view.height)
        val cached = cache.get(memKey(normalized, knownBucket))
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }

        if (view.drawable !== placeholder) view.setImageDrawable(placeholder)
        val job = scope.launch {
            try {
                val bytes =
                    withContext(Dispatchers.IO) {
                        loadBytesWithDiskCache(normalized)
                    }
                // 此处已回到主线程(scope = Main.immediate)。
                // ViewHolder 新建时 onBind 阶段 width==0,必须等一次 layout 才拿得到真实尺寸,
                // 否则退回全尺寸解码 —— 实测这会让下采样对"新建 item"整批失效。
                val (reqW, reqH) = awaitViewSize(view)
                if (reqW > 0 && reqH > 0) lastKnownSize = reqW to reqH
                val key = memKey(normalized, bucketOf(reqW, reqH) ?: knownBucket)
                val hit = cache.get(key)
                if (hit != null) {
                    if ((view.getTag(R.id.tag_image_loader_url) as? String) == normalized) {
                        view.setImageBitmap(hit)
                    }
                    return@launch
                }
                // 按控件尺寸下采样解码(r/400 封面实测 400x566=0.92MB,7 列控件仅 162x230=0.14MB)
                val decoded =
                    withContext(Dispatchers.Default) {
                        val s = calcInSampleSize(bytes, reqW, reqH)
                        s to decodeSampled(bytes, s)
                    }
                val sample = decoded.first
                val bmp = decoded.second
                logDecodeIfChanged(reqW, reqH, bmp, sample)
                if (bmp != null) {
                    cache.put(key, bmp)
                    if ((view.getTag(R.id.tag_image_loader_url) as? String) == normalized) {
                        view.setImageBitmap(bmp)
                    }
                }
            } catch (t: Throwable) {
                AppLog.w(TAG, "load failed url=$normalized", t)
            }
        }
        inFlight[view] = job
    }

    /** 内存缓存键:带尺寸档位(未知档位时退化为按 URL,兼容旧行为) */
    private fun memKey(url: String, bucket: String?): String = if (bucket == null) url else "$url@$bucket"

    /**
     * 目标尺寸量化到 64px 档位(**向上取整**,保证解码结果不小于控件,只会略大不会糊):
     * 同一布局下列宽稳定 → 档位稳定 → 缓存键稳定;不同列数(7列/5列)自然分离成不同档位。
     * 返回 null 表示控件尚未测量(width/height 为 0),由调用方稍后重取。
     */
    private fun bucketOf(w: Int, h: Int): String? {
        if (w <= 0 || h <= 0) return null
        val bw = ((w + 63) / 64) * 64
        val bh = ((h + 63) / 64) * 64
        return "${bw}x${bh}"
    }

    /**
     * 计算 2 的幂 inSampleSize:仅读取图片头(inJustDecodeBounds,不解码像素)拿到原图尺寸,
     * 逐级放大采样率直到结果仍 >= 目标尺寸。返回 1 表示原图本就不大于控件(无需降采样)。
     */
    private fun calcInSampleSize(bytes: ByteArray, reqW: Int, reqH: Int): Int {
        if (reqW <= 0 || reqH <= 0) return 1
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val ow = bounds.outWidth
        val oh = bounds.outHeight
        if (ow <= 0 || oh <= 0) return 1
        var sample = 1
        while (ow / (sample * 2) >= reqW && oh / (sample * 2) >= reqH) {
            sample *= 2
        }
        return sample
    }

    private fun decodeSampled(bytes: ByteArray, inSampleSize: Int): Bitmap? {
        if (inSampleSize <= 1) return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val opts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /**
     * 仅在「输出尺寸 + 采样率 + 目标尺寸」组合变化时打一行日志(不刷屏),
     * 用于验证下采样是否按控件尺寸生效:`decode 200x283 sample=2 req=192x256 (mem=226KB)`。
     */
    private fun logDecodeIfChanged(reqW: Int, reqH: Int, bmp: Bitmap?, sample: Int) {
        val info = "${bmp?.width}x${bmp?.height} sample=$sample req=${reqW}x${reqH} (mem=${(bmp?.byteCount ?: 0) / 1024}KB)"
        if (info == lastDecodeLog) return
        lastDecodeLog = info
        AppLog.i(TAG, "decode $info")
    }

    /**
     * 取控件真实测量尺寸。ViewHolder 新建时 onBind 阶段 width/height==0,直接读会让
     * 下采样失效(退回全尺寸解码),因此等一次 layout 回调。
     * 三级兜底:当前尺寸 → 等 layout(≤300ms) → 本会话最近一次成功尺寸。
     */
    private suspend fun awaitViewSize(view: ImageView): Pair<Int, Int> {
        if (view.width > 0 && view.height > 0) return view.width to view.height
        withTimeoutOrNull(VIEW_SIZE_WAIT_MS) {
            suspendCancellableCoroutine { cont ->
                val listener =
                    object : View.OnLayoutChangeListener {
                        override fun onLayoutChange(
                            v: View,
                            left: Int,
                            top: Int,
                            right: Int,
                            bottom: Int,
                            oldLeft: Int,
                            oldTop: Int,
                            oldRight: Int,
                            oldBottom: Int,
                        ) {
                            if (v.width > 0 && v.height > 0) {
                                v.removeOnLayoutChangeListener(this)
                                if (cont.isActive) cont.resume(Unit)
                            }
                        }
                    }
                view.addOnLayoutChangeListener(listener)
                cont.invokeOnCancellation { view.removeOnLayoutChangeListener(listener) }
            }
        }
        if (view.width > 0 && view.height > 0) return view.width to view.height
        return lastKnownSize ?: (0 to 0)
    }

    /** 磁盘缓存优先:命中直接读文件,否则网络下载并写盘 */
    private suspend fun loadBytesWithDiskCache(url: String): ByteArray {
        if (::diskCacheDir.isInitialized) {
            val disk = diskPath(url)
            if (disk.exists()) {
                val cached = runCatching { disk.readBytes() }.getOrNull()
                if (cached != null && cached.isNotEmpty()) return cached
            }
            val bytes = fetchBytes(url)
            runCatching { disk.writeBytes(bytes) }
                .onFailure { AppLog.w(TAG, "disk cache write failed", it) }
            return bytes
        }
        return fetchBytes(url)
    }

    private suspend fun fetchBytes(url: String): ByteArray {
        val host = url.toHttpUrlOrNull()?.host?.lowercase().orEmpty()
        val isBili =
            host == "hdslb.com" ||
                host.endsWith(".hdslb.com") ||
                host == "bilibili.com" ||
                host.endsWith(".bilibili.com") ||
                host == "bilivideo.com" ||
                host.endsWith(".bilivideo.com") ||
                host == "bilivideo.cn" ||
                host.endsWith(".bilivideo.cn")
        return if (isBili) {
            BiliClient.getBytes(url)
        } else {
            plainOkHttp.newCall(Request.Builder().url(url).build()).await().use { r ->
                val bytes = r.body?.bytes() ?: ByteArray(0)
                if (bytes.isEmpty() && !r.isSuccessful) throw java.io.IOException("HTTP ${r.code} ${r.message}")
                bytes
            }
        }
    }

    private fun diskPath(url: String): File = File(diskCacheDir, md5(url) + ".img")

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun normalizeImageUrl(url: String?): String? {
        val raw = url?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        if (raw.startsWith("//")) return "https:$raw"
        if (!raw.startsWith("http://")) return raw

        val host = raw.toHttpUrlOrNull()?.host?.lowercase().orEmpty()
        val isBiliCdn =
            host == "hdslb.com" ||
                host.endsWith(".hdslb.com") ||
                host == "bilibili.com" ||
                host.endsWith(".bilibili.com") ||
                host == "bilivideo.com" ||
                host.endsWith(".bilivideo.com") ||
                host == "bilivideo.cn" ||
                host.endsWith(".bilivideo.cn")
        return if (isBiliCdn) raw.replaceFirst("http://", "https://") else raw
    }

    private fun maxCacheBytes(): Int {
        val maxMemory = Runtime.getRuntime().maxMemory().toInt()
        return maxMemory / 16
    }
}
