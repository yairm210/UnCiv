package com.unciv.logic.map

import com.badlogic.gdx.math.Vector2
import com.unciv.Constants
import com.unciv.logic.HexMath.getDistance
import com.unciv.logic.civilization.CivilizationInfo

class UnitMovementAlgorithms(val unit:MapUnit) {

    // This function is called ALL THE TIME and should be as time-optimal as possible!
    fun getMovementCostBetweenAdjacentTiles(from: TileInfo, to: TileInfo, civInfo: CivilizationInfo): Float {

        if (from.isLand != to.isLand && unit.type.isLandUnit())
            if (unit.civInfo.nation.disembarkCosts1 && from.isWater && to.isLand) return 1f
            else return 100f // this is embarkment or disembarkment, and will take the entire turn

        // land units will still spend all movement points to embark even with this unique
        if (unit.allTilesCosts1)
            return 1f

        var extraCost = 0f

        val toOwner = to.getOwner()
        if (toOwner != null && to.isLand && toOwner.hasActiveGreatWall && civInfo.isAtWarWith(toOwner))
            extraCost += 1

        if (from.roadStatus == RoadStatus.Railroad && to.roadStatus == RoadStatus.Railroad)
            return RoadStatus.Railroad.movement + extraCost

        val areConnectedByRoad = from.hasConnection(civInfo) && to.hasConnection(civInfo)
        val areConnectedByRiver = from.isConnectedByRiver(to)

        if (areConnectedByRoad && (!areConnectedByRiver || civInfo.tech.roadsConnectAcrossRivers))
            return unit.civInfo.tech.movementSpeedOnRoads + extraCost

        if (unit.ignoresTerrainCost) return 1f + extraCost
        if (areConnectedByRiver) return 100f  // Rivers take the entire turn to cross

        if (unit.doubleMovementInForestAndJungle &&
                (to.terrainFeatures.contains(Constants.forest) || to.terrainFeatures.contains(Constants.jungle)))
            return 1f + extraCost // usually forest and jungle take 2 movements, so here it is 1

        if (unit.roughTerrainPenalty && to.isRoughTerrain())
            return 100f // units that have to sped all movement in rough terrain, have to spend all movement in rough terrain
        // Placement of this 'if' based on testing, see #4232
        
        if (civInfo.nation.ignoreHillMovementCost && to.isHill())
            return 1f + extraCost // usually hills take 2 movements, so here it is 1

        if (unit.doubleMovementInCoast && to.baseTerrain == Constants.coast)
            return 1 / 2f + extraCost

        if (unit.doubleMovementInSnowTundraAndHills && to.isHill())
            return 1f + extraCost // usually hills take 2
        if (unit.doubleMovementInSnowTundraAndHills && (to.baseTerrain == Constants.snow || to.baseTerrain == Constants.tundra))
            return 1 / 2f + extraCost

        return to.getLastTerrain().movementCost.toFloat() + extraCost // no road
    }

    class ParentTileAndTotalDistance(val parentTile: TileInfo, val totalDistance: Float)

    fun isUnknownTileWeShouldAssumeToBePassable(tileInfo: TileInfo) = !unit.civInfo.exploredTiles.contains(tileInfo.position)

    /**
     * Does not consider if tiles can actually be entered, use canMoveTo for that.
     * If a tile can be reached within the turn, but it cannot be passed through, the total distance to it is set to unitMovement
     */
    fun getDistanceToTilesWithinTurn(origin: Vector2, unitMovement: Float): PathsToTilesWithinTurn {
        val distanceToTiles = PathsToTilesWithinTurn()
        if (unitMovement == 0f) return distanceToTiles

        val currentUnitTile = unit.currentTile
        // This is for performance, because this is called all the time
        val unitTile = if (origin == currentUnitTile.position) currentUnitTile else currentUnitTile.tileMap[origin]
        distanceToTiles[unitTile] = ParentTileAndTotalDistance(unitTile, 0f)
        var tilesToCheck = listOf(unitTile)

        while (tilesToCheck.isNotEmpty()) {
            val updatedTiles = ArrayList<TileInfo>()
            for (tileToCheck in tilesToCheck)
                for (neighbor in tileToCheck.neighbors) {
                    var totalDistanceToTile: Float

                    if (unit.civInfo.exploredTiles.contains(neighbor.position)) {
                        if (!canPassThrough(neighbor))
                            totalDistanceToTile = unitMovement // Can't go here.
                        // The reason that we don't just "return" is so that when calculating how to reach an enemy,
                        // You need to assume his tile is reachable, otherwise all movement algs on reaching enemy
                        // cities and units goes kaput.

                        else {
                            val distanceBetweenTiles = getMovementCostBetweenAdjacentTiles(tileToCheck, neighbor, unit.civInfo)
                            totalDistanceToTile = distanceToTiles[tileToCheck]!!.totalDistance + distanceBetweenTiles
                        }
                    } else totalDistanceToTile = distanceToTiles[tileToCheck]!!.totalDistance + 1f // If we don't know then we just guess it to be 1.

                    if (!distanceToTiles.containsKey(neighbor) || distanceToTiles[neighbor]!!.totalDistance > totalDistanceToTile) { // this is the new best path
                        if (totalDistanceToTile < unitMovement)  // We can still keep moving from here!
                            updatedTiles += neighbor
                        else
                            totalDistanceToTile = unitMovement
                        // In Civ V, you can always travel between adjacent tiles, even if you don't technically
                        // have enough movement points - it simply depletes what you have

                        distanceToTiles[neighbor] = ParentTileAndTotalDistance(tileToCheck, totalDistanceToTile)
                    }
                }

            tilesToCheck = updatedTiles
        }

        return distanceToTiles
    }

    /**
     * Does not consider if the [destination] tile can actually be entered, use [canMoveTo] for that.
     * Returns an empty list if there's no way to get to the destination.
     */
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
                        if (!isUnknownTileWeShouldAssumeToBePassable(reachableTile) &&
                                 !canMoveTo(reachableTile)) continue // This is a tile that we can't actually enter - either an intermediary tile containing our unit, or an enemy unit/city
                        movementTreeParents[reachableTile] = tileToCheck
                        newTilesToCheck.add(reachableTile)
                    }
                }
            }

            if (distanceToDestination.isNotEmpty()) {
                val path = mutableListOf(destination) // Traverse the tree upwards to get the list of tiles leading to the destination,
                // Get the tile from which the distance to the final tile in least -
                // this is so that when we finally get there, we'll have as many movement points as possible
                var intermediateTile = distanceToDestination.minByOrNull { it.value }!!.key
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

        // If we can fly, head there directly
        if (unit.type.isAirUnit() || unit.type.isMissile() || unit.action == Constants.unitActionParadrop) return finalDestination

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
                .minByOrNull { distanceToTiles.getValue(it).totalDistance } // we can get a little closer
                    ?: currentTile // We can't get closer...
        }
    }

    /**
     * @return The tile that we reached this turn
     */
    fun headTowards(destination: TileInfo): TileInfo {
        val destinationTileThisTurn = getTileToMoveToThisTurn(destination)
        moveToTile(destinationTileThisTurn)
        return unit.currentTile
    }

    /** This is performance-heavy - use as last resort, only after checking everything else!
     * Also note that REACHABLE tiles are not necessarily tiles that the unit CAN ENTER */
    fun canReach(destination: TileInfo): Boolean {
        if (unit.type.isAirUnit() || unit.type.isMissile() || unit.action == Constants.unitActionParadrop)
            return canReachInCurrentTurn(destination)
        return getShortestPath(destination).any()
    }

    fun canReachInCurrentTurn(destination: TileInfo): Boolean {
        if (unit.type.isAirUnit() || unit.type.isMissile())
            return unit.currentTile.aerialDistanceTo(destination) <= unit.getRange()*2
        if (unit.action == Constants.unitActionParadrop)
            return getDistance(unit.currentTile.position, destination.position) <= unit.paradropRange && canParadropOn(destination)
        return getDistanceToTiles().containsKey(destination)
    }

    fun getReachableTilesInCurrentTurn(): Sequence<TileInfo> {
        return when {
            unit.type.isAirUnit() || unit.type.isMissile() ->
                unit.getTile().getTilesInDistanceRange(IntRange(1, unit.getRange() * 2))
            unit.action == Constants.unitActionParadrop ->
                unit.getTile().getTilesInDistance(unit.paradropRange)
                    .filter { unit.movement.canParadropOn(it) }
            else ->
                unit.movement.getDistanceToTiles().keys.asSequence()

        }
    }

    /** Returns whether we can perform a swap move to the specified tile */
    fun canUnitSwapTo(destination: TileInfo): Boolean {
        return canReachInCurrentTurn(destination) && canUnitSwapToReachableTile(destination)
    }

    /** Returns the tiles to which we can perform a swap move */
    fun getUnitSwappableTiles(): Sequence<TileInfo> {
        return getReachableTilesInCurrentTurn().filter { canUnitSwapToReachableTile(it) }
    }

    /**
     * Returns whether we can perform a unit swap move to the specified tile, given that it is
     * reachable in the current turn
     */
    private fun canUnitSwapToReachableTile(reachableTile: TileInfo): Boolean {
        // Air units cannot swap
        if (unit.type.isAirUnit() || unit.type.isMissile()) return false
        // We can't swap with ourself
        if (reachableTile == unit.getTile()) return false
        // Check whether the tile contains a unit of the same type as us that we own and that can
        // also reach our tile in its current turn.
        val otherUnit = (
            if (unit.type.isCivilian())
                reachableTile.civilianUnit
            else
                reachableTile.militaryUnit
        ) ?: return false
        val ourPosition = unit.getTile()
        if (otherUnit.owner != unit.owner || !otherUnit.movement.canReachInCurrentTurn(ourPosition)) return false
        // Check if we could enter their tile if they wouldn't be there
        otherUnit.removeFromTile()
        val weCanEnterTheirTile = canMoveTo(reachableTile)
        otherUnit.putInTile(reachableTile)
        if (!weCanEnterTheirTile) return false
        // Check if they could enter our tile if we wouldn't be here
        unit.removeFromTile()
        val theyCanEnterOurTile = otherUnit.movement.canMoveTo(ourPosition)
        unit.putInTile(ourPosition)
        if (!theyCanEnterOurTile) return false
        // All clear!
        return true
    }

    /**
     * Displace a unit - choose a viable tile close by if possible and 'teleport' the unit there.
     * This will not use movement points or check for a possible route.
     * It is used e.g. if an enemy city expands its borders, or trades or diplomacy change a unit's
     * allowed position.
     */
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
        if (allowedTile != null) { // it's possible that there is no close tile, and all the guy's cities are full. Screw him then.
            unit.putInTile(allowedTile)
            // Cancel sleep or fortification if forcibly displaced - for now, leave movement / auto / explore orders
            if (unit.isSleeping() || unit.isFortified())
                unit.action = null
        }
    }

    fun moveToTile(destination: TileInfo) {
        if (destination == unit.getTile()) return // already here!

        if (unit.type.isAirUnit() || unit.type.isMissile()) { // air units move differently from all other units
            unit.action = null
            unit.removeFromTile()
            unit.isTransported = false // it has left the carrier by own means
            unit.putInTile(destination)
            unit.currentMovement = 0f
            return
        } else if (unit.action == Constants.unitActionParadrop) { // paradropping units move differently
            unit.action = null
            unit.removeFromTile()
            unit.putInTile(destination)
            unit.currentMovement -= 1f
            unit.attacksThisTurn += 1
            // Check if unit maintenance changed
            // Is also done for other units, but because we skip everything else, we have to manually check it
            // The reason we skip everything, is that otherwise `getPathToTile()` throws an exception
            // As we can not reach our destination in a single turn
            if (unit.canGarrison()
                && (unit.getTile().isCityCenter() || destination.isCityCenter())
                && unit.civInfo.hasUnique("Units in cities cost no Maintenance")
            ) unit.civInfo.updateStatsForNextTurn()
            return
        }

        val distanceToTiles = getDistanceToTiles()
        val pathToDestination = distanceToTiles.getPathToTile(destination)
        val movableTiles = pathToDestination.takeWhile { canPassThrough(it) }
        val lastReachableTile = movableTiles.lastOrNull { canMoveTo(it) }
        if (lastReachableTile == null) // no tiles can pass though/can move to
            return
        val pathToLastReachableTile = distanceToTiles.getPathToTile(lastReachableTile)

        if (!unit.civInfo.gameInfo.gameParameters.godMode) {
            unit.currentMovement -= distanceToTiles[lastReachableTile]!!.totalDistance
            if (unit.currentMovement < 0.1) unit.currentMovement = 0f // silly floats which are "almost zero"
        }
        if (unit.isFortified() || unit.action == Constants.unitActionSetUp || unit.isSleeping())
            unit.action = null // unfortify/setup after moving

        // If this unit is a carrier, keep record of its air payload whereabouts.
        val origin = unit.getTile()
        unit.removeFromTile()
        unit.putInTile(lastReachableTile)

        // The .toList() here is because we have a sequence that's running on the units in the tile,
        // then if we move one of the units we'll get a ConcurrentModificationException, se we save them all to a list
        for (payload in origin.getUnits().filter { it.isTransported && unit.canTransport(it) }.toList()) {  // bring along the payloads
            payload.removeFromTile()
            payload.putInTile(lastReachableTile)
            payload.isTransported = true // restore the flag to not leave the payload in the cit
        }

        // Unit maintenance changed
        if (unit.canGarrison()
                && (origin.isCityCenter() || lastReachableTile.isCityCenter())
                && unit.civInfo.hasUnique("Units in cities cost no Maintenance")
        ) unit.civInfo.updateStatsForNextTurn()

        // Move through all intermediate tiles to get ancient ruins, barb encampments
        // and to view tiles along the way
        // We only activate the moveThroughTile AFTER the putInTile because of a really weird bug -
        // If you're going to (or past) a ruin, and you activate the ruin bonus, and A UNIT spawns.
        // That unit could now be blocking your entrance to the destination, so the putInTile would fail! =0
        // Instead, we move you to the destination directly, and only afterwards activate the various tiles on the way.
        for (tile in pathToLastReachableTile) {
            unit.moveThroughTile(tile)
        }

    }

    /**
     * Swaps this unit with the unit on the given tile
     * Precondition: this unit can swap-move to the given tile, as determined by canUnitSwapTo
     */
    fun swapMoveToTile(destination: TileInfo) {
        val otherUnit = (
            if (unit.type.isCivilian())
                destination.civilianUnit
            else
                destination.militaryUnit
        )!! // The precondition guarantees that there is an eligible same-type unit at the destination

        val ourOldPosition = unit.getTile()
        val theirOldPosition = otherUnit.getTile()

        // Swap the units
        otherUnit.removeFromTile()
        unit.movement.moveToTile(destination)
        unit.removeFromTile()
        otherUnit.putInTile(theirOldPosition)
        otherUnit.movement.moveToTile(ourOldPosition)
        unit.putInTile(theirOldPosition)
    }

    /**
     * Designates whether we can enter the tile - without attacking
     * DOES NOT designate whether we can reach that tile in the current turn
     */
    fun canMoveTo(tile: TileInfo): Boolean {
        if (unit.type.isAirUnit() || unit.type.isMissile())
            return canAirUnitMoveTo(tile, unit)

        if (!canPassThrough(tile))
            return false

        if (tile.isCityCenter() && tile.getOwner() != unit.civInfo) return false // even if they'll let us pass through, we can't enter their city

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
    
    // Can a paratrooper land at this tile?
    fun canParadropOn(destination: TileInfo): Boolean {
        // Can only move to land tiles within range that are visible and not impassible
        // Based on some testing done in the base game
        if (!destination.isLand || destination.isImpassible() || !unit.civInfo.viewableTiles.contains(destination)) return false
        return true
    }

    // This is the most called function in the entire game,
    // so multiple callees of this function have been optimized,
    // because optimization on this function results in massive benefits!
    fun canPassThrough(tile: TileInfo): Boolean {
        if (tile.isImpassible()) {
            // special exception - ice tiles are technically impassible, but some units can move through them anyway
            // helicopters can pass through impassable tiles like mountains
            if (!(tile.terrainFeatures.contains(Constants.ice) && unit.canEnterIceTiles) && !unit.canPassThroughImpassableTiles)
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
        if (tile.isOcean && !unit.civInfo.tech.wayfinding) { // Apparently all Polynesian naval units can enter oceans
            if (unit.cannotEnterOceanTiles) return false
            if (unit.cannotEnterOceanTilesUntilAstronomy
                    && !unit.civInfo.tech.isResearched("Astronomy"))
                return false
        }
        if (tile.naturalWonder != null) return false

        if (!tile.canCivEnter(unit.civInfo)) return false

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