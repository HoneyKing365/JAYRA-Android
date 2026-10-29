package ai.jayra.companion.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Collects low-power motion data for `vibe_telemetry`: step-counter deltas when the permission is
 * granted, otherwise an accelerometer motion estimate.
 */
class VibeTelemetryManager(private val context: Context) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)

    private var lastStepTotal = -1f
    private var stepDelta = 0
    private var motionSum = 0.0
    private var motionSamples = 0
    private var usingSteps = false
    private var running = false

    @Synchronized
    fun start() {
        if (running) return
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        val stepSensor = if (granted) sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) else null
        if (stepSensor != null) {
            usingSteps = sm.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            // 5 s batching keeps the CPU asleep between reports
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 5_000_000)
        }
        running = true
    }

    @Synchronized
    fun stop() {
        if (!running) return
        sm.unregisterListener(this)
        running = false
        lastStepTotal = -1f
    }

    @Synchronized
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val total = event.values[0]
                if (lastStepTotal >= 0 && total >= lastStepTotal) stepDelta += (total - lastStepTotal).toInt()
                lastStepTotal = total
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                val mag = sqrt((x * x + y * y + z * z).toDouble())
                motionSum += abs(mag - 9.81)
                motionSamples++
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Builds the `vibe_telemetry` frame for the interval since the previous call and resets counters. */
    @Synchronized
    fun drain(): JSONObject {
        val avgMotion = if (motionSamples > 0) motionSum / motionSamples else 0.0
        val moving = if (usingSteps) stepDelta > 10 else avgMotion > 0.9
        val state = when {
            usingSteps && stepDelta > 40 -> "walking"
            moving -> "active"
            else -> "sedentary"
        }
        val out = JSONObject()
            .put("type", "vibe_telemetry")
            .put("stepCountDelta", stepDelta)
            .put("motionState", state)
            .put("timestamp", System.currentTimeMillis())
        stepDelta = 0
        motionSum = 0.0
        motionSamples = 0
        return out
    }
}
