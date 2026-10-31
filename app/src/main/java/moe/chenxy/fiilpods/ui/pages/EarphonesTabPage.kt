package moe.chenxy.fiilpods.ui.pages

import android.bluetooth.BluetoothDevice
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import moe.chenxy.fiilpods.R
import moe.chenxy.fiilpods.pods.NoiseControlMode
import moe.chenxy.fiilpods.pods.WearStatus
import moe.chenxy.fiilpods.utils.miuiStrongToast.data.BatteryParams
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
internal fun EarphonesTabPage(
    showEarphoneDetail: Boolean,
    displayTitle: String,
    displayBattery: BatteryParams,
    displayWearStatus: WearStatus,
    displayAnc: NoiseControlMode,
    onAncModeChange: (NoiseControlMode) -> Unit,
    smartAncLevel: NoiseControlMode?,
    displayTransparencyVocalEnhancement: Boolean,
    onTransparencyVocalEnhancementChange: (Boolean) -> Unit,
    displayGameMode: Boolean,
    gameModeSupported: Boolean,
    onGameModeChange: (Boolean) -> Unit,
    spatialAudioMode: Int,
    onSpatialAudioModeChange: (Int) -> Unit,
    equalizerVisible: Boolean,
    dualDeviceSupported: Boolean,
    onOpenEqualizer: () -> Unit,
    displayDualDeviceConnection: Boolean,
    onDualDeviceConnectionChange: (Boolean) -> Unit,
    /** 语音提示音量线值 0=高 1=中 2=低 3=静音，-1 表示未知。 */
    toneVolume: Int = -1,
    lowLatencyMode: Int = -1,
    /**
     * LDAC 三态：`-1` 未知/不支持（不渲染开关）、`0` 支持但当前未启用、`1` 已启用。
     *
     * 为什么用 Int 而不是 Boolean：本 ROM 反射不到 `A2dpService` 时要能把开关整个藏掉，
     * 给个恒为 false 的 Boolean 就变成「假开关」了。
     */
    ldacState: Int = -1,
    onLdacChange: (Boolean) -> Unit = {},
    onToneVolumeChange: (Int) -> Unit = {},
    onLowLatencyModeChange: (Int) -> Unit = {},
    spatialAudioSupported: Boolean,
    spatialSoundSupported: Boolean,
    adaptiveModeEnabled: Boolean,
    boxImagePath: String?,
    /**
     * 当前耳机的 earType（`-1` = 未知）。
     *
     * 用户没自定义产品图时，详情页拿它去 [moe.chenxy.fiilpods.pods.PodImages]
     * 换成本型号的产品图；未知时仍用通用 `img_box`。
     */
    earType: Int = -1,
    connectedDeviceAddress: String,
    connectingDeviceAddress: String?,
    showConnectErrorDialog: Boolean,
    contentPadding: PaddingValues,
    pageBottomContentPadding: Dp,
    nestedScrollConnection: NestedScrollConnection,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onConnectedDeviceClick: () -> Unit,
    onDeviceDisconnect: (BluetoothDevice) -> Unit,
    onDismissConnectError: () -> Unit,
) {
    AnimatedContent(
        targetState = showEarphoneDetail,
        modifier = Modifier.fillMaxSize(),
        label = "EarphonesPageAnim",
    ) { detailVisible ->
        if (detailVisible) {
            PodDetailPage(
                modifier = Modifier
                    .overScrollVertical()
                    .nestedScroll(nestedScrollConnection),
                contentPadding = contentPadding,
                bottomContentPadding = pageBottomContentPadding,
                podName = displayTitle.ifEmpty { stringResource(R.string.pod_info) },
                batteryParams = displayBattery,
                wearStatus = displayWearStatus,
                ancMode = displayAnc,
                onAncModeChange = onAncModeChange,
                smartAncLevel = smartAncLevel,
                transparencyVocalEnhancement = displayTransparencyVocalEnhancement,
                onTransparencyVocalEnhancementChange = onTransparencyVocalEnhancementChange,
                gameMode = displayGameMode,
                gameModeSupported = gameModeSupported,
                onGameModeChange = onGameModeChange,
                spatialAudioMode = spatialAudioMode,
                onSpatialAudioModeChange = onSpatialAudioModeChange,
                equalizerVisible = equalizerVisible,
                dualDeviceSupported = dualDeviceSupported,
                onOpenEqualizer = onOpenEqualizer,
                dualDeviceConnection = displayDualDeviceConnection,
                onDualDeviceConnectionChange = onDualDeviceConnectionChange,
                toneVolume = toneVolume,
                lowLatencyMode = lowLatencyMode,
                ldacState = ldacState,
                onLdacChange = onLdacChange,
                onToneVolumeChange = onToneVolumeChange,
                onLowLatencyModeChange = onLowLatencyModeChange,
                spatialAudioSupported = spatialAudioSupported,
                spatialSoundSupported = spatialSoundSupported,
                adaptiveModeEnabled = adaptiveModeEnabled,
                boxImagePath = boxImagePath,
                earType = earType,
            )
        } else {
            DevicePickerPage(
                connectedDeviceName = displayTitle,
                connectedDeviceAddress = connectedDeviceAddress,
                connectingDeviceAddress = connectingDeviceAddress,
                showConnectError = showConnectErrorDialog,
                contentPadding = contentPadding,
                bottomContentPadding = pageBottomContentPadding,
                onDeviceSelected = onDeviceSelected,
                onConnectedDeviceClick = onConnectedDeviceClick,
                onDeviceDisconnect = onDeviceDisconnect,
                onDismissConnectError = onDismissConnectError,
            )
        }
    }
}
