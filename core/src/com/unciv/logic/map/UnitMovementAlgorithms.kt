package com.unciv.logic.map

import com.badlogic.gdx.math.Vector2
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.UniqueAbility
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.replay.Action
import com.unciv.logic.replay.ActionType
import com.unciv.logic.replay.MovementData

class UnitMovementAlgorithms(val unit:MapUnit) {

    // This function is called ALL THE TIME and should be as time-optimal as possible!
    fun getMovementCostBetweenAdjacentTiles(from: TileInfo, to: TileInfo, civInfo: CivilizationInfo): Float {

        if ((from.isLand != to.isLand) && unit.type.isLandUnit() &&
                (unit.civInfo.nation.unique != UniqueAbility.VIKING_FURY))
            return 100f // this is embarkment or disembarkment, and will take the entire turn

        var extraCost = 0f

        val toOwner = to.getOwner()
        if (toOwner != null && to.isLand && toOwner.hasActiveGreatWall && civInfo.isAtWarWith(toOwner))
            extraCost += 1

        if (from.roadStatus == RoadStatus.Railroad && to.roadStatus == RoadStatus.Railroad)
            return 1 / 10f + extraCost

        val areConnectedByRoad = from.hasConnection(civInfo) && to.hasConnection(civInfo)
        if(from.isConnectedByRiver(to) &&
                (!areConnectedByRoad || !civInfo.tech.roadsConnectAcrossRivers)){
            return 100f // Rivers take the entire turn to cross
        }

        if (areConnectedByRoad)
        {
            return if (unit.civInfo.tech.movementSpeedOnRoadsImproved) 1 / 3f + extraCost
            else 1 / 2f + extraCost
        }
        if (unit.ignoresTerrainCost) return 1f + extraCost
        if (unit.doubleMovementInForestAndJungle && (to.terrainFeature == Constants.forest || to.terrainFeature == Constants.jungle))
            return 1f + extraCost // usually forest and jungle take 2 movements, so here it is 1
        if (civInfo.nation.greatAndeanRoad && to.baseTerrain == Constants.hill)
            return 1f + extraCost // usually hills take 2 movements, so here it is 1

        if (unit.roughTerrainPenalty && to.isRoughTerrain())
            return 4f + extraCost

        if (unit.doubleMovementInCoast && to.baseTerrain == Constants.coast)
            return 1 / 2f + extraCost

        if (unit.doubleMovementInSnowTundraAndHills && to.baseTerrain == Constants.hill)
            return 1f + extraCost // usually hills take 2
        if (unit.doubleMovementInSnowTundraAndHills && (to.baseTerrain == Constants.snow || to.baseTerrain == Constants.tundra))
            return 1 / 2f + extraCost

        return to.getLastTerrain().movementCost.toFloat() + extraCost // no road
    }

    class ParentTileAndTotalDistance(val parentTile: TileInfo, val totalDistance: Float)

    fun getDistanceToTilesWithinTurn(origin: Vector2, unitMovement: Float): PathsToTilesWithinTurn {
        val distanceToTiles = PathsToTilesWithinTurn()
        if (unitMovement == 0f) return distanceToTiles

        val currentUnitTile = unit.currentTile
        // This is for performance, because this is called all the time
        val unitTile = if(origin==currentUnitTile.position) currentUnitTile else currentUnitTile.tileMap[origin]
        distanceToTiles[unitTile] = ParentTileAndTotalDistance(unitTile, 0f)
        var tilesToCheck = listOf(unitTile)

        while (tilesToCheck.isNotEmpty()) {
            val updatedTiles = ArrayList<TileInfo>()
            for (tileToCheck in tilesToCheck)
                for (neighbor in tileToCheck.neighbors) {
                    var totalDistanceToTile: Float

                    if (!canPassThrough(neighbor))
                        totalDistanceToTile = unitMovement // Can't go here.
                    // The reason that we don't just "return" is so that when calculating how to reach an enemy,
                    // You need to assume his tile is reachable, otherwise all movement algs on reaching enemy
                    // cities and units goes kaput.

                    else {
                        val distanceBetweenTiles = getMovementCostBetweenAdjacentTiles(tileToCheck, neighbor, unit.civInfo)
                        totalDistanceToTile = distanceToTiles[tileToCheck]!!.totalDistance + distanceBetweenTiles
                    }

                    if (!distanceToTiles.containsKey(neighbor) || distanceToTiles[neighbor]!!.totalDistance > totalDistanceToTile) { // this is the new best path
                        if (totalDistanceToTile < unitMovement)  // We can still keep moving from here!
                            updatedTiles += neighbor
                        else
                            totalDistanceToTile = unitMovement
                        // In Civ V, you can always travel between adjacent tiles, even if you don't technically
                        // have enough movement points - it simple depletes what you have

                        distanceToTiles[neighbor] = ParentTileAndTotalDistance(tileToCheck, totalDistanceToTile)
                    }
                }

            tilesToCheck = updatedTiles
        }

        return distanceToTiles
    }

    fun getShortestPath(destination: TileInfo): List<TileInfo> {
        val currentTile = unit.getTile()
        if (currentTile.position == destination) return listOf(currentTile) // edge case that's needed, so that workers will know that they can reach their own tile. *sigh*

        var tilesToCheck = listOf(currentTile)
        val movementTreeParents = HashMap<TileInfo, TileInfo?>() // contains a map of "you can get from X to Y in that turn"
        movementTreeParents[currentTile] = null

        var movementThisTurn = unit.currentMovement
        var distance = 1
        val newTilesToCheck = ArrayList<TileInfo>()
        val distanceToDestination = HashMap<TileInfo, Float>()
        while (true) {
            if (distance == 2) // only set this once after distance > 1
                movementThisTurn = unit.getMaxMovement().toFloat()
            newTilesToCheck.clear()
            distanceToDestination.clear()
            for (tileToCheck in tilesToCheck) {
                val distanceToTilesThisTurn = getDistanceToTilesWithinTurn(tileToCheck.position, movementThisTurn)
                for (reachableTile in distanceToTilesThisTurn.keys) {
                    if (reachableTile == destination)
                        distanceToDestination[tileToCheck] = distanceToTilesThisTurn[reachableTile]!!.totalDistance
                    else {
                        if (movementTreeParents.containsKey(reachableTile)) continue // We cannot be faster than anything existing...
                        if (!canMoveTo(reachableTile)) continue // This is a tile that we can't actually enter - either an intermediary tile containing our unit, or an enemy unit/city
                        movementTreeParents[reachableTile] = tileToCheck
                        newTilesToCheck.add(reachableTile)
                    }
                }
            }

            if (distanceToDestination.isNotEmpty()) {
                val path = mutableListOf(destination) // Traverse the tree upwards to get the list of tiles leading to the destination,
                // Get the tile from which the distance to the final tile in least -
                // this is so that when we finally get there, we'll have as many movement points as possible
                var intermediateTile = distanceToDestination.minBy { it.value }!!.key
                while (intermediateTile != currentTile) {
                    path.add(intermediateTile)
                    intermediateTile = movementTreeParents[intermediateTile]!!
                }
                path.reverse() // and reverse in order to get the list in chronological order
                return path
            }

            if (newTilesToCheck.isEmpty()) return emptyList() // there is NO PATH (eg blocked by enemy units)

            // no need to check tiles that are surrounded by reachable tiles, only need to check the edgemost tiles.
            // Because anything we can reach from intermediate tiles, can be more easily reached by the edgemost tiles,
            // since we'll have to pass through an edgemost tile in order to reach the destination anyway
            tilesToCheck = newTilesToCheck.filterNot { tile -> tile.neighbors.all { it in newTilesToCheck || it in tilesToCheck } }

            distance++
        }
    }

    fun getTileToMoveToThisTurn(finalDestination: TileInfo): TileInfo {

        val currentTile = unit.getTile()
        if (currentTile == finalDestination) return currentTile

        // head there directly
        if (unit.type.isAirUnit()) return finalDestination

        val distanceToTiles = getDistanceToTiles()

        class UnreachableDestinationException : Exception()

        // If the tile is far away, we need to build a path how to get there, and then take the first step
        if (!distanceToTiles.containsKey(finalDestination))
            return getShortestPath(finalDestination).firstOrNull()
                    ?: throw UnreachableDestinationException()

        // we should be able to get there this turn
        if (canMoveTo(finalDestination))
            return finalDestination

        // Someone is blocking to the path to the final tile...
        val destinationNeighbors = finalDestination.neighbors
        return when (currentTile) {
            in destinationNeighbors -> currentTile // We're right nearby anyway, no need to move
            else -> destinationNeighbors.asSequence()
                    .filter { distanceToTiles.containsKey(it) && canMoveTo(it) }
                    .minBy { distanceToTiles.getValue(it).totalDistance } // we can get a little closer
                    ?: currentTile // We can't get closer...
        }
    }

    /**
     * @return The tile that we reached this turn
     */
    fun headTowards(destination: TileInfo): TileInfo {
        val destinationTileThisTurn = getTileToMoveToThisTurn(destination)
        moveToTile(destinationTileThisTurn)
        return destinationTileThisTurn
    }

    /** This is performance-heavy - use as last resort, only after checking everything else! */
    fun canReach(destination: TileInfo): Boolean {
        if (unit.type.isAirUnit())
            return unit.currentTile.aerialDistanceTo(destination) <= unit.getRange()*2
        return getShortestPath(destination).any()
    }


    fun teleportToClosestMoveableTile() {
        var allowedTile: TileInfo? = null
        var distance = 0
        // When we didn't limit the allowed distance the game would sometimes spend a whole minute looking for a suitable tile.
        while (allowedTile == null && distance < 5) {
            distance++
            allowedTile = unit.getTile().getTilesAtDistance(distance)
                    .firstOrNull { canMoveTo(it) }
        }

        // No tile within 4 spaces? move him to a city.
        if (allowedTile == null) {
            for (city in unit.civInfo.cities) {
                allowedTile = city.getTiles()
                        .firstOrNull { canMoveTo(it) }
                if (allowedTile != null) break
            }
        }
        unit.removeFromTile() // we "teleport" them away
        if (allowedTile != null) // it's possible that there is no close tile, and all the guy's cities are full. Screw him then.
            unit.putInTile(allowedTile)
    }


    fun moveToTile(destination: TileInfo, savedPath: List<Vector2>? = null) {
        if (destination == unit.getTile()) return // already here!

        class CantEnterThisTileException(msg: String) : Exception(msg)
        if (!canMoveTo(destination))
            throw CantEnterThisTileException("$unit can't enter $destination")

        val gameInfo = unit.currentTile.tileMap.gameInfo
        val unitId = unit.getUnitId()

        if (unit.type.isAirUnit()) { // they move differently from all other units
            // save movement data to replay
            if (UncivGame.Current.replayDebugSwitch && !gameInfo.replayMode) {
                val data = MovementData(unitId, destination.position)
                val action = Action(ActionType.Move, data)
                gameInfo.replay?.saveAction(unit.civInfo, action)
            }

            unit.action = null
            unit.removeFromTile()
            unit.isTransported = false // it has left the carrier by own means
            unit.putInTile(destination)
            unit.currentMovement = 0f
            return
        }

        val distanceToTiles = getDistanceToTiles()

        class YouCantGetThereFromHereException(msg: String) : Exception(msg)
        if (!distanceToTiles.containsKey(destination))
            throw YouCantGetThereFromHereException("$unit can't get from ${unit.currentTile.position} to ${destination.position}.")

        if (destination.isCityCenter() && destination.getOwner() != unit.civInfo && !destination.getCity()!!.hasJustBeenConquered)
            throw Exception("This is an enemy city, you can't go here!")

        unit.currentMovement -= distanceToTiles[destination]!!.totalDistance
        if (unit.currentMovement < 0.1) unit.currentMovement = 0f // silly floats which are "almost zero"
        if (unit.isFortified() || unit.action == Constants.unitActionSetUp || unit.isSleeping())
            unit.action = null // unfortify/setup after moving

        // If this unit is a carrier, keep record of its air payload whereabouts.
        val origin = unit.getTile()
        unit.removeFromTile()
        unit.putInTile(destination)

        // The .toList() here is because we have a sequence that's running on the units in the tile,
        // then if we move one of the units we'll get a ConcurrentModificationException, se we save them all to a list
        for (payload in origin.getUnits().filter { it.isTransported && unit.canTransport(it) }.toList()) {  // bring along the payloads
            payload.removeFromTile()
            payload.putInTile(destination)
            payload.isTransported = true // restore the flag to not leave the payload in the cit
        }

        // Unit maintenance changed
        if (unit.canGarrison()
                && (origin.isCityCenter() || destination.isCityCenter())
                && unit.civInfo.hasUnique("Units in cities cost no Maintenance")
        ) unit.civInfo.updateStatsForNextTurn()

        // Move through all intermediate tiles to get ancient ruins, barb encampments
        // and to view tiles along the way
        // We only activate the moveThroughTile AFTER the putInTile because of a really weird bug -
        // If you're going to (or past) a ruin, and you activate the ruin bonus, and A UNIT spawns.
        // That unit could now be blocking your entrance to the destination, so the putInTile would fail! =0
        // Instead, we move you to the destination directly, and only afterwards activate the various tiles on the way.
        val pathToFinalTile = if (gameInfo.replayMode) savedPath!!.map { unit.currentTile.tileMap[it] }
        else distanceToTiles.getPathToTile(destination)

        for (tile in pathToFinalTile) {
            unit.moveThroughTile(tile)
        }

        // save movement data to replay
        if (UncivGame.Current.replayDebugSwitch && !gameInfo.replayMode) {
            val path = pathToFinalTile.map { it.position }
            val data = MovementData(unitId, destination.position, path = path)
            val action = Action(ActionType.Move, data)
            gameInfo.replay?.saveAction(unit.civInfo, action)
        }
    }


    /**
     * Designates whether we can enter the tile - without attacking
     * DOES NOT designate whether we can reach that tile in the current turn
     */
    fun canMoveTo(tile: TileInfo): Boolean {
        if (unit.type.isAirUnit())
            return canAirUnitMoveTo(tile, unit)

        if (!canPassThrough(tile))
            return false

        if (unit.type.isCivilian())
            return tile.civilianUnit == null && (tile.militaryUnit == null || tile.militaryUnit!!.owner == unit.owner)
        else return tile.militaryUnit == null && (tile.civilianUnit == null || tile.civilianUnit!!.owner == unit.owner)
    }

    private fun canAirUnitMoveTo(tile: TileInfo, unit: MapUnit): Boolean {
        // landing in the city
        if (tile.isCityCenter()) {
            if (tile.airUnits.filter { !it.isTransported }.size < 6 && tile.getCity()?.civInfo == unit.civInfo)
                return true // if city is free - no problem, get in
        } // let's check whether it enters city on carrier now...

        if (tile.militaryUnit != null) {
            val unitAtDestination = tile.militaryUnit!!
            return unitAtDestination.canTransport(unit)
        }
        return false
    }


    // This is the most called function in the entire game,
    // so multiple callees of this function have been optimized,
    // because optimization on this function results in massive benefits!
    fun canPassThrough(tile: TileInfo): Boolean {
        if (tile.isImpassible()){
            // special exception - ice tiles are technically impassible, but somme units can move through them anyway
            if (!(tile.terrainFeature == Constants.ice && unit.canEnterIceTiles))
                return false
        }
        if (tile.isLand
                && unit.type.isWaterUnit()
                // Check that the tile is not a coastal city's center
                && !(tile.isCityCenter() && tile.isCoastalTile()))
            return false


        if (tile.isWater && unit.type.isLandUnit()) {
            if (!unit.civInfo.tech.unitsCanEmbark) return false
            if (tile.isOcean && !unit.civInfo.tech.embarkedUnitsCanEnterOcean)
                return false
        }
        if (tile.isOcean && unit.civInfo.nation.unique != UniqueAbility.WAYFINDING) {
            if (unit.cannotEnterOceanTiles) return false
            if (unit.cannotEnterOceanTilesUntilAstronomy
                    && !unit.civInfo.tech.isResearched("Astronomy"))
                return false
        }
        if (tile.naturalWonder != null) return false

        val tileOwner = tile.getOwner()
        if (tileOwner != null && tileOwner != unit.civInfo) { // comparing the CivInfo objects is cheaper than comparing strings?
            if (tile.isCityCenter() && !tile.getCity()!!.hasJustBeenConquered) return false
            if (!unit.civInfo.canEnterTiles(tileOwner)
                    && !(unit.civInfo.isPlayerCivilization() && tileOwner.isCityState())) return false
            // AIs won't enter city-state's border.
        }

        val firstUnit = tile.getFirstUnit()
        if (firstUnit != null && firstUnit.civInfo != unit.civInfo && unit.civInfo.isAtWarWith(firstUnit.civInfo))
            return false

        return true
    }

    fun getDistanceToTiles(): PathsToTilesWithinTurn = getDistanceToTilesWithinTurn(unit.currentTile.position, unit.currentMovement)

    fun getAerialPathsToCities(): HashMap<TileInfo, ArrayList<TileInfo>> {
        var tilesToCheck = ArrayList<TileInfo>()
        /** each tile reached points to its parent tile, where we got to it from */
        val tilesReached = HashMap<TileInfo, TileInfo>()

        val startingTile = unit.currentTile
        tilesToCheck.add(startingTile)
        tilesReached[startingTile] = startingTile


        while (tilesToCheck.isNotEmpty()) {
            val newTilesToCheck = ArrayList<TileInfo>()
            for (currentTileToCheck in tilesToCheck) {
                val reachableTiles = currentTileToCheck.getTilesInDistance(unit.getRange())
                        .filter { unit.movement.canMoveTo(it) }
                for (reachableTile in reachableTiles) {
                    if (tilesReached.containsKey(reachableTile)) continue
                    tilesReached[reachableTile] = currentTileToCheck
                    newTilesToCheck.add(reachableTile)
                }
            }
            tilesToCheck = newTilesToCheck
        }

        val pathsToCities = HashMap<TileInfo, ArrayList<TileInfo>>()

        for (city in tilesReached.keys) {
            val path = ArrayList<TileInfo>()
            var currentCity = city
            while (currentCity != startingTile) { // we don't add the "starting tile" to the arraylist
                path.add(currentCity)
                currentCity = tilesReached[currentCity]!! // go to parent
            }
            path.reverse()
            pathsToCities[city] = path
        }

        pathsToCities.remove(startingTile)
        return pathsToCities
    }
}

class PathsToTilesWithinTurn : LinkedHashMap<TileInfo, UnitMovementAlgorithms.ParentTileAndTotalDistance>() {
    fun getPathToTile(tile: TileInfo): List<TileInfo> {
        if (!containsKey(tile)) throw Exception("Can't reach this tile!")
        val reversePathList = ArrayList<TileInfo>()
        var currentTile = tile
        while (get(currentTile)!!.parentTile != currentTile) {
            reversePathList.add(currentTile)
            currentTile = get(currentTile)!!.parentTile
        }
        return reversePathList.reversed()
    }
}