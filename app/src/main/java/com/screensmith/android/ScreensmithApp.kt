package com.screensmith.android

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import com.screensmith.android.data.ProjectRepository

// App-scoped holder for the repositories (project data, MQTT connection) -
// plain manual DI (no Hilt/Koin) to keep the dependency graph obvious for a
// first Android project rather than adding a framework on top of an
// already-new stack.
//
// Also the app's Coil configuration, and it has to be: every icon in a
// project is an SVG (lib/android-export.ts ships the vectors themselves
// rather than pre-flattened bitmaps, so an icon chosen at runtime can be
// drawn at whatever size the object turns out to be). Coil 2 does not decode
// SVG out of the box - `coil-svg` has been on the classpath since the first
// scaffold, but its decoder only enters the pipeline once it is registered
// here, and until it was, every AsyncImage pointed at a .svg drew nothing at
// all. A missing icon looks exactly like an icon the project never set,
// which is how this survived while MQTTIconField was the only type that
// could have one.
class ScreensmithApp : Application(), ImageLoaderFactory {
    lateinit var projectRepository: ProjectRepository
        private set

    override fun onCreate() {
        super.onCreate()
        projectRepository = ProjectRepository(this)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(SvgDecoder.Factory()) }
            .build()
}
