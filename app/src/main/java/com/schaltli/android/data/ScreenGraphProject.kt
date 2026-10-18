package com.schaltli.android.data

/**
 * The screen graph of a loaded project (the designer's device contract
 * §2.10): what the app asks of it - next and previous over the main row, the
 * neighbour on an edge, where the pull tabs stand, the start screen. The
 * decisions are [ScreenGraph]'s, held to the designer's vectors; this only
 * reads them off the [Project], as the firmware's ScreenGraphProject.h does.
 *
 * An export from before the screen graph has no `mainRow` and no
 * neighbours: its main row is the screens that are not hidden, as it paged
 * before, and each of them has the row's screens beside it as neighbours -
 * what the designer would export for it today - so an app updated without a
 * new deploy swipes as it did. It has no tab: the export carries no colour.
 */
object ScreenGraphProject {

    fun mainRowIds(project: Project): List<String> =
        project.mainRow.ifEmpty { project.screens.filter { !it.hidden }.map { it.id } }

    /** The id of the screen on an edge of [screen]: as exported, or for an export from before the graph the row's screen beside it. */
    fun neighbourId(project: Project, screen: Screen, side: ScreenGraph.Edge): String? {
        if (project.mainRow.isNotEmpty()) return screen.neighbours[side.key]
        if (side != ScreenGraph.Edge.LEFT && side != ScreenGraph.Edge.RIGHT) return null
        val row = mainRowIds(project)
        val i = row.indexOf(screen.id)
        if (i < 0) return null
        return if (side == ScreenGraph.Edge.LEFT) row.getOrNull(i - 1) else row.getOrNull(i + 1)
    }

    fun graphScreenOf(project: Project, screen: Screen) = ScreenGraph.GraphScreen(
        id = screen.id,
        neighbours = ScreenGraph.Edge.entries.mapNotNull { e -> neighbourId(project, screen, e)?.let { e to it } }.toMap(),
        back = ScreenGraph.edgeFrom(screen.back),
        swipes = screen.buttonActions
            .filterKeys { ScreenGraph.sideOfSwipe(it) != null }
            .mapValues { (_, a) -> ScreenGraph.Action(a.type, a.side, a.targetScreenId) },
    )

    /** Next (1) or previous (-1) over the main row from [currentId]; a screen off it goes from its page. */
    fun rowStep(project: Project, currentId: String, delta: Int): String? {
        val screen = project.screens.find { it.id == currentId }
        val back = ScreenGraph.edgeFrom(screen?.back)
        val page = if (screen != null && back != null) screen.neighbours[back.key] else null
        return ScreenGraph.rowStep(mainRowIds(project), currentId, page, delta)
    }

    /** The first screen of the main row, else the first screen. */
    fun startScreenId(project: Project): String? =
        mainRowIds(project).firstOrNull { id -> project.screens.any { it.id == id } } ?: project.screens.firstOrNull()?.id

    /** Whether the swipe [swipeId] on [screen] leads to the neighbour it pulls in - where the tab stands and a slide follows. */
    fun swipeLeadsToNeighbour(project: Project, screen: Screen, swipeId: String): Boolean {
        val side = ScreenGraph.sideOfSwipe(swipeId) ?: return false
        return ScreenGraph.swipeLeadsTo(graphScreenOf(project, screen), side, mainRowIds(project))
    }

    fun tabSides(project: Project, screen: Screen): List<ScreenGraph.Edge> =
        ScreenGraph.tabSides(graphScreenOf(project, screen), mainRowIds(project))
}
