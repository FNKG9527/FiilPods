package moe.chenxy.fiilpods.ui.pages

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.chenxy.fiilpods.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

/**
 * 触控与按键。
 *
 * 两个功能分属不同协议族，必须分开门控：
 *  - 触控灵敏度：只有 GAIA 有（`FFCOMMAND_Get/SetTouchSensitivity` 1919/1918）；
 *  - 按键自定义：只有 Mate 有（cid=39 cmdId=5 读、cid=34 写）。
 * 传 [isMate] 而不是在页面里猜，避免 UI 与协议层判定不一致。
 */
@Composable
fun TouchKeysPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    isMate: Boolean,
    touchLeft: Int,
    touchRight: Int,
    keyActions: List<Int>,
    onTouchSensitivityChange: (ear: Int, level: Int) -> Unit,
    onKeyActionChange: (keyId: Int, action: Int) -> Unit,
) {
    val levelLabels = listOf(
        stringResource(R.string.touch_level_low),
        stringResource(R.string.touch_level_mid),
        stringResource(R.string.touch_level_high),
    )
    // 官方 F45SelectOperateDialog / arrays.xml:221 的动作码白名单。
    val actionCodes = listOf(0, 2, 3, 4, 5, 6, 7, 8)
    val actionLabels = listOf(
        stringResource(R.string.key_action_none),
        stringResource(R.string.key_action_voice_assistant),
        stringResource(R.string.key_action_previous),
        stringResource(R.string.key_action_next),
        stringResource(R.string.key_action_volume_up),
        stringResource(R.string.key_action_volume_down),
        stringResource(R.string.key_action_play_pause),
        stringResource(R.string.key_action_low_latency),
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 20.dp,
        ),
    ) {
        if (!isMate) {
            item { SmallTitle(text = stringResource(R.string.touch_sensitivity)) }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.touch_left),
                        summary = stringResource(R.string.touch_sensitivity_summary),
                        items = levelLabels,
                        selectedIndex = touchLeft.coerceIn(0, 2),
                        onSelectedIndexChange = { onTouchSensitivityChange(0, it) },
                    )
                    OverlayDropdownPreference(
                        title = stringResource(R.string.touch_right),
                        summary = stringResource(R.string.touch_sensitivity_summary),
                        items = levelLabels,
                        selectedIndex = touchRight.coerceIn(0, 2),
                        onSelectedIndexChange = { onTouchSensitivityChange(1, it) },
                    )
                }
            }
        }

        if (isMate) {
            item { SmallTitle(text = stringResource(R.string.key_customization)) }
            // 三种状态必须分开，否则用户看到「读取中」永远转，分不清是没查到还是不支持：
            //  · size < 11 → 这一轮还没收到回包；
            //  · size >= 11 但全 -1 → 回包到了但一个键都没解析出来（固件没开放按键表）；
            //  · 否则按已知动作渲染。
            val parsed = keyActions.size >= 11
            val anyKnown = parsed && keyActions.drop(1).any { it >= 0 }
            if (!parsed) {
                item {
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        Text(
                            text = stringResource(R.string.key_config_reading),
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            } else if (!anyKnown) {
                item {
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        Text(
                            text = stringResource(R.string.key_config_unavailable),
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            } else {
                item {
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        keyActions.drop(1).take(10).forEachIndexed { index, code ->
                            val keyId = index + 1
                            val known = code >= 0
                            OverlayDropdownPreference(
                                title = stringResource(R.string.key_slot, keyId),
                                summary = if (known) {
                                    actionLabels.getOrElse(actionCodes.indexOf(code)) {
                                        stringResource(R.string.key_action_none)
                                    }
                                } else {
                                    stringResource(R.string.key_action_unknown)
                                },
                                items = actionLabels,
                                selectedIndex = actionCodes.indexOf(code).coerceAtLeast(0),
                                onSelectedIndexChange = { onKeyActionChange(keyId, actionCodes[it]) },
                            )
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = stringResource(R.string.touch_keys_hint),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}
