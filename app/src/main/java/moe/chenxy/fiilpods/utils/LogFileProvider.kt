package moe.chenxy.fiilpods.utils

import androidx.core.content.FileProvider

/**
 * 给「导出日志」用的 FileProvider。
 *
 * 单独建一个类（而不是在 manifest 里直接用 `androidx.core.content.FileProvider`）
 * 只是为了把 authority 变成可引用的编译期常量，避免字符串在 manifest、
 * 分享代码、单元测试三处各写一遍、改一处漏两处。
 *
 * authority 必须与 `AndroidManifest.xml` 里 `<provider android:authorities>` 一致；
 * `ModulePackageTest` 会校验它以 applicationId 为前缀。
 */
class LogFileProvider : FileProvider() {
    companion object {
        const val AUTHORITY = "io.github.fnkg9527.fiilpods.logs"
    }
}
