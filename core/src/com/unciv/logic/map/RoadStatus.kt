package com.unciv.logic.map

import com.unciv.models.ruleset.Ruleset

/**
 * You can use RoadStatus.name to identify [Road] and [Railroad]
 * in string-based identification, as done in [improvement].
 * 
 * Note: Order is important, [ordinal] _is_ compared - please interpret as "roadLevel".
 */
enum class RoadStatus(
    val upkeep: Int = 0,
    val movement: Float = 1f,
    val movementImproved: Float = 1f,
    val removeAction: String? = null
) {

    None,
    Road (1, 0.5f, 1/3f, "Remove Road"),
    Railroad (2, 0.1f, 0.1f, "Remove Railroad");

    /** returns null for [None] */
    fun improvement(ruleset: Ruleset) = ruleset.tileImprovements[this.name]

}
