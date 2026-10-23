package com.aistra.hails.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.core.content.getSystemService
import kotlin.math.sqrt

/**
 * 摇一摇检测器。
 *
 * 只注册一个传感器，把后台功耗降到最低：优先陀螺仪（角速度对「有意摇晃」最敏感，
 * 不受重力与走路颠簸干扰）；设备没有陀螺仪时自动退化为加速度计。
 *
 * 灵敏度 0..1 线性映射到阈值（灵敏度越高阈值越低，越容易触发）：
 * - 陀螺仪：[GYRO_LIGHT]..[GYRO_HEAVY] rad/s
 * - 加速度计：[LIGHT_THRESHOLD]..[HEAVY_THRESHOLD] m/s²
 *
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

    /** 只用一个传感器：优先陀螺仪，缺陀螺仪时用加速度计 */
    private val sensor = gyroscope ?: accelerometer
    private val useGyroscope = gyroscope != null

    /** 重力低通滤波的估计值，仅加速度计模式使用 */
    private val gravity = FloatArray(3)
    private var lastShakeTime = 0L
    private var listening = false

    val isSupported: Boolean get() = sensor != null

    /** @return 是否成功开始监听（无可用传感器时返回 false） */
    fun start(): Boolean {
        val manager = sensorManager ?: return false
        val target = sensor ?: return false
        if (listening) return true
        gravity.fill(0f)
        lastShakeTime = 0L
        // SENSOR_DELAY_UI（约 60ms）：一次摇晃持续 200ms 以上，足够抓到峰值，功耗远低于 GAME
        listening = manager.registerListener(this, target, SensorManager.SENSOR_DELAY_UI)
        return listening
    }

    fun stop() {
        if (!listening) return
        sensorManager?.unregisterListener(this)
        listening = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val time = now()
        // 冷却期内直接丢弃，省掉后续计算
        if (time - lastShakeTime < COOLDOWN) return
        val v = event.values
        val hit = if (useGyroscope) {
            // 单陀螺仪判定：角速度幅值超过阈值即视为有意摇晃
            sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) > gyroscopeThreshold()
        } else {
            // 回退加速度计：先剥离重力，再看线性加速度幅值
            gravity[0] = ALPHA * gravity[0] + (1 - ALPHA) * v[0]
            gravity[1] = ALPHA * gravity[1] + (1 - ALPHA) * v[1]
            gravity[2] = ALPHA * gravity[2] + (1 - ALPHA) * v[2]
            val x = v[0] - gravity[0]
            val y = v[1] - gravity[1]
            val z = v[2] - gravity[2]
            sqrt(x * x + y * y + z * z) > accelerometerThreshold()
        }
        if (!hit) return
        lastShakeTime = time
        onShake()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun now() = SystemClock.elapsedRealtime()

    private fun gyroscopeThreshold(): Float {
        val s = sensitivity().coerceIn(0f, 1f)
        return GYRO_LIGHT + (GYRO_HEAVY - GYRO_LIGHT) * (1f - s)
    }

    private fun accelerometerThreshold(): Float {
        val s = sensitivity().coerceIn(0f, 1f)
        return LIGHT_THRESHOLD + (HEAVY_THRESHOLD - LIGHT_THRESHOLD) * (1f - s)
    }

    private companion object {
        /** 重力低通滤波系数，越接近 1 越平滑 */
        const val ALPHA = 0.8f

        /** 两次触发的最小间隔，避免一次摇晃连续弹出多次 */
        const val COOLDOWN = 1200L

        /** 陀螺仪角速度阈值（rad/s）：灵敏度最高时轻晃即可，最低时需明显甩动 */
        const val GYRO_LIGHT = 1.5f
        const val GYRO_HEAVY = 3.0f

        /** 加速度阈值（m/s²），仅无陀螺仪的设备使用 */
        const val LIGHT_THRESHOLD = 9f
        const val HEAVY_THRESHOLD = 18f
    }
}
