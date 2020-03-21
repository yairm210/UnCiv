package com.unciv.logic.automation

import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.civilization.GreatPersonManager
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.map.MapUnit
import com.unciv.logic.map.TileInfo
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.stats.Stats
import com.unciv.ui.worldscreen.unit.UnitActions

class SpecificUnitAutomation {

    private val battleHelper = BattleHelper()

    private fun hasWorkableSeaResource(tileInfo: TileInfo, civInfo: CivilizationInfo): Boolean =
            tileInfo.isWater && tileInfo.improvement == null && tileInfo.hasViewableResource(civInfo)

    fun automateWorkBoats(unit: MapUnit) {
        val closestReachableResource = unit.civInfo.cities.asSequence()
                .flatMap { city -> city.getWorkableTiles() }
                .filter { hasWorkableSeaResource(it, unit.civInfo)
                        && (unit.currentTile == it || unit.movement.canMoveTo(it)) }
                .sortedBy { it.aerialDistanceTo(unit.currentTile) }
                .firstOrNull { unit.movement.canReach(it) }

        when (closestReachableResource) {
            null -> UnitAutomation.tryExplore(unit, unit.movement.getDistanceToTiles())
            else -> {
                unit.movement.headTowards(closestReachableResource)

                // could be either fishing boats or oil well
                val improvement = closestReachableResource.getTileResource().improvement
                if (unit.currentTile == closestReachableResource && improvement != null)
                    UnitActions.getWaterImprovementAction(unit)?.action?.invoke()
            }
        }
    }

    fun automateGreatGeneral(unit: MapUnit) {
        //try to follow nearby units. Do not garrison in city if possible
        val militaryUnitTilesInDistance = unit.movement.getDistanceToTiles().asSequence()
                .filter {
                    val militant = it.key.militaryUnit
                    militant != null && militant.civInfo == unit.civInfo
                            && (it.key.civilianUnit == null || it.key.civilianUnit == unit)
                            && militant.getMaxMovement() <= 2 && !it.key.isCityCenter()
                }

        val maxAffectedTroopsTile = militaryUnitTilesInDistance
                .maxBy {
                        it.key.getTilesInDistance(2).count {tile ->
                            val militaryUnit = tile.militaryUnit
                            militaryUnit != null && militaryUnit.civInfo == unit.civInfo
                        }
                    }?.key
        if (maxAffectedTroopsTile != null) {
            unit.movement.headTowards(maxAffectedTroopsTile)
            return
        }

        //if no unit to follow, take refuge in city.
        val cityToGarrison = unit.civInfo.cities.asSequence().map { it.getCenterTile() }
                .sortedBy { it.aerialDistanceTo(unit.currentTile) }
                .firstOrNull {
                    it.civilianUnit == null && unit.movement.canMoveTo(it)
                            && unit.movement.canReach(it)
                }

        if (cityToGarrison != null) {
            unit.movement.headTowards(cityToGarrison)
            return
        }
    }

    private fun rankTileAsCityCenter(tileInfo: TileInfo, nearbyTileRankings: Map<TileInfo, Float>,
                                     luxuryResourcesInCivArea: Sequence<TileResource>): Float {
        val bestTilesFromOuterLayer = tileInfo.getTilesAtDistance(2)
                .sortedByDescending { nearbyTileRankings[it] }.take(2)
                .toList()
        val top5Tiles = tileInfo.neighbors.union(bestTilesFromOuterLayer)
                .asSequence()
                .sortedByDescending { nearbyTileRankings[it] }
                .take(5)
        var rank = top5Tiles.map { nearbyTileRankings.getValue(it) }.sum()
        if (tileInfo.isCoastalTile()) rank += 5

        val luxuryResourcesInCityArea = tileInfo.getTilesAtDistance(2).filter { it.resource != null }
                .map { it.getTileResource() }.filter { it.resourceType == ResourceType.Luxury }.distinct()
        val luxuryResourcesAlreadyInCivArea = luxuryResourcesInCivArea.map { it.name }.toHashSet()
        val luxuryResourcesNotYetInCiv = luxuryResourcesInCityArea
                .count { it.name !in luxuryResourcesAlreadyInCivArea }
        rank += luxuryResourcesNotYetInCiv * 10

        return rank
    }

    fun automateSettlerActions(unit: MapUnit) {
        if (unit.getTile().militaryUnit == null) return // Don't move until you're accompanied by a military unit

        val tilesNearCities = unit.civInfo.gameInfo.getCities().asSequence()
                .flatMap {
                    val distanceAwayFromCity =
                            if (unit.civInfo.knows(it.civInfo)
                                    // If the CITY OWNER knows that the UNIT OWNER agreed not to settle near them
                                    && it.civInfo.getDiplomacyManager(unit.civInfo).hasFlag(DiplomacyFlags.AgreedToNotSettleNearUs))
                                6
                            else 3
                    it.getCenterTile().getTilesInDistance(distanceAwayFromCity)
                }
                .toSet()

        // This is to improve performance - instead of ranking each tile in the area up to 19 times, do it once.
        val nearbyTileRankings = unit.getTile().getTilesInDistance(7)
                .associateBy({ it }, { Automation.rankTile(it, unit.civInfo) })

        val possibleCityLocations = unit.getTile().getTilesInDistance(5)
                .filter {
                    val tileOwner = it.getOwner()
                    it.isLand && (tileOwner == null || tileOwner == unit.civInfo) // don't allow settler to settle inside other civ's territory
                            && (unit.currentTile == it || unit.movement.canMoveTo(it))
                            && it !in tilesNearCities
                }

        val luxuryResourcesInCivArea = unit.civInfo.cities.asSequence()
                .flatMap { it.getTiles().asSequence() }.filter { it.resource != null }
                .map { it.getTileResource() }.filter { it.resourceType == ResourceType.Luxury }
                .distinct()
        val bestCityLocation: TileInfo? = possibleCityLocations
                .sortedByDescending { rankTileAsCityCenter(it, nearbyTileRankings, luxuryResourcesInCivArea) }
                .firstOrNull { unit.movement.canReach(it) }

        if (bestCityLocation == null) { // We got a badass over here, all tiles within 5 are taken? Screw it, random walk.
            val unitDistanceToTiles = unit.movement.getDistanceToTiles()
            if (UnitAutomation.tryExplore(unit, unitDistanceToTiles)) return // try to find new areas
            UnitAutomation.wander(unit, unitDistanceToTiles) // go around aimlessly
            return
        }

        val foundCityAction = UnitActions.getFoundCityAction(unit, bestCityLocation)
        if (foundCityAction?.action == null) { // this means either currentMove == 0 or city within 3 tiles
            if (unit.currentMovement > 0) // therefore, city within 3 tiles
                throw Exception("City within distance")
            return
        }

        unit.movement.headTowards(bestCityLocation)
        if (unit.getTile() == bestCityLocation)
            foundCityAction.action.invoke()
    }

    fun automateGreatPerson(unit: MapUnit) {
        if (unit.getTile().militaryUnit == null) return // Don't move until you're accompanied by a military unit

        val relatedStat = GreatPersonManager().statToGreatPersonMapping.entries.first { it.value == unit.name }.key

        val citiesByStatBoost = unit.civInfo.cities.sortedByDescending {
            val stats = Stats()
            for (bonus in it.cityStats.statPercentBonusList.values) stats.add(bonus)
            stats.toHashMap()[relatedStat]!!
        }
        for (city in citiesByStatBoost) {
            val pathToCity = unit.movement.getShortestPath(city.getCenterTile())

            if (pathToCity.isEmpty()) continue
            if (pathToCity.size > 2) {
                unit.movement.headTowards(city.getCenterTile())
                return
            }

            // if we got here, we're pretty close, start looking!
            val chosenTile = city.getTiles()
                    .filter {
                        it.isLand && it.resource == null && !it.isCityCenter()
                                && (unit.currentTile == it || unit.movement.canMoveTo(it))
                    }.sortedByDescending { Automation.rankTile(it, unit.civInfo) }
                    .firstOrNull { unit.movement.canReach(it) } // to another city
            if (chosenTile == null) continue

            unit.movement.headTowards(chosenTile)
            if (unit.currentTile == chosenTile)
                UnitActions.getGreatPersonBuildImprovementAction(unit)?.action?.invoke()
            return
        }
    }

    fun automateFighter(unit: MapUnit) {
        val tilesInRange = unit.currentTile.getTilesInDistance(unit.getRange())
        val enemyAirUnitsInRange = tilesInRange
                .flatMap { it.airUnits.asSequence() }.filter { it.civInfo.isAtWarWith(unit.civInfo) }

        if (enemyAirUnitsInRange.any()) return // we need to be on standby in case they attack
        if (battleHelper.tryAttackNearbyEnemy(unit)) return

        if (tryRelocateToCitiesWithEnemyNearBy(unit)) return

        val pathsToCities = unit.movement.getAerialPathsToCities()
        if (pathsToCities.isEmpty()) return // can't actually move anywhere else

        val citiesByNearbyAirUnits = pathsToCities.keys
                .groupBy { key ->
                    key.getTilesInDistance(unit.getRange()*2)
                            .count {
                                val firstAirUnit = it.airUnits.firstOrNull()
                                firstAirUnit != null && firstAirUnit.civInfo.isAtWarWith(unit.civInfo)
                            }
                }

        if (citiesByNearbyAirUnits.keys.any { it != 0 }) {
            val citiesWithMostNeedOfAirUnits = citiesByNearbyAirUnits.maxBy { it.key }!!.value
            //todo: maybe groupby size and choose highest priority within the same size turns
            val chosenCity = citiesWithMostNeedOfAirUnits.minBy { pathsToCities.getValue(it).size }!! // city with min path = least turns to get there
            val firstStepInPath = pathsToCities.getValue(chosenCity).first()
            unit.movement.moveToTile(firstStepInPath)
            return
        }

        // no city needs fighters to defend, so let's attack stuff from the closest possible location
        tryMoveToCitiesToAerialAttackFrom(pathsToCities, unit)

    }

    fun automateBomber(unit: MapUnit) {
        if (battleHelper.tryAttackNearbyEnemy(unit)) return

        if (tryRelocateToCitiesWithEnemyNearBy(unit)) return

        val pathsToCities = unit.movement.getAerialPathsToCities()
        if (pathsToCities.isEmpty()) return // can't actually move anywhere else
        tryMoveToCitiesToAerialAttackFrom(pathsToCities, unit)
    }

    private fun tryMoveToCitiesToAerialAttackFrom(pathsToCities: HashMap<TileInfo, ArrayList<TileInfo>>, airUnit: MapUnit) {
        val citiesThatCanAttackFrom = pathsToCities.keys
                .filter {
                    it != airUnit.currentTile
                            && it.getTilesInDistance(airUnit.getRange())
                            .any { battleHelper.containsAttackableEnemy(it, MapUnitCombatant(airUnit)) }
                }
        if (citiesThatCanAttackFrom.isEmpty()) return

        //todo: this logic looks similar to some parts of automateFighter, maybe pull out common code
        //todo: maybe groupby size and choose highest priority within the same size turns
        val closestCityThatCanAttackFrom = citiesThatCanAttackFrom.minBy { pathsToCities[it]!!.size }!!
        val firstStepInPath = pathsToCities[closestCityThatCanAttackFrom]!!.first()
        airUnit.movement.moveToTile(firstStepInPath)
    }

    // This really needs to be changed, to have better targetting for missiles
    fun automateMissile(unit: MapUnit) {
        if (battleHelper.tryAttackNearbyEnemy(unit)) return

        val tilesInRange = unit.currentTile.getTilesInDistance(unit.getRange()*2)

        val immediatelyReachableCities = tilesInRange
                .filter { unit.movement.canMoveTo(it) && ((it.isCityCenter() && it.getOwner() == unit.civInfo) ||
                        (it.getUnits().any { mapUnit -> mapUnit.type.isMissileCarrierUnit() && mapUnit.civInfo == unit.civInfo }))}

        for (city in immediatelyReachableCities) {
            if (city.getTilesInDistance(unit.getRange())
                            .any { battleHelper.containsAttackableEnemy(it, MapUnitCombatant(unit)) }) {
                unit.movement.moveToTile(city)
                return
            }
        }

        val pathsToCities = unit.movement.getAerialPathsToCities()
        if (pathsToCities.isEmpty()) return // can't actually move anywhere else
        tryMoveToCitiesToAerialAttackFrom(pathsToCities, unit)
    }

    private fun tryRelocateToCitiesWithEnemyNearBy(unit: MapUnit): Boolean {
        val immediatelyReachableCitiesAndCarriers = unit.currentTile.getTilesInDistance(unit.getRange()*2)
                .filter {  unit.movement.canMoveTo(it) && ((it.isCityCenter() && it.getOwner() == unit.civInfo) ||
                        (it.getUnits().any { mapUnit -> mapUnit.type.isAircraftCarrierUnit() && mapUnit.civInfo == unit.civInfo })) }

        for (city in immediatelyReachableCitiesAndCarriers) {
            if (city.getTilesInDistance(unit.getRange())
                            .any { battleHelper.containsAttackableEnemy(it, MapUnitCombatant(unit)) }) {
                unit.movement.moveToTile(city)
                return true
            }
        }
        return false
    }

}