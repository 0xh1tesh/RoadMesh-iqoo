package com.jsons.roadmesh

import android.app.Application
import com.jsons.roadmesh.service.MeshSession

/** Small manual composition root; replace adapters without changing domain or scene code. */
class RoadMeshApplication : Application() {
    val preferences by lazy { AppPreferences(this) }
    val session by lazy { MeshSession(this, preferences) }
}
