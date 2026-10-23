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
 * 判定策略：加速度计负责「幅度」，陀螺仪负责「旋转」，两者在 [WINDOW] 毫秒内同时命中才算一次摇晃。
 * 只用加速度计会被走路、骑车、地铁颠簸误触；只用陀螺仪会被缓慢转动手腕误触；
 * 两者同时成立更接近「有意摇晃手机」。
 * 设备没有陀螺仪时自动退化为只用加速度计。
 *
 * 灵敏度 0..1 线性映射到加速度阈值 [LIGHT_THRESHOLD]..[HEAVY_THRESHOLD] m/s²（越小越容易触发）。
 *
 * 注意：本类只负责判定，何时注册/注销由调用方按屏幕状态控制，以降低耗电。
 */
class ShakeDetector(
    context: Context,
    private val sensitivity: () -> Float,
    private val onShake: () -> Unit
) : SensorEventListener {
    private val sensorManager = context.getSystemService<SensorManager>()
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /** 重力低通滤波的估计值，用于从原始加速度中剥离重力分量 */
    private val gravity = FloatArray(3)
    private var lastAccelerometerHit = 0L
    private var lastGyroscopeHit = 0L
    private var lastShakeTime = 0L
    private var listening = false

    val isSupported: Boolean get() = accelerometer != null

    /** @return 是否成功开始监听（无加速度计时返回 false） */
    fun start(): Boolean {
        val manager = sensorManager ?: return false
        val sensor = accelerometer ?: return false
        if (listening) return true
        gravity.fill(0f)
        lastAccelerometerHit = 0L
        lastGyroscopeHit = 0L
        listening = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        // 陀螺仪只用于佐证，注册失败不影响主检测
        gyroscope?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        return listening
    }

    fun stop() {
        if (!listening) return
        sensorManager?.unregisterListener(this)
        listening = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val v = event.values
                gravity[0] = ALPHA * gravity[0] + (1 - ALPHA) * v[0]
                gravity[1] = ALPHA * gravity[1] + (1 - ALPHA) * v[1]
                gravity[2] = ALPHA * gravity[2] + (1 - ALPHA) * v[2]
                val x = v[0] - gravity[0]
                val y = v[1] - gravity[1]
                val z = v[2] - gravity[2]
                if (sqrt(x * x + y * y + z * z) > accelerometerThreshold()) {
                    lastAccelerometerHit = now()
                    maybeShake()
                }
            }

            Sensor.TYPE_GYROSCOPE -> {
                val v = event.values
                if (sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) > GYROSCOPE_THRESHOLD) {
                    lastGyroscopeHit = now()
                    maybeShake()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun now() = SystemClock.elapsedRealtime()

    private fun accelerometerThreshold(): Float {
        val s = sensitivity().coerceIn(0f, 1f)
        return LIGHT_THRESHOLD + (HEAVY_THRESHOLD - LIGHT_THRESHOLD) * (1f - s)
    }

    private fun maybeShake() {
        val time = now()
        if (time - lastShakeTime < COOLDOWN) return
        val accelerometerFresh = time - lastAccelerometerHit <= WINDOW
        // 没有陀螺仪时视为无条件满足
        val gyroscopeFresh = gyroscope == null || time - lastGyroscopeHit <= WINDOW
        if (!accelerometerFresh || !gyroscopeFresh) return
        lastShakeTime = time
        onShake()
    }

    private companion object {
        /** 重力低通滤波系数，越接近 1 越平滑 */
        const val ALPHA = 0.8f

        /** 两次触发的最小间隔，避免一次摇晃连续弹出多次 */
        const val COOLDOWN = 1200L

        /** 加速度与陀螺仪命中需要落在同一个时间窗口内 */
        const val WINDOW = 350L

        /** 角速度阈值（rad/s），过滤缓慢转动手腕 */
        const val GYROSCOPE_THRESHOLD = 1.5f

        /** 灵敏度最高时的加速度阈值（m/s²），轻摇即可触发 */
        const val LIGHT_THRESHOLD = 9f

        /** 灵敏度最低时的加速度阈值（m/s²），需要明显甩动 */
        const val HEAVY_THRESHOLD = 18f
    }
}
