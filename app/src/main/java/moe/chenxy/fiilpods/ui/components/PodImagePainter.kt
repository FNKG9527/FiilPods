package moe.chenxy.fiilpods.ui.components

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import moe.chenxy.fiilpods.R
import moe.chenxy.fiilpods.pods.PodImages

/**
 * 设备产品图的统一取图入口 —— 三级回落，与通知栏 `PodImageLoader.loadModelBoxBitmap` 保持一致：
 *
 * 1. 用户在「耳机图片」里自定义的盒图（`path` 指向模块私有目录里的文件）；
 * 2. 型号官方图 `PodImages.drawableOf(earType)`（仅 `earType > 0` 时才用）；
 * 3. 通用盒图 `R.drawable.img_box`。
 *
 * 为什么 `earType <= 0` 不落 (2)：`PodImages.drawableOf` 对未知型号返回的是
 * `fiil_unknow`（灰色问号占位图），摆进弹层/详情页比通用盒图更让人困惑。
 *
 * 为什么解析失败不算错：自定义图可能已被用户删掉（文件不存在 / 解码失败），
 * 这时必须静默回落，不能让一个例外把整个弹层崩掉。
 */
@Composable
fun rememberPodImagePainter(path: String?, earType: Int): Painter {
    val custom = remember(path) {
        path?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
            ?.let { bitmap -> BitmapPainter(bitmap.asImageBitmap()) }
    }
    return custom ?: painterResource(
        if (earType > 0) PodImages.drawableOf(earType) else R.drawable.img_box
    )
}
