package moe.chenxy.fiilpods.utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import moe.chenxy.fiilpods.R
import moe.chenxy.fiilpods.config.PodImagePrefs
import moe.chenxy.fiilpods.config.PodImageResource
import moe.chenxy.fiilpods.config.imageUri
import moe.chenxy.fiilpods.pods.PodImages

object PodImageLoader {
    /** applicationId，不是 Kotlin 包名 —— 见 [moe.chenxy.fiilpods.pods.FiilConfig.MODULE_PACKAGE]。 */
    private const val MODULE_PACKAGE = "io.github.fnkg9527.fiilpods"

    fun loadBitmap(
        context: Context,
        prefs: SharedPreferences,
        address: String,
        resource: PodImageResource,
        fallbackResId: Int,
    ): Bitmap? {
        val earphone = runCatching { PodImagePrefs.findOrLatest(prefs, address) }.getOrNull()
        val custom = runCatching {
            earphone?.imageUri(resource)?.let { uri -> decodeUri(context, uri) }
        }.getOrNull()
        if (custom != null) {
            //android.util.Log.d("OppoPods-PodImage", "loaded custom $resource for ${earphone?.address}")
            return custom
        }

        val moduleContext = runCatching {
            context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: return null
        return BitmapFactory.decodeResource(moduleContext.resources, fallbackResId)
    }

    fun loadBitmapWithCustomFallback(
        context: Context,
        prefs: SharedPreferences,
        address: String,
        resource: PodImageResource,
        customFallbackResource: PodImageResource,
        fallbackResId: Int,
    ): Bitmap? {
        val earphone = runCatching { PodImagePrefs.findOrLatest(prefs, address) }.getOrNull()
        val custom = runCatching {
            earphone?.imageUri(resource)?.let { uri -> decodeUri(context, uri) }
                ?: earphone?.imageUri(customFallbackResource)?.let { uri -> decodeUri(context, uri) }
        }.getOrNull()
        if (custom != null) {
            //android.util.Log.d("OppoPods-PodImage", "loaded custom $resource for ${earphone?.address}")
            return custom
        }

        val moduleContext = runCatching {
            context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: return null
        return BitmapFactory.decodeResource(moduleContext.resources, fallbackResId)
    }

    fun loadBoxBitmap(context: Context, prefs: SharedPreferences, address: String): Bitmap? {
        return loadBitmap(context, prefs, address, PodImageResource.BOX, R.drawable.img_box)
    }

    /**
     * 常驻通知的耳机图案：**跟着型号走**。
     *
     * 三级回落，顺序不能颠倒：
     *  1. 用户在模块里给这台耳机设的自定义图（`PodImagePrefs`）—— 用户的显式选择优先；
     *  2. 该型号的官方产品图（`PodImages.drawableOf(earType)`）；
     *  3. 通用的 `img_box.png`。
     *
     * 之前只有 1 和 3，所以不管接的是 Diva、Key Max 还是 CC nano，通知栏里
     * 永远是同一张通用盒子图 —— 这就是「图标没有跟着耳机型号变化」的直接原因。
     *
     * `earType <= 0`（还没读到型号）时不走第 2 级：`PodImages.drawableOf()` 对
     * 未收录型号回落到 `fiil_unknow`，那是「官方也不认识这个型号」的意思，
     * 把它摆进通知栏并不比通用图更有信息量，反而会让人误判设备。
     */
    fun loadModelBoxBitmap(
        context: Context,
        prefs: SharedPreferences,
        address: String,
        earType: Int,
    ): Bitmap? {
        val custom = runCatching {
            PodImagePrefs.findOrLatest(prefs, address)
                ?.imageUri(PodImageResource.BOX)
                ?.let { uri -> decodeUri(context, uri) }
        }.getOrNull()
        if (custom != null) return custom

        val moduleContext = runCatching {
            context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: return null

        // 先试型号图，解不出来（资源缺失/损坏）再落到通用图，不能返回 null
        // —— updateNotification 拿到 null 会整条通知跳过不显示。
        if (earType > 0) {
            val byModel = runCatching {
                BitmapFactory.decodeResource(moduleContext.resources, PodImages.drawableOf(earType))
            }.getOrNull()
            if (byModel != null) return byModel
        }
        return BitmapFactory.decodeResource(moduleContext.resources, R.drawable.img_box)
    }


    fun loadIslandLeftBitmap(context: Context, prefs: SharedPreferences, address: String): Bitmap? {
        return loadBitmapWithCustomFallback(
            context = context,
            prefs = prefs,
            address = address,
            resource = PodImageResource.LEFT,
            customFallbackResource = PodImageResource.BOX,
            fallbackResId = R.drawable.img_left,
        )
    }

    fun loadIslandRightBitmap(context: Context, prefs: SharedPreferences, address: String): Bitmap? {
        return loadBitmapWithCustomFallback(
            context = context,
            prefs = prefs,
            address = address,
            resource = PodImageResource.RIGHT,
            customFallbackResource = PodImageResource.BOX,
            fallbackResId = R.drawable.img_right,
        )
    }

    private fun decodeUri(context: Context, uri: android.net.Uri): Bitmap? {
        return runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                input?.let { BitmapFactory.decodeStream(it) }
            }
        }.getOrNull()
    }
}
