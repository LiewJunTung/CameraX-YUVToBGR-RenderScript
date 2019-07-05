package org.netvirta.thecamerax

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Provides accelerometer and gyroscope readings
 */
class MotionSensorController(context: Context) : SensorEventListener {


    private var sensorManager: SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)

    private val rotationMatrix = FloatArray(9)
    val orientationAngles = FloatArray(3)


//    val accelerometerValues: FloatArray
//        get() = mAccelerometerValues.clone()
//
//    val gyroscopeValues: FloatArray
//        get() = mGyroscopeValues.clone()

    fun startListening() {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { accelerometer ->
            sensorManager.registerListener(
                    this,
                    accelerometer,
                    SensorManager.SENSOR_DELAY_NORMAL,
                    SensorManager.SENSOR_DELAY_UI
            )
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also { magneticField ->
            sensorManager.registerListener(
                    this,
                    magneticField,
                    SensorManager.SENSOR_DELAY_NORMAL,
                    SensorManager.SENSOR_DELAY_UI
            )
        }
    }

    fun stopListening() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.size)
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.size)
        }

    }

    override fun onAccuracyChanged(sensor: Sensor, i: Int) {}

    fun updateOrientationAngles() {

        // Update rotation matrix, which is needed to update orientation angles.
        SensorManager.getRotationMatrix(
                rotationMatrix,
                null,
                accelerometerReading,
                magnetometerReading
        )

        // "mRotationMatrix" now has up-to-date information.

        SensorManager.getOrientation(rotationMatrix, orientationAngles)

        // "mOrientationAngles" now has up-to-date information.
    }
}
