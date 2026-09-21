package com.screensmith.android.ui

import androidx.compose.runtime.compositionLocalOf

/**
 * Which installation of the project bundle the files on disk belong to.
 *
 * Anything decoded from a bundle file and kept in a `remember` has to name
 * this alongside the path, because the path is not an identity: every
 * project's background is `assets/<screenId>.png` and every project's font is
 * `assets/fonts/<family>.ttf`. A deploy replaces the bytes under those names
 * without ever changing one of them, so a cache keyed on the path alone
 * survives the project it was filled for.
 *
 * The project itself cannot stand in for this. `ProjectRepository.project` is
 * a StateFlow, and a StateFlow drops a value equal to the one it holds - two
 * installs of the same project.json emit once - while the bundle around that
 * project.json may be entirely different. The counter behind this local
 * changes on every install, whatever the zip contained.
 *
 * A composition local rather than a parameter: the composables that need it
 * are leaves - a typeface cache inside a button inside a tab-control - and
 * every composable in between would be carrying something it has no use for.
 *
 * The default is for previews and tests, which have no repository: they
 * compose once and never see a deploy, so any constant will do.
 */
val LocalBundleInstallation = compositionLocalOf { 0L }
