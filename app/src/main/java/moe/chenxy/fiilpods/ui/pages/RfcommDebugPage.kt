package moe.chenxy.fiilpods.ui.pages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.fiilpods.utils.miuiStrongToast.data.OppoPodsAction
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val MAX_LOGS = 300

private data class RfcommDebugLogEntry(
    val level: String,
    val tag: String,
    val message: String,
    val time: String,
)

@Composable
fun RfcommDebugPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    clearRequest: Int = 0,
) {
    val context = LocalContext.current
    val logs = remember { mutableStateListOf<RfcommDebugLogEntry>() }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var hexInput by remember { mutableStateOf("") }

    // ---- LDAC 诊断快照（只读留档，见 docs/RESEARCH_2026-11_F046_LDAC.md §2.5）----
    var diagSwitchKnown by remember { mutableStateOf(false) }
    var diagEnabled by remember { mutableStateOf(false) }
    var diagUnlockKnown by remember { mutableStateOf(false) }
    var diagUnlocked by remember { mutableStateOf(false) }
    var diagRaw by remember { mutableStateOf("") }
    // 手机侧 A2DP 视角（另一条来源，不能与上面混为一谈）
    var phoneSupported by remember { mutableStateOf(false) }
    var phoneKnown by remember { mutableStateOf(false) }
    var phoneEnabled by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    OppoPodsAction.ACTION_RFCOMM_LOG -> {
                        logs.add(
                            RfcommDebugLogEntry(
                                level = intent.getStringExtra("level").orEmpty().ifBlank { "D" },
                                tag = intent.getStringExtra("tag").orEmpty().ifBlank { "RFCOMM" },
                                message = intent.getStringExtra("message").orEmpty(),
                                time = intent.getStringExtra("time").orEmpty(),
                            )
                        )
                        while (logs.size > MAX_LOGS) logs.removeAt(0)
                    }
                    OppoPodsAction.ACTION_LDAC_DIAG -> {
                        diagEnabled = intent.getBooleanExtra(OppoPodsAction.EXTRA_LDAC_DIAG_ENABLED, false)
                        diagSwitchKnown = intent.getBooleanExtra(OppoPodsAction.EXTRA_LDAC_DIAG_SWITCH_KNOWN, false)
                        diagUnlocked = intent.getBooleanExtra(OppoPodsAction.EXTRA_LDAC_DIAG_UNLOCKED, false)
                        diagUnlockKnown = intent.getBooleanExtra(OppoPodsAction.EXTRA_LDAC_DIAG_UNLOCK_KNOWN, false)
                        diagRaw = intent.getStringExtra(OppoPodsAction.EXTRA_LDAC_DIAG_RAW).orEmpty()
                    }
                    OppoPodsAction.ACTION_PODS_LDAC_CHANGED -> {
                        // 耳机侧与手机侧共用这个 action，用 extra 前缀区分（只读手机侧那几个）。
                        if (!intent.hasExtra(OppoPodsAction.EXTRA_HEADSET_LDAC_KNOWN)) {
                            phoneKnown = intent.getBooleanExtra(OppoPodsAction.EXTRA_LDAC_KNOWN, false)
                            phoneSupported = intent.getBooleanExtra(OppoPodsAction.EXTRA_LDAC_SUPPORTED, false)
                            phoneEnabled = intent.getBooleanExtra(OppoPodsAction.EXTRA_LDAC_ENABLED, false)
                        }
                    }
                }
            }
        }
        // 注册要吞异常：调试页会被反复进出，进程被冻结 / receiver 已被系统回收时
        // registerReceiver 会抛（SecurityException、IllegalArgumentException 都见过），
        // 而这个页面存在的意义就是「出问题时能看到日志」—— 它自己崩掉等于把线索一起扔掉。
        val registered = runCatching {
            context.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(OppoPodsAction.ACTION_RFCOMM_LOG)
                    addAction(OppoPodsAction.ACTION_LDAC_DIAG)
                    addAction(OppoPodsAction.ACTION_PODS_LDAC_CHANGED)
                },
                Context.RECEIVER_EXPORTED,
            )
        }.isSuccess
        if (registered) {
            context.sendRfcommDebugBroadcast(OppoPodsAction.ACTION_RFCOMM_LOG_CONNECT)
            // 进页面顺手扫一遍诊断查询：不扫的话卡片要等到用户手动点「重新扫描」才有内容。
            context.sendRfcommDebugBroadcast(OppoPodsAction.ACTION_LDAC_DIAG_QUERY)
        }
        onDispose {
            context.sendRfcommDebugBroadcast(OppoPodsAction.ACTION_RFCOMM_LOG_DISCONNECT)
            // 没注册成功就不能注销（会抛 IllegalArgumentException），
            // 注册成功也要包起来：注销时机与系统回收有竞争。
            if (registered) {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
    }

    LaunchedEffect(clearRequest) {
        if (clearRequest > 0) {
            logs.clear()
            context.sendRfcommDebugBroadcast(OppoPodsAction.ACTION_RFCOMM_LOG_CLEAR)
        }
    }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollBy(
                value = 50_000f,
                animationSpec = tween(durationMillis = 280),
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = 12.dp,
                top = contentPadding.calculateTopPadding() + 12.dp,
                end = 12.dp,
                bottom = contentPadding.calculateBottomPadding() + 12.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 顶部工具条：用户抓日志时最需要的是「一次性把全部内容拿出来」，
        // 而不是一条一条点着复制（点卡片只复制单条）。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "共 ${logs.size} 条",
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            TextButton(
                text = "复制全部",
                onClick = {
                    if (logs.isEmpty()) {
                        Toast.makeText(context, "暂无日志", Toast.LENGTH_SHORT).show()
                    } else {
                        val all = logs.joinToString("\n") { "${it.time} ${it.level}/${it.tag} ${it.message}" }
                        clipboard.setText(AnnotatedString(all))
                        Toast.makeText(context, "已复制 ${logs.size} 条日志", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
            TextButton(
                text = "清空",
                onClick = {
                    logs.clear()
                    context.sendRfcommDebugBroadcast(OppoPodsAction.ACTION_RFCOMM_LOG_CLEAR)
                },
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }

        // LDAC 三层状态 + 原始回包留档。
        // 为什么放在调试页而不是主界面：142/143/144/140/141/254 的语义里有三条
        // 至今没定论（见 docs/RESEARCH_2026-11_F046_LDAC.md §2.5 / §2.9），
        // 主界面不该显示一个我们自己都不确定含义的数字，但排查时必须看得到它。
        LdacDiagCard(
            switchKnown = diagSwitchKnown,
            enabled = diagEnabled,
            unlockKnown = diagUnlockKnown,
            unlocked = diagUnlocked,
            raw = diagRaw,
            phoneSupported = phoneSupported,
            phoneKnown = phoneKnown,
            phoneEnabled = phoneEnabled,
            onRescan = {
                context.sendRfcommDebugBroadcast(OppoPodsAction.ACTION_LDAC_DIAG_QUERY)
            },
        )

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (logs.isEmpty()) {
                item {
                    EmptyLogCard()
                }
            }
            items(logs) { entry ->
                RfcommLogCard(entry)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HexInputField(
                value = hexInput,
                onValueChange = { hexInput = it.uppercase() },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = "发送",
                onClick = {
                    context.sendRfcommDebugBroadcast(OppoPodsAction.ACTION_RFCOMM_DEBUG_SEND) {
                        putExtra("hex", hexInput)
                    }
                    hexInput = ""
                },
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/**
 * LDAC 三层状态诊断卡。
 *
 * 三条独立的事实必须分开显示，任何两条都不能互相推断：
 *   ① 143 开关 —— 耳机侧 LDAC 开关（cid=39 cmd=143），已验证；
 *   ② 142 授权 —— 耳机侧高清音频授权位（cmd=142），已验证，但**没回包 ≠ 未授权**；
 *   ③ 手机侧 A2DP —— 手机实际协商到的编码器，只有它才回答「现在到底在不在跑 LDAC」。
 *
 * 144 / 254 / 140 / 141 只做原始回包留档：语义尚未定论（research §2.5），
 * 界面不替它们编解释，直接给十六进制。
 */
@Composable
private fun LdacDiagCard(
    switchKnown: Boolean,
    enabled: Boolean,
    unlockKnown: Boolean,
    unlocked: Boolean,
    raw: String,
    phoneSupported: Boolean,
    phoneKnown: Boolean,
    phoneEnabled: Boolean,
    onRescan: () -> Unit,
) {
    val switchText = when {
        !switchKnown -> "未回报"
        enabled -> "01 已开启"
        else -> "00 已关闭"
    }
    val unlockText = when {
        !unlockKnown -> "未回报（本机不认这条查询，不等于未解锁）"
        unlocked -> "01 已解锁"
        else -> "00 未解锁"
    }
    val phoneText = when {
        !phoneKnown -> "未读取"
        !phoneSupported -> "未声明支持 LDAC"
        phoneEnabled -> "LDAC 使用中"
        else -> "已声明支持，当前未启用"
    }
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "LDAC 诊断",
                    modifier = Modifier.weight(1f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                TextButton(
                    text = "重新扫描",
                    onClick = onRescan,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
            DiagRow("143 开关", switchText)
            DiagRow("142 授权", unlockText)
            DiagRow("手机侧编码", phoneText)
            DiagRow("144 codec", diagValue(raw, "90"))
            DiagRow("254 能力位", diagValue(raw, "FE"))
            DiagRow("140 槽 L", diagValue(raw, "8C"))
            DiagRow("141 槽 R", diagValue(raw, "8D"))
            if (raw.isNotBlank()) {
                Text(
                    text = raw,
                    modifier = Modifier.padding(top = 6.dp),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 从 `"8C=00 01;8E=01"` 里取某条诊断值；没查到就明确说「未回报」。
 *
 * 空串也是有效结果：耳机收到查询但不认识时，`recordLdacSlotDiag` 会记一条空值，
 * 那同样说明「问过了、没答案」，与「一次都没问」必须区分。
 */
private fun diagValue(raw: String, key: String): String {
    if (raw.isBlank()) return "未回报"
    val value = raw.split(";")
        .firstOrNull { it.substringBefore("=").equals(key, ignoreCase = true) }
        ?.substringAfter("=")
        ?: return "未回报"
    return value.ifBlank { "空回包" }
}

@Composable
private fun EmptyLogCard() {    Card {
        Text(
            text = "等待 RFCOMM 日志...",
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun RfcommLogCard(entry: RfcommDebugLogEntry) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val direction = entry.directionLabel()
    Card(
        onClick = {
            val text = "${entry.time} ${entry.level}/${entry.tag} ${entry.message}"
            clipboard.setText(AnnotatedString(text))
            Toast.makeText(context, "已复制日志", Toast.LENGTH_SHORT).show()
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .background(levelColor(entry.level), RoundedCornerShape(5.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = entry.level.take(1),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
                Text(
                    text = direction,
                    modifier = Modifier.padding(start = 8.dp),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = directionColor(entry),
                )
                Text(
                    text = entry.tag,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = entry.time,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Text(
                text = entry.message,
                modifier = Modifier.padding(top = 6.dp),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 3,
            )
        }
    }
}

@Composable
private fun HexInputField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .heightIn(min = 44.dp)
            .background(MiuixTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        textStyle = androidx.compose.ui.text.TextStyle(
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
        ),
        singleLine = true,
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = "HEX",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 14.sp,
                    )
                }
                innerTextField()
            }
        },
    )
}

private fun RfcommDebugLogEntry.directionLabel(): String = when (tag.uppercase()) {
    "RFCOMM/TX" -> "SEND"
    "RFCOMM/RX" -> "RECV"
    "RFCOMM/DEBUG" -> "DEBUG"
    else -> "STATE"
}

private fun directionColor(entry: RfcommDebugLogEntry): Color = when (entry.tag.uppercase()) {
    "RFCOMM/TX" -> Color(0xFF00A86B)
    "RFCOMM/RX" -> Color(0xFF1677FF)
    "RFCOMM/DEBUG" -> Color(0xFF7C4DFF)
    else -> Color(0xFF6B7280)
}

private fun levelColor(level: String): Color = when (level.uppercase()) {
    "E", "C" -> Color(0xFFFF4D4F)
    "W" -> Color(0xFFFF9F0A)
    "I" -> Color(0xFF1677FF)
    "D" -> Color(0xFF7C4DFF)
    else -> Color(0xFF6B7280)
}

private fun Context.sendRfcommDebugBroadcast(action: String, fill: Intent.() -> Unit = {}) {
    // 调试页的广播一律「尽力而为」：蓝牙进程没起、被停用、或广播被系统限流时
    // sendBroadcast 会抛异常，而用户面前只是一个空面板 —— 不该整页崩。
    runCatching {
        Intent(action).apply {
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            fill()
            sendBroadcast(this)
        }
    }
}
