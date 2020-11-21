package com.unciv.logic

import com.badlogic.gdx.graphics.Color
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.automation.NextTurnAutomation
import com.unciv.logic.city.CityConstructions
import com.unciv.logic.civilization.*
import com.unciv.logic.map.TileInfo
import com.unciv.logic.map.TileMap
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeType
import com.unciv.models.metadata.GameParameters
import com.unciv.models.ruleset.Difficulty
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.Specialist
import java.util.*
import kotlin.collections.ArrayList

class UncivShowableException(missingMods: String) : Exception(missingMods)

class GameInfo {
    @Transient lateinit var difficultyObject: Difficulty // Since this is static game-wide, and was taking a large part of nextTurn
    @Transient lateinit var currentPlayerCiv:CivilizationInfo // this is called thousands of times, no reason to search for it with a find{} every time
    /** This is used in multiplayer games, where I may have a saved game state on my phone
     * that is inconsistent with the saved game on the cloud */
    @Transient var isUpToDate=false
    @Transient lateinit var ruleSet:Ruleset

    var civilizations = mutableListOf<CivilizationInfo>()
    var difficulty="Chieftain" // difficulty is game-wide, think what would happen if 2 human players could play on different difficulties?
    var tileMap: TileMap = TileMap()
    var gameParameters= GameParameters()
    var turns = 0
    var oneMoreTurnMode=false
    var currentPlayer=""
    var gameId = UUID.randomUUID().toString() // random string
    @Volatile
    var customSaveLocation: String? = null

    /** Simulate until any player wins,
     *  or turns exceeds indicated number
     *  Does not update World View until finished.
     *  Should be set manually on each new game start.
     */
    var simulateMaxTurns: Int = 1000
    var simulateUntilWin = false

    //region pure functions
    fun clone(): GameInfo {
        val toReturn = GameInfo()
        toReturn.tileMap = tileMap.clone()
        toReturn.civilizations.addAll(civilizations.map { it.clone() })
        toReturn.currentPlayer=currentPlayer
        toReturn.turns = turns
        toReturn.difficulty=difficulty
        toReturn.gameParameters = gameParameters
        toReturn.gameId = gameId
        toReturn.oneMoreTurnMode = oneMoreTurnMode
        toReturn.customSaveLocation = customSaveLocation
        return toReturn
    }

    fun getPlayerToViewAs(): CivilizationInfo {
        if (!gameParameters.isOnlineMultiplayer) return currentPlayerCiv // non-online, play as human player
        val userId = UncivGame.Current.settings.userId
        if (civilizations.any { it.playerId == userId}) return civilizations.first { it.playerId == userId }
        else return getBarbarianCivilization()// you aren't anyone. How did you even get this game? Can you spectate?
    }

    fun getCivilization(civName:String) = civilizations.first { it.civName==civName }
    fun getCurrentPlayerCivilization() = currentPlayerCiv
    fun getBarbarianCivilization() = getCivilization(Constants.barbarians)
    fun getDifficulty() = difficultyObject
    fun getCities() = civilizations.flatMap { it.cities }
    fun getAliveCityStates() = civilizations.filter { it.isAlive() && it.isCityState() }
    fun getAliveMajorCivs() = civilizations.filter { it.isAlive() && it.isMajorCiv() }
    //endregion

    fun nextTurn() {
        val previousHumanPlayer = getCurrentPlayerCivilization()
        var thisPlayer = previousHumanPlayer // not calling it currentPlayer because that's already taken and I can't think of a better name
        var currentPlayerIndex = civilizations.indexOf(thisPlayer)


        fun switchTurn(){
            thisPlayer.endTurn()
            currentPlayerIndex = (currentPlayerIndex+1) % civilizations.size
            if(currentPlayerIndex==0){
                turns++
            }
            thisPlayer = civilizations[currentPlayerIndex]
            thisPlayer.startTurn()
        }

        switchTurn()

        while (thisPlayer.playerType == PlayerType.AI
            || turns < UncivGame.Current.simulateUntilTurnForDebug
                || turns < simulateMaxTurns && simulateUntilWin
                // For multiplayer, if there are 3+ players and one is defeated or spectator,
                // we'll want to skip over their turn
                || gameParameters.isOnlineMultiplayer && (thisPlayer.isDefeated() || thisPlayer.isSpectator())
        ) {
            if (!thisPlayer.isDefeated() || thisPlayer.isBarbarian()) {
                NextTurnAutomation.automateCivMoves(thisPlayer)

                // Placing barbarians after their turn
                if (thisPlayer.isBarbarian()
                        && !gameParameters.noBarbarians
                        && turns % 10 == 0) placeBarbarians()

                // exit simulation mode when player wins
                if (thisPlayer.victoryManager.hasWon() && simulateUntilWin) {
                    // stop simulation
                    simulateUntilWin = false
                    break
                }
            }
            switchTurn()
        }

        currentPlayer = thisPlayer.civName
        currentPlayerCiv = getCivilization(currentPlayer)
        if (currentPlayerCiv.isSpectator()) currentPlayerCiv.popupAlerts.clear() // no popups for spectators


        // Start our turn immediately before the player can made decisions - affects whether our units can commit automated actions and then be attacked immediately etc.
        notifyOfCloseEnemyUnits(thisPlayer)
    }

    private fun notifyOfCloseEnemyUnits(thisPlayer: CivilizationInfo) {
        val viewableInvisibleTiles = thisPlayer.viewableInvisibleUnitsTiles.map { it.position }
        val enemyUnitsCloseToTerritory = thisPlayer.viewableTiles
                .filter {
                    it.militaryUnit != null && it.militaryUnit!!.civInfo != thisPlayer
                            && thisPlayer.isAtWarWith(it.militaryUnit!!.civInfo)
                            && (it.getOwner() == thisPlayer || it.neighbors.any { neighbor -> neighbor.getOwner() == thisPlayer }
                            && (!it.militaryUnit!!.isInvisible() || viewableInvisibleTiles.contains(it.position)))
                }

        // enemy units ON our territory
        addEnemyUnitNotification(thisPlayer,
                enemyUnitsCloseToTerritory.filter { it.getOwner() == thisPlayer },
                "in"
        )
        // enemy units NEAR our territory
        addEnemyUnitNotification(thisPlayer,
                enemyUnitsCloseToTerritory.filter { it.getOwner() != thisPlayer },
                "near"
        )
    }

    private fun addEnemyUnitNotification(thisPlayer: CivilizationInfo, tiles: List<TileInfo>, inOrNear: String) {
        // don't flood the player with similar messages. instead cycle through units by clicking the message multiple times.
        if (tiles.size < 3) {
            for (tile in tiles) {
                val unitName = tile.militaryUnit!!.name
                thisPlayer.addNotification("An enemy [$unitName] was spotted $inOrNear our territory", tile.position, Color.RED)
            }
        }
        else {
            val positions = tiles.map { it.position }
            thisPlayer.addNotification("[${positions.size}] enemy units were spotted $inOrNear our territory", Color.RED, LocationAction(positions))
        }
    }


    fun placeBarbarians() {
        val encampments = tileMap.values.filter { it.improvement == Constants.barbarianEncampment }

        if (encampments.size < civilizations.filter { it.isMajorCiv() }.size) {
            val newEncampmentTile = placeBarbarianEncampment(encampments)
            if (newEncampmentTile != null)
                placeBarbarianUnit(newEncampmentTile)
        }

        val totalBarbariansAllowedOnMap = encampments.size * 3
        var extraBarbarians = totalBarbariansAllowedOnMap - getBarbarianCivilization().getCivUnits().count()

        for (tile in tileMap.values.filter { it.improvement == Constants.barbarianEncampment }) {
            if (extraBarbarians <= 0) break
            extraBarbarians--
            placeBarbarianUnit(tile)
        }
    }

    fun placeBarbarianEncampment(existingEncampments: List<TileInfo>): TileInfo? {
        // Barbarians will only spawn in places that no one can see
        val allViewableTiles = civilizations.filterNot { it.isBarbarian() || it.isSpectator() }
                .flatMap { it.viewableTiles }.toHashSet()
        val tilesWithin3ofExistingEncampment = existingEncampments.asSequence()
                .flatMap { it.getTilesInDistance(3) }.toSet()
        val viableTiles = tileMap.values.filter {
            it.isLand && it.terrainFeature == null
                    && !it.isImpassible()
                    && it !in tilesWithin3ofExistingEncampment
                    && it !in allViewableTiles
        }
        if (viableTiles.isEmpty()) return null // no place for more barbs =(
        val tile = viableTiles.random()
        tile.improvement = Constants.barbarianEncampment
        notifyCivsOfBarbarianEncampment(tile)
        return tile
    }

    fun placeBarbarianUnit(tileToPlace: TileInfo) {
        // if we don't make this into a separate list then the retain() will happen on the Tech keys,
        // which effectively removes those techs from the game and causes all sorts of problems
        val allResearchedTechs = ruleSet.technologies.keys.toMutableList()
        for (civ in civilizations.filter { !it.isBarbarian() && !it.isDefeated() }) {
            allResearchedTechs.retainAll(civ.tech.techsResearched)
        }
        val barbarianCiv = getBarbarianCivilization()
        barbarianCiv.tech.techsResearched = allResearchedTechs.toHashSet()
        val unitList = ruleSet.units.values
                .filter { !it.unitType.isCivilian()}
                .filter { it.isBuildable(barbarianCiv) }

        val landUnits = unitList.filter { it.unitType.isLandUnit() }
        val waterUnits = unitList.filter { it.unitType.isWaterUnit() }

        val unit:String
        if(waterUnits.isNotEmpty() && tileToPlace.isCoastalTile() && Random().nextBoolean())
            unit=waterUnits.random().name
        else unit = landUnits.random().name

        tileMap.placeUnitNearTile(tileToPlace.position, unit, getBarbarianCivilization())
    }

    /**
     * [CivilizationInfo.addNotification][Add a notification] to every civilization that have
     * adopted Honor policy and have explored the [tile] where the Barbarian Encampent has spawned.
     */
    fun notifyCivsOfBarbarianEncampment(tile: TileInfo) {
        civilizations.filter { it.hasUnique("Notified of new Barbarian encampments")
                && it.exploredTiles.contains(tile.position) }
                .forEach { it.addNotification("A new barbarian encampment has spawned!", tile.position, Color.RED) }
    }

    // All cross-game data which needs to be altered (e.g. when removing or changing a name of a building/tech)
    // will be done here, and not in CivInfo.setTransients or CityInfo
    fun setTransients() {
        tileMap.gameInfo = this
        ruleSet = RulesetCache.getComplexRuleset(gameParameters)
        // any mod the saved game lists that is currently not installed causes null pointer
        // exceptions in this routine unless it contained no new objects or was very simple.
        // Player's fault, so better complain early:
        val missingMods = gameParameters.mods
                .filterNot { it in ruleSet.mods }
                .joinToString(limit = 120) { it }
        if (missingMods.isNotEmpty()) {
            throw UncivShowableException("Missing mods: [$missingMods]")
        }

        // Mods can change, leading to things on the map that are no longer defined in the mod.
        // So we remove them so the game doesn't crash when it tries to access them.
        for (tile in tileMap.values) {
            if (tile.resource != null && !ruleSet.tileResources.containsKey(tile.resource!!))
                tile.resource = null
            if (tile.improvement != null && !ruleSet.tileImprovements.containsKey(tile.improvement!!))
                tile.improvement = null

            for (unit in tile.getUnits())
                for (promotion in unit.promotions.promotions.toList())
                    if (!ruleSet.unitPromotions.containsKey(promotion))
                        unit.promotions.promotions.remove(promotion)
        }

        tileMap.setTransients(ruleSet)

        if (currentPlayer == "") currentPlayer = civilizations.first { it.isPlayerCivilization() }.civName
        currentPlayerCiv = getCivilization(currentPlayer)

        // this is separated into 2 loops because when we activate updateVisibleTiles in civ.setTransients,
        //  we try to find new civs, and we check if civ is barbarian, which we can't know unless the gameInfo is already set.
        for (civInfo in civilizations) civInfo.gameInfo = this

        difficultyObject = ruleSet.difficulties[difficulty]!!

        // We have to remove all deprecated buildings from all cities BEFORE we update a single one, or run setTransients on the civs,
        // because updating leads to getting the building uniques from the civ info,
        // which in turn leads to us trying to get info on all the building in all the cities...
        // which can fail if there's an "unregistered" building anywhere
        for (civInfo in civilizations) {
            // As of 3.3.7, Facism -> Fascism
            if (civInfo.policies.adoptedPolicies.contains("Facism")) {
                civInfo.policies.adoptedPolicies.remove("Facism")
                civInfo.policies.adoptedPolicies.add("Fascism")
            }
        }


        // This doesn't HAVE to go here, but why not.

        for (civInfo in civilizations) civInfo.setNationTransient()
        for (civInfo in civilizations) civInfo.setTransients()
        for (civInfo in civilizations) civInfo.updateSightAndResources()

        for (civInfo in civilizations) {
            for (unit in civInfo.getCivUnits())
                unit.updateVisibleTiles() // this needs to be done after all the units are assigned to their civs and all other transients are set

            // Since this depends on the cities of ALL civilizations,
            // we need to wait until we've set the transients of all the cities before we can run this.
            // Hence why it's not in CivInfo.setTransients().
            civInfo.initialSetCitiesConnectedToCapitalTransients()

            // We need to determine the GLOBAL happiness state in order to determine the city stats
            for (cityInfo in civInfo.cities) cityInfo.cityStats.updateCityHappiness()

            for (cityInfo in civInfo.cities) {
                if (cityInfo.cityConstructions.currentConstruction != "") { // move it to the top of the queue
                    val constructionQueue = cityInfo.cityConstructions.constructionQueue
                    val itemsInQueue = constructionQueue.toList()
                    constructionQueue.clear()
                    constructionQueue.add(cityInfo.cityConstructions.currentConstruction)
                    constructionQueue.addAll(itemsInQueue)
                    cityInfo.cityConstructions.currentConstruction = ""
                }

                // As of 3.10.14, specialists are saved by name not by stat
                for((key, value) in cityInfo.population.specialists.toHashMap().filter { it.value>0 })
                    cityInfo.population.specialistAllocations.add(
                            Specialist.specialistNameByStat(key), value.toInt())
                cityInfo.population.specialists.clear()

                cityInfo.cityStats.update()
            }
        }
    }

    private fun changeBuildingName(cityConstructions: CityConstructions, oldBuildingName: String, newBuildingName: String) {
        if (cityConstructions.builtBuildings.contains(oldBuildingName)) {
            cityConstructions.builtBuildings.remove(oldBuildingName)
            cityConstructions.builtBuildings.add(newBuildingName)
        }
        cityConstructions.constructionQueue.replaceAll { if(it==oldBuildingName) newBuildingName else it }
        if (cityConstructions.inProgressConstructions.containsKey(oldBuildingName)) {
            cityConstructions.inProgressConstructions[newBuildingName] = cityConstructions.inProgressConstructions[oldBuildingName]!!
            cityConstructions.inProgressConstructions.remove(oldBuildingName)
        }
    }
}
