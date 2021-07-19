package com.unciv.logic

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.automation.NextTurnAutomation
import com.unciv.logic.city.CityConstructions
import com.unciv.logic.city.PerpetualConstruction
import com.unciv.logic.civilization.*
import com.unciv.logic.map.TileInfo
import com.unciv.logic.map.TileMap
import com.unciv.models.Religion
import com.unciv.models.metadata.GameParameters
import com.unciv.models.ruleset.Difficulty
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import java.util.*

class UncivShowableException(missingMods: String) : Exception(missingMods)

class GameInfo {
    @Transient
    lateinit var difficultyObject: Difficulty // Since this is static game-wide, and was taking a large part of nextTurn

    @Transient
    lateinit var currentPlayerCiv: CivilizationInfo // this is called thousands of times, no reason to search for it with a find{} every time

    /** This is used in multiplayer games, where I may have a saved game state on my phone
     * that is inconsistent with the saved game on the cloud */
    @Transient
    var isUpToDate = false

    @Transient
    lateinit var ruleSet: Ruleset

    var civilizations = mutableListOf<CivilizationInfo>()
    var religions: HashMap<String, Religion> = hashMapOf()
    var difficulty = "Chieftain" // difficulty is game-wide, think what would happen if 2 human players could play on different difficulties?
    var tileMap: TileMap = TileMap()
    var gameParameters = GameParameters()
    var turns = 0
    var oneMoreTurnMode = false
    var currentPlayer = ""
    var gameId = UUID.randomUUID().toString() // random string

    /**Keep track of a custom location this game was saved to _or_ loaded from
     *
     * Note this was used as silent autosave destination, but it was decided (#3898) to
     * make the custom location feature a one-shot import/export kind of operation.
     * The tracking is left in place, however [GameSaver.autoSaveSingleThreaded] no longer uses it
     */
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
        toReturn.religions.putAll(religions.map { Pair(it.key, it.value.clone()) })
        toReturn.currentPlayer = currentPlayer
        toReturn.turns = turns
        toReturn.difficulty = difficulty
        toReturn.gameParameters = gameParameters
        toReturn.gameId = gameId
        toReturn.oneMoreTurnMode = oneMoreTurnMode
        toReturn.customSaveLocation = customSaveLocation
        return toReturn
    }

    fun getPlayerToViewAs(): CivilizationInfo {
        if (!gameParameters.isOnlineMultiplayer) return currentPlayerCiv // non-online, play as human player
        val userId = UncivGame.Current.settings.userId
        if (civilizations.any { it.playerId == userId }) return civilizations.first { it.playerId == userId }
        else return getBarbarianCivilization()// you aren't anyone. How did you even get this game? Can you spectate?
    }

    fun getCivilization(civName: String) = civilizations.first { it.civName == civName }
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


        fun switchTurn() {
            thisPlayer.endTurn()
            currentPlayerIndex = (currentPlayerIndex + 1) % civilizations.size
            if (currentPlayerIndex == 0) {
                turns++
            }
            thisPlayer = civilizations[currentPlayerIndex]
            thisPlayer.startTurn()
        }

        //check is important or else switchTurn
        //would skip a turn if an AI civ calls nextTurn
        //this happens when resigning a multiplayer game
        if (thisPlayer.isPlayerCivilization()){
            switchTurn()
        }

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
                thisPlayer.addNotification("An enemy [$unitName] was spotted $inOrNear our territory", tile.position, NotificationIcon.War, unitName)
            }
        } else {
            val positions = tiles.map { it.position }
            thisPlayer.addNotification("[${positions.size}] enemy units were spotted $inOrNear our territory", LocationAction(positions), NotificationIcon.War)
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
            it.isLand && it.terrainFeatures.isEmpty()
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
                .filter { !it.unitType.isCivilian() }
                .filter { it.isBuildable(barbarianCiv) }

        val landUnits = unitList.filter { it.unitType.isLandUnit() }
        val waterUnits = unitList.filter { it.unitType.isWaterUnit() }

        val unit: String
        if (waterUnits.isNotEmpty() && tileToPlace.isCoastalTile() && Random().nextBoolean())
            unit = waterUnits.random().name
        else unit = landUnits.random().name

        tileMap.placeUnitNearTile(tileToPlace.position, unit, getBarbarianCivilization())
    }

    /**
     * [CivilizationInfo.addNotification][Add a notification] to every civilization that have
     * adopted Honor policy and have explored the [tile] where the Barbarian Encampent has spawned.
     */
    fun notifyCivsOfBarbarianEncampment(tile: TileInfo) {
        civilizations.filter {
            it.hasUnique("Notified of new Barbarian encampments")
                    && it.exploredTiles.contains(tile.position)
        }
                .forEach { it.addNotification("A new barbarian encampment has spawned!", tile.position, NotificationIcon.War) }
    }

    // All cross-game data which needs to be altered (e.g. when removing or changing a name of a building/tech)
    // will be done here, and not in CivInfo.setTransients or CityInfo
    fun setTransients() {
        tileMap.gameInfo = this
        ruleSet = RulesetCache.getComplexRuleset(gameParameters.mods)
        // any mod the saved game lists that is currently not installed causes null pointer
        // exceptions in this routine unless it contained no new objects or was very simple.
        // Player's fault, so better complain early:
        val missingMods = gameParameters.mods
                .filterNot { it in ruleSet.mods }
                .joinToString(limit = 120) { it }
        if (missingMods.isNotEmpty()) {
            throw UncivShowableException("Missing mods: [$missingMods]")
        }

        // compatibility code translating changed tech name "Railroad" - to be deprecated soon
        val (oldTechName, newTechName) = "Railroad" to "Railroads"
        if (ruleSet.technologies[oldTechName] == null && ruleSet.technologies[newTechName] != null) {
            civilizations.forEach {
                it.tech.replaceUpdatedTechName(oldTechName, newTechName)
            }
        }

        removeMissingModReferences()

        tileMap.setTransients(ruleSet)

        if (currentPlayer == "") currentPlayer = civilizations.first { it.isPlayerCivilization() }.civName
        currentPlayerCiv = getCivilization(currentPlayer)

        // this is separated into 2 loops because when we activate updateVisibleTiles in civ.setTransients,
        //  we try to find new civs, and we check if civ is barbarian, which we can't know unless the gameInfo is already set.
        for (civInfo in civilizations) civInfo.gameInfo = this

        difficultyObject = ruleSet.difficulties[difficulty]!!
        
        for (religion in religions.values) religion.setTransients(this)

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
                /** We remove constructions from the queue that aren't defined in the ruleset.
                 * This can lead to situations where the city is puppeted and had its construction removed, and there's no way to user-set it
                 * So for cities like those, we'll auto-set the construction
                 */
                if (cityInfo.isPuppet && cityInfo.cityConstructions.constructionQueue.isEmpty())
                    cityInfo.cityConstructions.chooseNextConstruction()

                cityInfo.cityStats.update()
            }
        }
    }


    // Mods can change, leading to things on the map that are no longer defined in the mod.
    // So we remove them so the game doesn't crash when it tries to access them.
    private fun removeMissingModReferences() {
        for (tile in tileMap.values) {
            for (terrainFeature in tile.terrainFeatures.filter{ !ruleSet.terrains.containsKey(it) })
                tile.terrainFeatures.remove(terrainFeature)
            if (tile.resource != null && !ruleSet.tileResources.containsKey(tile.resource!!))
                tile.resource = null
            if (tile.improvement != null && !ruleSet.tileImprovements.containsKey(tile.improvement!!)
                    && !tile.improvement!!.startsWith("StartingLocation ")) // To not remove the starting locations in GameStarter.startNewGame()
                tile.improvement = null

            for (unit in tile.getUnits()) {
                if (!ruleSet.units.containsKey(unit.name)) tile.removeUnit(unit)

                for (promotion in unit.promotions.promotions.toList())
                    if (!ruleSet.unitPromotions.containsKey(promotion))
                        unit.promotions.promotions.remove(promotion)
            }
        }

        for (city in civilizations.asSequence().flatMap { it.cities.asSequence() }) {
            // Temple was replaced by Amphitheater in 3.15.6. For backwards compatibility, we
            // replace existing temples with amphitheaters. This replacement should be removed
            // when temples are reintroduced as faith buildings.
            changeBuildingNameIfNotInRuleset(city.cityConstructions, "Temple", "Amphitheater")


            for (building in city.cityConstructions.builtBuildings.toHashSet())
                if (!ruleSet.buildings.containsKey(building))
                    city.cityConstructions.builtBuildings.remove(building)

            fun isInvalidConstruction(construction: String) = !ruleSet.buildings.containsKey(construction) && !ruleSet.units.containsKey(construction)
                    && !PerpetualConstruction.perpetualConstructionsMap.containsKey(construction)

            // Remove invalid buildings or units from the queue - don't just check buildings and units because it might be a special construction as well
            for (construction in city.cityConstructions.constructionQueue.toList()) {
                if (isInvalidConstruction(construction))
                    city.cityConstructions.constructionQueue.remove(construction)
            }
            // And from being in progress
            for (construction in city.cityConstructions.inProgressConstructions.keys.toList())
                if (isInvalidConstruction(construction))
                    city.cityConstructions.inProgressConstructions.remove(construction)
        }

        for (civinfo in civilizations) {
            for (tech in civinfo.tech.techsResearched.toList())
                if (!ruleSet.technologies.containsKey(tech))
                    civinfo.tech.techsResearched.remove(tech)
            for (policy in civinfo.policies.adoptedPolicies.toList())
                // So these two policies are deprecated since 3.14.17
                // However, we still need to convert save files that have those to valid save files
                // The easiest way to do this, is just to allow them here, and filter them out in
                // the policyManager class.
                // Yes, this is ugly, but it should be temporary, and it works.
                if (!ruleSet.policies.containsKey(policy) && !(policy == "Entrepreneurship" || policy == "Patronage"))
                    civinfo.policies.adoptedPolicies.remove(policy)
        }
    }

    /**
     * Replaces all occurrences of [oldBuildingName] in [cityConstructions] with [newBuildingName]
     * if the former is not contained in the ruleset.
     * This function can be used for backwards compatibility with older save files when a building
     * name is changed.
     */
    private fun changeBuildingNameIfNotInRuleset(cityConstructions: CityConstructions, oldBuildingName: String, newBuildingName: String) {
        if (ruleSet.buildings.containsKey(oldBuildingName))
            return
        // Replace in built buildings
        if (cityConstructions.builtBuildings.contains(oldBuildingName)) {
            cityConstructions.builtBuildings.remove(oldBuildingName)
            cityConstructions.builtBuildings.add(newBuildingName)
        }
        // Replace in construction queue
        if (!cityConstructions.builtBuildings.contains(newBuildingName) && !cityConstructions.constructionQueue.contains(newBuildingName))
            cityConstructions.constructionQueue = cityConstructions.constructionQueue.map{ if (it == oldBuildingName) newBuildingName else it }.toMutableList()
        else
            cityConstructions.constructionQueue.remove(oldBuildingName)
        // Replace in in-progress constructions
        if (cityConstructions.inProgressConstructions.containsKey(oldBuildingName)) {
            if (!cityConstructions.builtBuildings.contains(newBuildingName) && !cityConstructions.inProgressConstructions.containsKey(newBuildingName))
                cityConstructions.inProgressConstructions[newBuildingName] = cityConstructions.inProgressConstructions[oldBuildingName]!!
            cityConstructions.inProgressConstructions.remove(oldBuildingName)
        }
    }
    
    fun hasReligionEnabled() = gameParameters.religionEnabled || ruleSet.hasReligion() // Temporary function to check whether religion should be used for this game
}

// reduced variant only for load preview
class GameInfoPreview {
    var civilizations = mutableListOf<CivilizationInfoPreview>()
    var difficulty = "Chieftain"
    var gameParameters = GameParameters()
    var turns = 0
    var gameId = ""
    var currentPlayer = ""
    fun getCivilization(civName: String) = civilizations.first { it.civName == civName }
}
