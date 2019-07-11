package com.unciv.logic.automation

import com.unciv.Constants
import com.unciv.UnCivGame
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.civilization.GreatPersonManager
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.map.MapUnit
import com.unciv.logic.map.TileInfo
import com.unciv.models.stats.Stats
import com.unciv.ui.worldscreen.unit.UnitActions

class SpecificUnitAutomation{

    private fun hasWorkableSeaResource(tileInfo: TileInfo, civInfo: CivilizationInfo): Boolean {
        return tileInfo.hasViewableResource(civInfo) && tileInfo.isWater && tileInfo.improvement==null
    }

    fun automateWorkBoats(unit: MapUnit) {
        val seaResourcesInCities = unit.civInfo.cities.flatMap { it.getTilesInRange() }.asSequence()
                .filter { hasWorkableSeaResource(it, unit.civInfo) && (unit.canMoveTo(it) || unit.currentTile == it) }
        val closestReachableResource = seaResourcesInCities.sortedBy { it.arialDistanceTo(unit.currentTile) }
                .firstOrNull { unit.movementAlgs().canReach(it) }

        if (closestReachableResource != null) {
            unit.movementAlgs().headTowards(closestReachableResource)
            if (unit.currentMovement > 0 && unit.currentTile == closestReachableResource) {
                val createImprovementAction = UnitActions().getUnitActions(unit, UnCivGame.Current.worldScreen)
                        .firstOrNull { it.name.startsWith("Create") } // could be either fishing boats or oil well
                if (createImprovementAction != null)
                    return createImprovementAction.action() // unit is already gone, can't "Explore"
            }
        }
        else UnitAutomation().tryExplore(unit, unit.getDistanceToTiles())
    }

    fun automateGreatGeneral(unit: MapUnit){
        //try to follow nearby units. Do not garrison in city if possible
        val militaryUnitTilesInDistance = unit.getDistanceToTiles().map { it.key }
                .filter {val militant = it.militaryUnit
            militant != null && militant.civInfo == unit.civInfo
                && (it.civilianUnit == null || it.civilianUnit == unit)
                && militant.getMaxMovement() <= 2 && !it.isCityCenter()}

        if(militaryUnitTilesInDistance.isNotEmpty()) {
            val tilesSortedByAffectedTroops = militaryUnitTilesInDistance
                    .sortedByDescending { it.getTilesInDistance(2).count {
                val militaryUnit = it.militaryUnit
                militaryUnit!=null && militaryUnit.civInfo==unit.civInfo
            } }
            unit.movementAlgs().headTowards(tilesSortedByAffectedTroops.first())
            return
        }

        //if no unit to follow, take refuge in city.
        val cityToGarrison = unit.civInfo.cities.map {it.getCenterTile()}
                .sortedBy { it.arialDistanceTo(unit.currentTile) }
                .firstOrNull { it.civilianUnit == null && unit.canMoveTo(it) && unit.movementAlgs().canReach(it)}

        if (cityToGarrison != null) {
            unit.movementAlgs().headTowards(cityToGarrison)
            return
        }
    }

    fun rankTileAsCityCenter(tileInfo: TileInfo, nearbyTileRankings: Map<TileInfo, Float>): Float {
        val bestTilesFromOuterLayer = tileInfo.getTilesAtDistance(2)
                .asSequence()
                .sortedByDescending { nearbyTileRankings[it] }.take(2)
                .toList()
        val top5Tiles = tileInfo.neighbors.union(bestTilesFromOuterLayer)
                .asSequence()
                .sortedByDescending { nearbyTileRankings[it] }
                .take(5)
                .toList()
        var rank =  top5Tiles.asSequence().map { nearbyTileRankings[it]!! }.sum()
        if(tileInfo.neighbors.any{it.baseTerrain == Constants.coast}) rank += 5
        return rank
    }


    fun automateSettlerActions(unit: MapUnit) {
        if(unit.getTile().militaryUnit==null) return // Don't move until you're accompanied by a military unit

        val tilesNearCities = unit.civInfo.gameInfo.civilizations.flatMap { it.cities }
                .flatMap {
                    val distanceAwayFromCity =
                            if (unit.civInfo.knows(it.civInfo)
                                    // If the CITY OWNER knows that the UNIT OWNER agreed not to settle near them
                                    && it.civInfo.getDiplomacyManager(unit.civInfo).hasFlag(DiplomacyFlags.AgreedToNotSettleNearUs))
                                6
                            else 3
                    it.getCenterTile().getTilesInDistance(distanceAwayFromCity)
                }
                .toHashSet()

        // This is to improve performance - instead of ranking each tile in the area up to 19 times, do it once.
        val nearbyTileRankings = unit.getTile().getTilesInDistance(7)
                .associateBy ( {it},{ Automation().rankTile(it,unit.civInfo) })

        val possibleCityLocations = unit.getTile().getTilesInDistance(5)
                .filter { (unit.canMoveTo(it) || unit.currentTile==it) && it !in tilesNearCities && it.isLand }

        val bestCityLocation: TileInfo? = possibleCityLocations
                .asSequence()
                .sortedByDescending { rankTileAsCityCenter(it, nearbyTileRankings) }
                .firstOrNull { unit.movementAlgs().canReach(it) }

        if(bestCityLocation==null) { // We got a badass over here, all tiles within 5 are taken? Screw it, random walk.
            if(UnitAutomation().tryExplore(unit, unit.getDistanceToTiles())) return // try to find new areas
            UnitAutomation().wander(unit, unit.getDistanceToTiles()) // go around aimlessly
            return
        }

        if(bestCityLocation.getTilesInDistance(3).any { it.isCityCenter() })
            throw Exception("City within distance")

        if (unit.getTile() == bestCityLocation)
            UnitActions().getUnitActions(unit, UnCivGame.Current.worldScreen).first { it.name == "Found city" }.action()
        else {
            unit.movementAlgs().headTowards(bestCityLocation)
            if (unit.currentMovement > 0 && unit.getTile() == bestCityLocation)
                UnitActions().getUnitActions(unit, UnCivGame.Current.worldScreen).first { it.name == "Found city" }.action()
        }
    }

    fun automateGreatPerson(unit: MapUnit) {
        if(unit.getTile().militaryUnit==null) return // Don't move until you're accompanied by a military unit

        val relatedStat = GreatPersonManager().statToGreatPersonMapping.entries.first { it.value==unit.name }.key

        val citiesByStatBoost = unit.civInfo.cities.sortedByDescending{
            val stats = Stats()
            for (bonus in it.cityStats.statPercentBonusList.values) stats.add(bonus)
            stats.toHashMap()[relatedStat]!!
        }
        for(city in citiesByStatBoost){
            val pathToCity =unit.movementAlgs().getShortestPath(city.getCenterTile())
            if(pathToCity.isEmpty()) continue
            if(pathToCity.size>2){
                unit.movementAlgs().headTowards(city.getCenterTile())
                return
            }

            // if we got here, we're pretty close, start looking!
            val tiles = city.getTiles().asSequence()
                    .filter { (unit.canMoveTo(it) || unit.currentTile==it)
                            && it.isLand
                            && !it.isCityCenter()
                            && it.resource==null }
                    .sortedByDescending { Automation().rankTile(it,unit.civInfo) }.toList()
            val chosenTile = tiles.firstOrNull { unit.movementAlgs().canReach(it) }
            if(chosenTile==null) continue // to another city

            unit.movementAlgs().headTowards(chosenTile)
            if(unit.currentTile==chosenTile && unit.currentMovement>0)
                UnitActions().getUnitActions(unit, UnCivGame.Current.worldScreen)
                        .first { it.name.startsWith("Create") }.action()
            return
        }

    }

    fun automateFighter(unit: MapUnit) {
        val tilesInRange = unit.currentTile.getTilesInDistance(unit.getRange())
        val enemyAirUnitsInRange = tilesInRange
                .flatMap { it.airUnits }.filter { it.civInfo.isAtWarWith(unit.civInfo) }

        if(enemyAirUnitsInRange.isNotEmpty()) return // we need to be on standby in case they attack
        if(UnitAutomation().tryAttackNearbyEnemy(unit)) return

        val reachableCities = tilesInRange
                .filter { it.isCityCenter() && it.getOwner()==unit.civInfo && unit.canMoveTo(it)}

        for(city in reachableCities){
            if(city.getTilesInDistance(unit.getRange())
                            .any { UnitAutomation().containsAttackableEnemy(it,MapUnitCombatant(unit)) }) {
                unit.moveToTile(city)
                return
            }
        }
    }

}