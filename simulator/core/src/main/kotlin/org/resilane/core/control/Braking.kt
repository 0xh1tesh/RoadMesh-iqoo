package org.resilane.core.control

/** Demo kinematics, not measured braking. Position and lamp state use the same timeline. */
data class BrakeSample(val distanceM: Float, val speedMps: Float, val lampsOn: Boolean)

object Braking {
    fun sample(elapsedSeconds: Float, initialSpeed: Float = 2f, deceleration: Float = 2f): BrakeSample {
        require(elapsedSeconds.isFinite() && initialSpeed.isFinite() && deceleration.isFinite() && initialSpeed >= 0 && deceleration > 0)
        val t = elapsedSeconds.coerceIn(0f, initialSpeed / deceleration)
        return BrakeSample(initialSpeed * t - deceleration * t * t / 2,
            (initialSpeed - deceleration * t).coerceAtLeast(0f), elapsedSeconds < 5f)
    }
}
