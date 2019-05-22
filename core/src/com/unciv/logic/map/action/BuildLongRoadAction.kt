package com.unciv.logic.map.action

import com.unciv.logic.map.BFS
import com.unciv.logic.map.MapUnit
import com.unciv.logic.map.TileInfo

class BuildLongRoadAction(
        mapUnit: MapUnit = MapUnit(),
        val target: TileInfo = TileInfo()
) : MapUnitAction(mapUnit) {

    override fun name(): String = "Build Long Road"

    override fun shouldStopOnEnemyInSight(): Boolean = true

    override fun isAvailable(): Boolean
            = unit.hasUnique("Can build improvements on tiles")
            && getPath(target).isNotEmpty()

    override fun doPreTurnAction() {

        // we're working!
        if (unit.currentTile.improvementInProgress != null)
            return

        if (startWorkingOnRoad())
            return


        // we reached our target? And road is finished?
        if (unit.currentTile.position == target.position
                && isRoadFinished(unit.currentTile)) {
            unit.action = null
            return
        }

        // move one step forward - and start building
        if (stepForward(target)) {
            startWorkingOnRoad()
        } else if (unit.currentMovement > 1f) {
            unit.action = null
            return
        }

    }

    // because the unit is building a road, we need to use a shortest path that is
    // independent of movement costs, but should respect impassable terrain like water and enemy territory
    private fun stepForward(destination: TileInfo): Boolean {
        var success = false
        val tilesUnitCanCurrentlyReach = unit.getDistanceToTiles().keys
        for (step in getPath(destination).drop(1)) {
            if(step !in tilesUnitCanCurrentlyReach) return false // we're out of tiles in reachable distance, no need to check any further

            if (unit.currentMovement > 0f && unit.canMoveTo(step)) {
                unit.moveToTile(step)
                success = true

                // if there is a road already, take multiple steps, otherwise this is where we're going to build a road
                if (!isRoadFinished(step)) return true

            } else break
        }
        return success
    }

    private fun isRoadFinished(tile: TileInfo): Boolean {
        return tile.roadStatus >= unit.civInfo.tech.getBestRoadAvailable()
    }

    private fun getPath(destination: TileInfo): List<TileInfo> {
        // BFS is not very efficient
        return BFS(unit.currentTile) { isRoadableTile(it) }
                .stepUntilDestination(destination)
                .getPathTo(destination).reversed()
    }

    private fun isRoadableTile(it: TileInfo) = it.isLand && unit.canPassThrough(it)

    private fun startWorkingOnRoad(): Boolean {
        val tile = unit.currentTile
        if (unit.currentMovement > 0 && isRoadableTile(tile)) {
            val roadToBuild = unit.civInfo.tech.getBestRoadAvailable()
            roadToBuild.improvement()?.let { improvement ->
                if (tile.roadStatus < roadToBuild && tile.improvementInProgress != improvement.name) {
                    tile.startWorkingOnImprovement(improvement, unit.civInfo)
                    return true
                }
            }
        }
        return false
    }


}