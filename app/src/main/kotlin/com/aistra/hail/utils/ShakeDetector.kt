package com.aistra.hail.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.core.content.getSystemService
import com.aistra.hail.app.HailData
import kotlin.math.sqrt

/**
 * 摇一摇检测器。
 *
 * 只注册一个传感器，把后台功耗降到最低：只用加速度计（MEMS 里功耗最低的传感器，
 * 约为陀螺仪的 1/5 ~ 1/10）；设备没有加速度计时才退化为陀螺仪。
 *
 * 加速度计判定：先做低通滤波估计重力，再从原始值里剥离重力得到线性加速度，
 * 幅值超过阈值即视为有意摇晃。灵敏度 0..1 线性映射到 [LIGHT_THRESHOLD]..[HEAVY_THRESHOLD]
 * m/s²（灵敏度越高阈值越低，越容易触发）。
 *
 * 采样周期由设置决定（[HailData.shakeSampleIntervalMs]，20-500ms，默认 200ms）。
 * 何时注册 / 注销由调用方按屏幕与锁屏状态控制，以进一步降低耗电。
 */
class ShakeDetector(
    context: Context,
    private val sensitivity: () -> Float,
    private val onShake: () -> Unit
) : SensorEventListener {
    private val sensorManager = context.getSystemService<SensorManager>()
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /** 只用一个传感器：优先加速度计（最省电），缺加速度计时用陀螺仪 */
    private val sensor = accelerometer ?: gyroscope
    private val useAccelerometer = accelerometer != null

    /** 重力低通滤波的估计值，加速度计模式使用 */
    private val gravity = FloatArray(3)
    private var lastShakeTime = 0L
    private var listening = false

    /**
     * 触发后必须先「安静」一段时间才允许再次触发。
     * 只靠固定冷却不够：一次真实摇晃会持续 0.5~1.5 秒，冷却一过就会在同一次摇晃里再次命中，
     * 表现成「摇一下弹出好几个面板」（开→关→开反复切换）。
     */
    private var armed = true
    private var quietSince = 0L

    val isSupported: Boolean get() = sensor != null

    /** 当前生效的采样周期（毫秒），供界面展示 */
    val sampleIntervalMs: Int get() = HailData.shakeSampleIntervalMs.coerceIn(20, 500)

    /** @return 是否成功开始监听（无可用传感器时返回 false） */
    fun start(): Boolean {
        val manager = sensorManager ?: return false
        val target = sensor ?: return false
        if (listening) return true
        gravity.fill(0f)
        lastShakeTime = 0L
        armed = true
        quietSince = 0L
        // 采样周期由设置控制：越小越灵敏、越耗电；20ms 接近 GAME，200ms 接近 NORMAL
        val intervalUs = sampleIntervalMs * 1000
        listening = manager.registerListener(this, target, intervalUs)
        return listening
    }

    fun stop() {
        if (!listening) return
        sensorManager?.unregisterListener(this)
        listening = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        // 不管当前能不能触发，重力估计都要持续更新，否则滤波会失真
        val magnitude = if (useAccelerometer) {
            // 剥离重力，只看线性加速度
            gravity[0] = ALPHA * gravity[0] + (1 - ALPHA) * v[0]
            gravity[1] = ALPHA * gravity[1] + (1 - ALPHA) * v[1]
            gravity[2] = ALPHA * gravity[2] + (1 - ALPHA) * v[2]
            val x = v[0] - gravity[0]
            val y = v[1] - gravity[1]
            val z = v[2] - gravity[2]
            sqrt(x * x + y * y + z * z)
        } else {
            // 回退陀螺仪（设备无加速度计的极少数情况）
            sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        }
        val threshold = if (useAccelerometer) accelerometerThreshold() else gyroscopeThreshold()
        val time = now()
        // 兜底冷却，压掉高频抖动连击
        if (time - lastShakeTime < COOLDOWN) return
        if (!armed) {
            // 还没安静下来：这次摇晃的余波，全部忽略；安静够久才重新武装
            if (magnitude < threshold * REARM_RATIO) {
                if (quietSince == 0L) quietSince = time
                if (time - quietSince >= REARM_QUIET_MS) {
                    armed = true
                    quietSince = 0L
                }
            } else {
                quietSince = 0L
            }
            return
        }
        if (magnitude <= threshold) return
        armed = false
        quietSince = 0L
        lastShakeTime = time
        onShake()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun now() = SystemClock.elapsedRealtime()

    private fun accelerometerThreshold(): Float {
        val s = sensitivity().coerceIn(0f, 1f)
        return LIGHT_THRESHOLD + (HEAVY_THRESHOLD - LIGHT_THRESHOLD) * (1f - s)
    }

    private fun gyroscopeThreshold(): Float {
        val s = sensitivity().coerceIn(0f, 1f)
        return GYRO_LIGHT + (GYRO_HEAVY - GYRO_LIGHT) * (1f - s)
    }

    private companion object {
        /** 重力低通滤波系数，越接近 1 越平滑 */
        const val ALPHA = 0.8f

        /** 兜底冷却：命中后的最短间隔，压掉同一瞬间的多次命中 */
        const val COOLDOWN = 400L

        /** 命中后需要连续安静这么久（ms）才允许下一次触发：一次摇晃只算一次 */
        const val REARM_QUIET_MS = 350L

        /** 幅值低于阈值的这个比例才算「安静」，避免噪声一直不让重新武装 */
        const val REARM_RATIO = 0.4f

        /** 加速度阈值（m/s²）：灵敏度最高时轻晃即可，最低时需明显甩动 */
        const val LIGHT_THRESHOLD = 9f
        const val HEAVY_THRESHOLD = 18f

        /** 陀螺仪阈值（rad/s），仅无加速度计的设备使用 */
        const val GYRO_LIGHT = 1.5f
        const val GYRO_HEAVY = 3.0f
    }
}
