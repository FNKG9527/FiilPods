package moe.chenxy.fiilpods.utils.miuiStrongToast.data
import android.annotation.SuppressLint
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@SuppressLint("UnsafeOptInUsageError")
@Serializable
@Parcelize
data class PodParams (
    var battery: Int = 0,
    var isCharging: Boolean = false,
    var isConnected: Boolean = false,
    var rawStatus: Int = 0
) : Parcelable

@SuppressLint("UnsafeOptInUsageError")
@Serializable
@Parcelize
data class BatteryParams(
    var left: PodParams? = null,
    var right: PodParams? = null,
    var case: PodParams? = null,
    /**
     * 头戴式：只有 [left] 这一路整体电量，UI 必须渲染成单列，
     * 不能套「左耳 / 右耳 / 盒」三列布局。
     */
    var headphones: Boolean = false
) : Parcelable
