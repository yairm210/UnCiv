package com.unciv.logic

import com.badlogic.gdx.math.Vector2
import com.unciv.Constants
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.map.*
import com.unciv.models.metadata.GameParameters
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.max

class GameStarter{

    fun startNewGame(newGameParameters: GameParameters, mapParameters: MapParameters): GameInfo {
        val gameInfo = GameInfo()

        gameInfo.gameParameters = newGameParameters
        val ruleset = RulesetCache.getComplexRuleset(newGameParameters.mods)

        if(mapParameters.name!="")
            gameInfo.tileMap = MapSaver().loadMap(mapParameters.name)
        else gameInfo.tileMap = MapGenerator().generateMap(mapParameters, ruleset)
        gameInfo.tileMap.mapParameters = mapParameters

        gameInfo.tileMap.setTransients(ruleset)

        gameInfo.tileMap.gameInfo = gameInfo // need to set this transient before placing units in the map
        gameInfo.difficulty = newGameParameters.difficulty


        addCivilizations(newGameParameters, gameInfo, ruleset) // this is before the setTransients so gameInfo doesn't yet have the gameBasics

        gameInfo.setTransients() // needs to be before placeBarbarianUnit because it depends on the tilemap having its gameinfo set

        // Add Civ Technologies
        for (civInfo in gameInfo.civilizations.filter {!it.isBarbarian()}) {

            if (!civInfo.isPlayerCivilization())
                for (tech in gameInfo.getDifficulty().aiFreeTechs)
                    civInfo.tech.addTechnology(tech)

            for (tech in ruleset.technologies.values
                    .filter { it.era() < newGameParameters.startingEra })
                if (!civInfo.tech.isResearched(tech.name))
                    civInfo.tech.addTechnology(tech.name)

            civInfo.popupAlerts.clear() // Since adding technologies generates popups...
        }

        // and only now do we add units for everyone, because otherwise both the gameInfo.setTransients() and the placeUnit will both add the unit to the civ's unit list!
        addCivStartingUnits(gameInfo)

        return gameInfo
    }

    private fun addCivilizations(newGameParameters: GameParameters, gameInfo: GameInfo, ruleset: Ruleset) {
        val availableCivNames = Stack<String>()
        availableCivNames.addAll(ruleset.nations.filter { !it.value.isCityState() }.keys.shuffled())
        availableCivNames.removeAll(newGameParameters.players.map { it.chosenCiv })
        availableCivNames.remove("Barbarians")


        val barbarianCivilization = CivilizationInfo("Barbarians")
        gameInfo.civilizations.add(barbarianCivilization)

        for (player in newGameParameters.players.sortedBy { it.chosenCiv == "Random" }) {
            val nationName = if (player.chosenCiv != "Random") player.chosenCiv
            else availableCivNames.pop()

            val playerCiv = CivilizationInfo(nationName)
            playerCiv.playerType = player.playerType
            playerCiv.playerId = player.playerId
            gameInfo.civilizations.add(playerCiv)
        }


        val cityStatesWithStartingLocations =
                gameInfo.tileMap.values.filter { it.improvement != null && it.improvement!!.startsWith("StartingLocation ") }
                        .map { it.improvement!!.replace("StartingLocation ", "") }

        val availableCityStatesNames = Stack<String>()
        // since we shuffle and then order by, we end up with all the city states with starting tiles first in a random order,
        //   and then all the other city states in a random order! Because the sortedBy function is stable!
        availableCityStatesNames.addAll(ruleset.nations.filter { it.value.isCityState() }.keys
                .shuffled().sortedByDescending { it in cityStatesWithStartingLocations })

        for (cityStateName in availableCityStatesNames.take(newGameParameters.numberOfCityStates)) {
            val civ = CivilizationInfo(cityStateName)
            gameInfo.civilizations.add(civ)
        }
    }

    private fun addCivStartingUnits(gameInfo: GameInfo) {

        val startingLocations = getStartingLocations(
                gameInfo.civilizations.filter { !it.isBarbarian() },
                gameInfo.tileMap)

        // For later starting eras, or for civs like Polynesia with a different Warrior, we need different starting units
        fun getWarriorEquivalent(civ: CivilizationInfo): String {
            val availableMilitaryUnits = gameInfo.ruleSet.units.values.filter {
                it.isBuildable(civ)
                        && it.unitType.isLandUnit()
                        && !it.unitType.isCivilian()
            }
            return availableMilitaryUnits.maxBy { max(it.strength, it.rangedStrength) }!!.name
        }

        for (civ in gameInfo.civilizations.filter { !it.isBarbarian() }) {
            val startingLocation = startingLocations[civ]!!

            civ.placeUnitNearTile(startingLocation.position, Constants.settler, removeImprovement = true)
            civ.placeUnitNearTile(startingLocation.position, getWarriorEquivalent(civ), removeImprovement = true)
            if(civ.isMajorCiv()) // City-states don't get initial Scouts
                civ.placeUnitNearTile(startingLocation.position, "Scout", removeImprovement = true)

            if (!civ.isPlayerCivilization() && civ.isMajorCiv()) {
                for (unit in gameInfo.getDifficulty().aiFreeUnits) {
                    val unitToAdd = if (unit == "Warrior") getWarriorEquivalent(civ) else unit
                    civ.placeUnitNearTile(startingLocation.position, unitToAdd, removeImprovement = true)
                }
            }
        }
    }

    private fun getStartingLocations(civs:List<CivilizationInfo>, tileMap: TileMap): HashMap<CivilizationInfo, TileInfo> {
        var landTiles = tileMap.values
                .filter { it.isLand && !it.getBaseTerrain().impassable }

        val landTilesInBigEnoughGroup = ArrayList<TileInfo>()
        while(landTiles.any()){
            val bfs = BFS(landTiles.random()){it.isLand && !it.getBaseTerrain().impassable}
            bfs.stepToEnd()
            val tilesInGroup = bfs.tilesReached.keys
            landTiles = landTiles.filter { it !in tilesInGroup }
            if(tilesInGroup.size > 20) // is this a good number? I dunno, but it's easy enough to change later on
                landTilesInBigEnoughGroup.addAll(tilesInGroup)
        }

        val tilesWithStartingLocations = tileMap.values
                .filter { it.improvement!=null && it.improvement!!.startsWith("StartingLocation ") }

        val civsOrderedByAvailableLocations = civs.sortedBy {civ ->
            when {
                tilesWithStartingLocations.any { it.improvement=="StartingLocation "+civ.civName } -> 1 // harshest requirements
                civ.nation.startBias.isNotEmpty() -> 2 // less harsh
                else -> 3
            }  // no requirements
        }

        for(minimumDistanceBetweenStartingLocations in tileMap.tileMatrix.size/3 downTo 0){
            val freeTiles = landTilesInBigEnoughGroup
                    .filter {  vectorIsAtLeastNTilesAwayFromEdge(it.position,minimumDistanceBetweenStartingLocations,tileMap)}
                    .toMutableList()

            val startingLocations = HashMap<CivilizationInfo,TileInfo>()

            for(civ in civsOrderedByAvailableLocations){
                var startingLocation:TileInfo
                val presetStartingLocation = tilesWithStartingLocations.firstOrNull { it.improvement=="StartingLocation "+civ.civName }
                if(presetStartingLocation!=null) startingLocation = presetStartingLocation
                else {
                    if (freeTiles.isEmpty()) break // we failed to get all the starting tiles with this minimum distance
                    var preferredTiles = freeTiles.toList()

                    for (startBias in civ.nation.startBias) {
                        if (startBias.startsWith("Avoid ")) {
                            val tileToAvoid = startBias.removePrefix("Avoid ")
                            preferredTiles = preferredTiles.filter { it.baseTerrain != tileToAvoid && it.terrainFeature != tileToAvoid }
                        } else if (startBias == Constants.coast) preferredTiles = preferredTiles.filter { it.isCoastalTile() }
                        else preferredTiles = preferredTiles.filter { it.baseTerrain == startBias || it.terrainFeature == startBias }
                    }

                    startingLocation = if (preferredTiles.isNotEmpty()) preferredTiles.random() else freeTiles.random()
                }
                startingLocations[civ] = startingLocation
                freeTiles.removeAll(tileMap.getTilesInDistance(startingLocation.position,minimumDistanceBetweenStartingLocations))
            }
            if(startingLocations.size < civs.size) continue // let's try again with less minimum distance!

            return startingLocations
        }
        throw Exception("Didn't manage to get starting tiles even with distance of 1?")
    }

    private fun vectorIsAtLeastNTilesAwayFromEdge(vector: Vector2, n:Int, tileMap: TileMap): Boolean {
        // Since all maps are HEXAGONAL, the easiest way of checking if a tile is n steps away from the
        // edge is checking the distance to the CENTER POINT
        // Can't believe we used a dumb way of calculating this before!
        val hexagonalRadius = -tileMap.leftX
        val distanceFromCenter = HexMath.getDistance(vector, Vector2.Zero)
        return hexagonalRadius-distanceFromCenter >= n
    }
}