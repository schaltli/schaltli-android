package com.screensmith.android

import android.app.Application
import com.screensmith.android.data.ProjectRepository

// App-scoped holder for the repositories (project data, MQTT connection) -
// plain manual DI (no Hilt/Koin) to keep the dependency graph obvious for a
// first Android project rather than adding a framework on top of an
// already-new stack.
class ScreensmithApp : Application() {
    lateinit var projectRepository: ProjectRepository
        private set

    override fun onCreate() {
        super.onCreate()
        projectRepository = ProjectRepository(this)
    }
}
