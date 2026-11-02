package com.smartyourtv.housecameras.core

/** The banked camera-stage pattern (owner, 2026-10-22): on a wide
 *  surface the stage comes first (tall, leading the layout) and the
 *  grids are EXACT-FILL — HOUSE 3×2, DVR one row of 4, ALL 5×2.
 *  Pure spec, unit-tested; the UI sizes tiles by weight so they
 *  fill the stage edge to edge on any screen. */
object StageSpec {
    data class View(val name: String, val cols: Int, val rows: Int, val label: String)
    val HOUSE = View("HOUSE", 3, 2, "House")
    val DVR = View("DVR", 4, 1, "DVR")
    val ALL = View("ALL", 5, 2, "All")
    val VIEWS = listOf(HOUSE, DVR, ALL)
    fun byName(name: String): View = VIEWS.firstOrNull { it.name == name } ?: HOUSE
    fun tiles(view: View): Int = view.cols * view.rows
}
