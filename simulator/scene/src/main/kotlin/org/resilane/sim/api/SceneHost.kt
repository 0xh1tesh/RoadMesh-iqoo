package org.resilane.sim.api

/** Small, renderer-independent boundary; new source types can be added without BLE dependencies. */
sealed interface SceneEvent {
    data object VehicleVisible : SceneEvent
    data class BrakeStarted(val eventId: String) : SceneEvent
    data object VehicleHidden : SceneEvent
}

interface SceneHost {
    fun brake(): Boolean
    fun resetScene()
}
