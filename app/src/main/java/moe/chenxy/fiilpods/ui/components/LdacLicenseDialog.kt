package moe.chenxy.fiilpods.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.fiilpods.pods.FiilMate
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * LDAC 授权码输入对话框。
 *
 * 只在用户**主动**点「写入授权码」时出现 —— 官方激活服务器
 * `newservice.fiil.com` 已 NXDOMAIN，用户手里如果还留着自己耳机的授权码
 * （例如以前从官方 App 抄下来的），这是唯一还能把它写回去的入口。
 *
 * ⚠ 三条必须让用户看见的事实（写进正文，不能只写注释）：
 *  1. 固件会**校验**内容，写错会把耳机已有的解锁状态清掉，且不会自动恢复；
 *  2. 写入命令的 ACK **不代表被接受**，唯一判据是写完后回读 `142`；
 *  3. 授权码无法离线伪造（62 个候选实测全灭），本对话框只做原样转发。
 *
 * ⚠ 不用 `androidx.compose.material3.*` —— 本工程 `app/build.gradle.kts:137`
 * 明确「不要加 material3 / material-icons-extended」，UI 一律走 Miuix。
 *
 * @param show 是否显示
 * @param earType 当前机型（决定写一次还是左右各一次、是否支持写授权码）
 * @param onDismiss 关闭
 * @param onConfirm 用户确认写入，回传解析后的 16 字节块（1 或 2 段）
 */
@Composable
fun LdacLicenseDialog(
    show: Boolean,
    earType: Int,
    onDismiss: () -> Unit,
    onConfirm: (List<ByteArray>) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    // 只有 F046 / F048 两族的协议栈里有 setActivationLdac。
    val supportsLicense = earType == FiilMate.License.EAR_TYPE_F046 ||
        earType == FiilMate.License.EAR_TYPE_F048
    val isF046 = earType == FiilMate.License.EAR_TYPE_F046

    OverlayDialog(
        title = "写入 LDAC 授权码",
        show = show,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (isF046) {
                    "F046 机型（Key Max / Atom 等）只写左耳一次，填 32 位 hex。"
                } else {
                    "F048 机型左右耳各写一次，可填 32 位，或用逗号分隔两段（左,右）。"
                },
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "⚠ 写错内容会清掉耳机已有的解锁状态，且无法恢复。",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(10.dp))
            TextField(
                value = text,
                onValueChange = {
                    text = it
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = "授权码（hex）",
            )
            if (error != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = error.orEmpty(),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "写入后请刷新回读「已授权」才算成功 —— 命令回 ACK 不代表耳机接受。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(4.dp))
                TextButton(
                    text = "写入",
                    onClick = {
                        val parsed = runCatching { FiilMate.License.parseActivateCode(text) }
                        parsed.onSuccess { onConfirm(it) }
                            .onFailure { error = it.message ?: "授权码格式不正确" }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = text.isNotBlank() && supportsLicense,
                )
            }
        }
    }
}
