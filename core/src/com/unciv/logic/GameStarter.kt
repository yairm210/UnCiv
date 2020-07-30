package com.unciv.logic

import com.badlogic.gdx.maps.Map
import com.badlogic.gdx.math.Vector2
import com.unciv.Constants
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.map.*
import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.models.metadata.GameParameters
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.ui.newgamescreen.GameSetupInfo
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.max

object GameStarter {

    fun startNewGame(gameSetupInfo: GameSetupInfo): GameInfo {
        val gameInfo = GameInfo()

        gameInfo.gameParameters = gameSetupInfo.gameParameters
        val ruleset = RulesetCache.getComplexRuleset(gameInfo.gameParameters)

        addTileMap(gameSetupInfo.mapParameters, gameInfo, ruleset)

        gameInfo.tileMap.gameInfo = gameInfo // need to set this transient before placing units in the map
        addCivilizations(gameSetupInfo.gameParameters, gameInfo, ruleset) // this is before gameInfo.setTransients, so gameInfo doesn't yet have the gameBasics

        for(tile in gameInfo.tileMap.values)
            for(unit in tile.getUnits())
                if(gameInfo.civilizations.none { it.civName== unit.owner}){
                    unit.currentTile=tile
                    unit.removeFromTile()
                }

        gameInfo.tileMap.setTransients(ruleset) // if we're starting from a map with preplaced units, they need the civs to exist first

        gameInfo.difficulty = gameSetupInfo.gameParameters.difficulty


        gameInfo.setTransients() // needs to be before placeBarbarianUnit because it depends on the tilemap having its gameinfo set

        // Add Civ Technologies
        for (civInfo in gameInfo.civilizations.filter { !it.isBarbarian() }) {

            if (!civInfo.isPlayerCivilization())
                for (tech in gameInfo.getDifficulty().aiFreeTechs)
                    civInfo.tech.addTechnology(tech)

            // add all techs to spectators
            if (civInfo.isSpectator())
                for (tech in ruleset.technologies.values)
                    if (!civInfo.tech.isResearched(tech.name))
                        civInfo.tech.addTechnology(tech.name)

            for (tech in ruleset.technologies.values
                    .filter { ruleset.getEraNumber(it.era()) < ruleset.getEraNumber(gameSetupInfo.gameParameters.startingEra) })
                if (!civInfo.tech.isResearched(tech.name))
                    civInfo.tech.addTechnology(tech.name)

            civInfo.popupAlerts.clear() // Since adding technologies generates popups...
        }

        // and only now do we add units for everyone, because otherwise both the gameInfo.setTransients() and the placeUnit will both add the unit to the civ's unit list!
        addCivStartingUnits(gameInfo)

        return gameInfo
    }

    private fun addTileMap(mapParameters: MapParameters, gameInfo: GameInfo, ruleset: Ruleset) {
        when (mapParameters.type) {
            MapType.scenario -> gameInfo.tileMap = MapSaver.loadScenario(mapParameters.name).tileMap
            MapType.custom -> gameInfo.tileMap = MapSaver.loadMap(mapParameters.name)
            else -> gameInfo.tileMap = MapGenerator(ruleset).generateMap(mapParameters)
        }
        gameInfo.tileMap.mapParameters = mapParameters
    }

    private fun addCivilizations(newGameParameters: GameParameters, gameInfo: GameInfo, ruleset: Ruleset) {
        val availableCivNames = Stack<String>()
        // CityState or Spectator civs are not available for Random pick
        availableCivNames.addAll(ruleset.nations.filter { it.value.isMajorCiv() }.keys.shuffled())
        availableCivNames.removeAll(newGameParameters.players.map { it.chosenCiv })
        availableCivNames.remove(Constants.barbarians)

        if(!newGameParameters.noBarbarians && ruleset.nations.containsKey(Constants.barbarians)) {
            val barbarianCivilization = CivilizationInfo(Constants.barbarians)
            gameInfo.civilizations.add(barbarianCivilization)
        }

        for (player in newGameParameters.players.sortedBy { it.chosenCiv == "Random" }) {
            val nationName = if (player.chosenCiv != "Random") player.chosenCiv
            else availableCivNames.pop()

            val playerCiv = CivilizationInfo(nationName)
            for (tech in ruleset.technologies.values.filter { it.uniques.contains("Starting tech") })
                playerCiv.tech.techsResearched.add(tech.name) // can't be .addTechnology because the civInfo isn't assigned yet
            playerCiv.playerType = player.playerType
            playerCiv.playerId = player.playerId
            gameInfo.civilizations.add(playerCiv)
        }

        val cityStatesWithStartingLocations =
                gameInfo.tileMap.values
                        .filter { it.improvement != null && it.improvement!!.startsWith("StartingLocation ") }
                        .map { it.improvement!!.replace("StartingLocation ", "") }

        val availableCityStatesNames = Stack<String>()
        // since we shuffle and then order by, we end up with all the City-States with starting tiles first in a random order,
        //   and then all the other City-States in a random order! Because the sortedBy function is stable!
        availableCityStatesNames.addAll(ruleset.nations.filter { it.value.isCityState() }.keys
                .shuffled().sortedByDescending { it in cityStatesWithStartingLocations })

        for (cityStateName in availableCityStatesNames.take(newGameParameters.numberOfCityStates)) {
            val civ = CivilizationInfo(cityStateName)
            gameInfo.civilizations.add(civ)
            for(tech in ruleset.technologies.values.filter { it.uniques.contains("Starting tech") })
                civ.tech.techsResearched.add(tech.name) // can't be .addTechnology because the civInfo isn't assigned yet
        }
    }

    private fun addCivStartingUnits(gameInfo: GameInfo) {

        val startingLocations = getStartingLocations(
                gameInfo.civilizations.filter { !it.isBarbarian() },
                gameInfo.tileMap)

        // remove starting locations once we're done
        for (tile in gameInfo.tileMap.values) {
            if (tile.improvement != null && tile.improvement!!.startsWith("StartingLocation "))
                tile.improvement = null
            // set max starting movement for units loaded from map
            for(unit in tile.getUnits()) unit.currentMovement = unit.getMaxMovement().toFloat()
        }


        // For later starting eras, or for civs like Polynesia with a different Warrior, we need different starting units
        fun getWarriorEquivalent(civ: CivilizationInfo): String {
            val availableMilitaryUnits = gameInfo.ruleSet.units.values.filter {
                it.isBuildable(civ)
                        && it.unitType.isLandUnit()
                        && !it.unitType.isCivilian()
            }
            return availableMilitaryUnits.maxBy { max(it.strength, it.rangedStrength) }!!.name
        }
        // no starting units for Barbarians and Spectators
        for (civ in gameInfo.civilizations.filter { !it.isBarbarian() && !it.isSpectator() }) {
            val startingLocation = startingLocations[civ]!!
            for (tile in startingLocation.getTilesInDistance(3))
                tile.improvement = null // Remove ancient ruins in immediate vicinity

            fun placeNearStartingPosition(unitName: String) {
                civ.placeUnitNearTile(startingLocation.position, unitName)
            }
            placeNearStartingPosition(Constants.settler)
            placeNearStartingPosition(getWarriorEquivalent(civ))

            if (!civ.isPlayerCivilization() && civ.isMajorCiv()) {
                for (unit in gameInfo.getDifficulty().aiFreeUnits) {
                    val unitToAdd = if (unit == "Warrior") getWarriorEquivalent(civ) else unit
                    placeNearStartingPosition(unitToAdd)
                }
            }
        }
    }

    private fun getStartingLocations(civs: List<CivilizationInfo>, tileMap: TileMap): HashMap<CivilizationInfo, TileInfo> {
        var landTiles = tileMap.values
                // Games starting on snow might as well start over...
                .filter { it.isLand && !it.isImpassible() && it.baseTerrain!=Constants.snow }

        val landTilesInBigEnoughGroup = ArrayList<TileInfo>()
        while (landTiles.any()) {
            val bfs = BFS(landTiles.random()) { it.isLand && !it.isImpassible() }
            bfs.stepToEnd()
            val tilesInGroup = bfs.tilesReached.keys
            landTiles = landTiles.filter { it !in tilesInGroup }
            if (tilesInGroup.size > 20) // is this a good number? I dunno, but it's easy enough to change later on
                landTilesInBigEnoughGroup.addAll(tilesInGroup)
        }

        val tilesWithStartingLocations = tileMap.values
                .filter { it.improvement != null && it.improvement!!.startsWith("StartingLocation ") }

        val civsOrderedByAvailableLocations = civs.sortedBy { civ ->
            when {
                tilesWithStartingLocations.any { it.improvement == "StartingLocation " + civ.civName } -> 1 // harshest requirements
                civ.nation.startBias.isNotEmpty() -> 2 // less harsh
                else -> 3
            }  // no requirements
        }

        for (minimumDistanceBetweenStartingLocations in tileMap.tileMatrix.size / 3 downTo 0) {
            val freeTiles = landTilesInBigEnoughGroup
                    .filter { vectorIsAtLeastNTilesAwayFromEdge(it.position, minimumDistanceBetweenStartingLocations, tileMap) }
                    .toMutableList()

            val startingLocations = HashMap<CivilizationInfo, TileInfo>()

            for (civ in civsOrderedByAvailableLocations) {
                var startingLocation: TileInfo
                val presetStartingLocation = tilesWithStartingLocations.firstOrNull { it.improvement == "StartingLocation " + civ.civName }
                if (presetStartingLocation != null) startingLocation = presetStartingLocation
                else {
                    if (freeTiles.isEmpty()) break // we failed to get all the starting tiles with this minimum distance
                    var preferredTiles = freeTiles.toList()

                    for (startBias in civ.nation.startBias) {
                        if (startBias.startsWith("Avoid ")) {
                            val tileToAvoid = startBias.removePrefix("Avoid [").removeSuffix("]")
                            preferredTiles = preferredTiles.filter { it.baseTerrain != tileToAvoid && it.terrainFeature != tileToAvoid }
                        } else if (startBias == Constants.coast) preferredTiles = preferredTiles.filter { it.isCoastalTile() }
                        else preferredTiles = preferredTiles.filter { it.baseTerrain == startBias || it.terrainFeature == startBias }
                    }

                    startingLocation = if (preferredTiles.isNotEmpty()) preferredTiles.random() else freeTiles.random()
                }
                startingLocations[civ] = startingLocation
                freeTiles.removeAll(tileMap.getTilesInDistance(startingLocation.position, minimumDistanceBetweenStartingLocations))
            }
            if (startingLocations.size < civs.size) continue // let's try again with less minimum distance!

            return startingLocations
        }
        throw Exception("Didn't manage to get starting tiles even with distance of 1?")
    }

    private fun vectorIsAtLeastNTilesAwayFromEdge(vector: Vector2, n: Int, tileMap: TileMap): Boolean {
        // Since all maps are HEXAGONAL, the easiest way of checking if a tile is n steps away from the
        // edge is checking the distance to the CENTER POINT
        // Can't believe we used a dumb way of calculating this before!
        val hexagonalRadius = -tileMap.leftX
        val distanceFromCenter = HexMath.getDistance(vector, Vector2.Zero)
        return hexagonalRadius - distanceFromCenter >= n
    }
}