package com.unciv.logic.civilization

import com.badlogic.gdx.math.Vector2
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.UncivShowableException
import com.unciv.logic.automation.NextTurnAutomation
import com.unciv.logic.city.CityInfo
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomacyManager
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.map.MapUnit
import com.unciv.logic.map.TileInfo
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeRequest
import com.unciv.models.ruleset.*
import com.unciv.models.ruleset.tile.ResourceSupplyList
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stats
import com.unciv.models.translations.tr
import com.unciv.ui.victoryscreen.RankingType
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.math.roundToInt

class CivilizationInfo {

    @Transient
    lateinit var gameInfo: GameInfo

    @Transient
    lateinit var nation: Nation

    /**
     * We never add or remove from here directly, could cause comodification problems.
     * Instead, we create a copy list with the change, and replace this list.
     * The other solution, casting toList() every "get", has a performance cost
     */
    @Transient
    private var units = listOf<MapUnit>()

    @Transient
    var viewableTiles = setOf<TileInfo>()

    @Transient
    var viewableInvisibleUnitsTiles = setOf<TileInfo>()

    /** Contains mapping of cities to travel mediums from ALL civilizations connected by trade routes to the capital */
    @Transient
    var citiesConnectedToCapitalToMediums = mapOf<CityInfo, Set<String>>()

    /** This is for performance since every movement calculation depends on this, see MapUnit comment */
    @Transient
    var hasActiveGreatWall = false

    @Transient
    var statsForNextTurn = Stats()

    @Transient
    var happinessForNextTurn = 0

    @Transient
    var detailedCivResources = ResourceSupplyList()

    var playerType = PlayerType.AI

    /** Used in online multiplayer for human players */
    var playerId = ""
    var gold = 0
    var civName = ""
    var tech = TechManager()
    var policies = PolicyManager()
    var questManager = QuestManager()
    var religionManager = ReligionManager()
    var goldenAges = GoldenAgeManager()
    var greatPeople = GreatPersonManager()
    var victoryManager = VictoryManager()
    var diplomacy = HashMap<String, DiplomacyManager>()
    var notifications = ArrayList<Notification>()
    val popupAlerts = ArrayList<PopupAlert>()
    private var allyCivName = ""
    var naturalWonders = ArrayList<String>()

    /** for trades here, ourOffers is the current civ's offers, and theirOffers is what the requesting civ offers  */
    val tradeRequests = ArrayList<TradeRequest>()

    /** See DiplomacyManager.flagsCountdown to why not eEnum */
    private var flagsCountdown = HashMap<String, Int>()

    // if we only use lists, and change the list each time the cities are changed,
    // we won't get concurrent modification exceptions.
    // This is basically a way to ensure our lists are immutable.
    var cities = listOf<CityInfo>()
    var citiesCreated = 0
    var exploredTiles = HashSet<Vector2>()

    constructor()

    constructor(civName: String) {
        this.civName = civName
    }

    fun clone(): CivilizationInfo {
        val toReturn = CivilizationInfo()
        toReturn.gold = gold
        toReturn.playerType = playerType
        toReturn.playerId = playerId
        toReturn.civName = civName
        toReturn.tech = tech.clone()
        toReturn.policies = policies.clone()
        toReturn.religionManager = religionManager.clone()
        toReturn.questManager = questManager.clone()
        toReturn.goldenAges = goldenAges.clone()
        toReturn.greatPeople = greatPeople.clone()
        toReturn.victoryManager = victoryManager.clone()
        toReturn.allyCivName = allyCivName
        for (diplomacyManager in diplomacy.values.map { it.clone() })
            toReturn.diplomacy[diplomacyManager.otherCivName] = diplomacyManager
        toReturn.cities = cities.map { it.clone() }

        // This is the only thing that is NOT switched out, which makes it a source of ConcurrentModification errors.
        // Cloning it by-pointer is a horrific move, since the serialization would go over it ANYWAY and still lead to concurrency problems.
        // Cloning it by iterating on the tilemap values may seem ridiculous, but it's a perfectly thread-safe way to go about it, unlike the other solutions.
        toReturn.exploredTiles.addAll(gameInfo.tileMap.values.asSequence().map { it.position }.filter { it in exploredTiles })
        toReturn.notifications.addAll(notifications)
        toReturn.citiesCreated = citiesCreated
        toReturn.popupAlerts.addAll(popupAlerts)
        toReturn.tradeRequests.addAll(tradeRequests)
        toReturn.naturalWonders.addAll(naturalWonders)
        toReturn.cityStatePersonality = cityStatePersonality
        toReturn.flagsCountdown.putAll(flagsCountdown)
        return toReturn
    }

    //region pure functions
    fun getDifficulty(): Difficulty {
        if (isPlayerCivilization()) return gameInfo.getDifficulty()
        return gameInfo.ruleSet.difficulties["Chieftain"]!!
    }

    fun getDiplomacyManager(civInfo: CivilizationInfo) = getDiplomacyManager(civInfo.civName)
    fun getDiplomacyManager(civName: String) = diplomacy[civName]!!

    /** Returns only undefeated civs, aka the ones we care about */
    fun getKnownCivs() = diplomacy.values.map { it.otherCiv() }.filter { !it.isDefeated() }
    fun knows(otherCivName: String) = diplomacy.containsKey(otherCivName)
    fun knows(otherCiv: CivilizationInfo) = knows(otherCiv.civName)

    fun getCapital() = cities.first { it.isCapital() }
    fun isPlayerCivilization() = playerType == PlayerType.Human
    fun isOneCityChallenger() = (
            playerType == PlayerType.Human &&
                    gameInfo.gameParameters.oneCityChallenge)

    fun isCurrentPlayer() = gameInfo.getCurrentPlayerCivilization() == this
    fun isBarbarian() = nation.isBarbarian()
    fun isSpectator() = nation.isSpectator()
    fun isCityState(): Boolean = nation.isCityState()
    val cityStateType: CityStateType get() = nation.cityStateType!!
    var cityStatePersonality: CityStatePersonality = CityStatePersonality.Neutral
    fun isMajorCiv() = nation.isMajorCiv()
    fun isAlive(): Boolean = !isDefeated()
    fun hasEverBeenFriendWith(otherCiv: CivilizationInfo): Boolean = getDiplomacyManager(otherCiv).everBeenFriends()
    fun hasMetCivTerritory(otherCiv: CivilizationInfo): Boolean = otherCiv.getCivTerritory().any { it in exploredTiles }
    private fun getCivTerritory() = cities.asSequence().flatMap { it.tiles.asSequence() }

    fun victoryType(): VictoryType {
        val victoryTypes = gameInfo.gameParameters.victoryTypes
        if (victoryTypes.size == 1)
            return victoryTypes.first() // That is the most relevant one
        val victoryType = nation.preferredVictoryType
        return if (victoryType in victoryTypes) victoryType
               else VictoryType.Neutral
    }

    fun stats() = CivInfoStats(this)
    fun transients() = CivInfoTransientUpdater(this)

    fun updateStatsForNextTurn() {
        happinessForNextTurn = stats().getHappinessBreakdown().values.sum().roundToInt()
        statsForNextTurn = stats().getStatMapForNextTurn().values.reduce { a, b -> a + b }
    }

    fun getHappiness() = happinessForNextTurn


    fun getCivResources(): ResourceSupplyList {
        val newResourceSupplyList = ResourceSupplyList()
        for (resourceSupply in detailedCivResources)
            newResourceSupplyList.add(resourceSupply.resource, resourceSupply.amount, "All")
        return newResourceSupplyList
    }

    fun isCapitalConnectedToCity(city: CityInfo): Boolean = citiesConnectedToCapitalToMediums.keys.contains(city)


    /**
     * Returns a dictionary of ALL resource names, and the amount that the civ has of each
     */
    fun getCivResourcesByName(): HashMap<String, Int> {
        val hashMap = HashMap<String, Int>(gameInfo.ruleSet.tileResources.size)
        for (resource in gameInfo.ruleSet.tileResources.keys) hashMap[resource] = 0
        for (entry in getCivResources())
            hashMap[entry.resource.name] = entry.amount
        return hashMap
    }

    fun getResourceModifier(resource: TileResource): Int {
        var resourceModifier = 1
        for (unique in getMatchingUniques("Double quantity of [] produced"))
            if (unique.params[0] == resource.name)
                resourceModifier *= 2
        if (resource.resourceType == ResourceType.Strategic) {
            if (hasUnique("Quantity of strategic resources produced by the empire increased by 100%"))
                resourceModifier *= 2
        }
        return resourceModifier
    }

    fun hasResource(resourceName: String): Boolean = getCivResourcesByName()[resourceName]!! > 0

    fun getCivWideBuildingUniques(): Sequence<Unique> = cities.asSequence().flatMap {
        city -> city.cityConstructions.builtBuildingUniqueMap.getAllUniques()
                .filter { it.params.isEmpty() || it.params.last() != "in this city" }
    }

    fun hasUnique(unique: String) = getMatchingUniques(unique).any()

    // Does not return local uniques, only global ones.
    fun getMatchingUniques(uniqueTemplate: String): Sequence<Unique> {
        return nation.uniqueObjects.asSequence().filter { it.placeholderText == uniqueTemplate } +
                cities.asSequence().flatMap {
                    city -> city.cityConstructions.builtBuildingUniqueMap.getUniques(uniqueTemplate).asSequence()
                            .filter { it.params.isEmpty() || it.params.last() != "in this city" }
                } +
                policies.policyUniques.getUniques(uniqueTemplate) +
                tech.getTechUniques().filter { it.placeholderText == uniqueTemplate } +
                religionManager.getUniques().filter { it.placeholderText == uniqueTemplate }
    }

    //region Units
    fun getCivUnits(): Sequence<MapUnit> = units.asSequence()
    fun getCivGreatPeople(): Sequence<MapUnit> = getCivUnits().filter { mapUnit -> mapUnit.isGreatPerson() }

    fun addUnit(mapUnit: MapUnit, updateCivInfo: Boolean = true) {
        val newList = ArrayList(units)
        newList.add(mapUnit)
        units = newList

        if (updateCivInfo) {
            // Not relevant when updating TileInfo transients, since some info of the civ itself isn't yet available,
            // and in any case it'll be updated once civ info transients are
            updateStatsForNextTurn() // unit upkeep
            updateDetailedCivResources()
        }
    }

    fun removeUnit(mapUnit: MapUnit) {
        val newList = ArrayList(units)
        newList.remove(mapUnit)
        units = newList
        updateStatsForNextTurn() // unit upkeep
        updateDetailedCivResources()
    }

    fun getIdleUnits() = getCivUnits().filter { it.isIdle() }

    private fun getDueUnits() = getCivUnits().filter { it.due && it.isIdle() }

    fun shouldGoToDueUnit() = UncivGame.Current.settings.checkForDueUnits && getDueUnits().any()

    fun getNextDueUnit(): MapUnit? {
        val dueUnits = getDueUnits()
        if (dueUnits.any()) {
            val unit = dueUnits.first()
            unit.due = false
            return unit
        }
        return null
    }
    //endregion

    fun shouldOpenTechPicker(): Boolean {
        if (!tech.canResearchTech()) return false
        if (tech.freeTechs != 0) return true
        return tech.currentTechnology() == null && cities.isNotEmpty()
    }


    fun getEquivalentBuilding(buildingName: String): Building {
        val baseBuilding = gameInfo.ruleSet.buildings[buildingName]!!.getBaseBuilding(gameInfo.ruleSet)

        for (building in gameInfo.ruleSet.buildings.values)
            if (building.replaces == baseBuilding.name && building.uniqueTo == civName)
                return building
        return baseBuilding
    }

    fun getEquivalentUnit(baseUnitName: String): BaseUnit {
        val baseUnit = gameInfo.ruleSet.units[baseUnitName]
        @Suppress("FoldInitializerAndIfToElvis")
        if (baseUnit == null) throw UncivShowableException("Unit $baseUnitName doesn't seem to exist!")
        if (baseUnit.replaces != null) return getEquivalentUnit(baseUnit.replaces!!) // Equivalent of unique unit is the equivalent of the replaced unit

        for (unit in gameInfo.ruleSet.units.values)
            if (unit.replaces == baseUnitName && unit.uniqueTo == civName)
                return unit
        return baseUnit
    }

    fun meetCivilization(otherCiv: CivilizationInfo) {
        diplomacy[otherCiv.civName] = DiplomacyManager(this, otherCiv.civName)
                .apply { diplomaticStatus = DiplomaticStatus.Peace }

        otherCiv.popupAlerts.add(PopupAlert(AlertType.FirstContact, civName))

        otherCiv.diplomacy[civName] = DiplomacyManager(otherCiv, civName)
                .apply { diplomaticStatus = DiplomaticStatus.Peace }
        popupAlerts.add(PopupAlert(AlertType.FirstContact, otherCiv.civName))

        if (isCurrentPlayer() || otherCiv.isCurrentPlayer())
            UncivGame.Current.settings.addCompletedTutorialTask("Meet another civilization")
    }

    fun discoverNaturalWonder(naturalWonderName: String) {
        naturalWonders.add(naturalWonderName)
    }

    override fun toString(): String {
        return civName
    } // for debug

    /** Returns true if the civ was fully initialized and has no cities remaining */
    fun isDefeated(): Boolean {
        // Dirty hack: exploredTiles are empty only before starting units are placed
        return if (exploredTiles.isEmpty() || isBarbarian() || isSpectator()) false
        else cities.isEmpty() // No cities
                && (citiesCreated > 0 || !getCivUnits().any { it.hasUnique(Constants.settlerUnique) })
    }

    fun getEra(): String {
        if (gameInfo.ruleSet.technologies.isEmpty()) return "None"
        if (tech.researchedTechnologies.isEmpty())
            return gameInfo.ruleSet.getEras().first()
        return tech.researchedTechnologies
                .asSequence()
                .map { it.column!! }
                .maxByOrNull { it.columnNumber }!!
                .era
    }

    fun getEraNumber(): Int = gameInfo.ruleSet.getEraNumber(getEra())

    fun isAtWarWith(otherCiv: CivilizationInfo): Boolean {
        if (otherCiv.civName == civName) return false // never at war with itself
        if (otherCiv.isBarbarian() || isBarbarian()) return true
        val diplomacyManager = diplomacy[otherCiv.civName]
                ?: return false // not encountered yet
        return diplomacyManager.diplomaticStatus == DiplomaticStatus.War
    }

    fun isAtWar() = diplomacy.values.any { it.diplomaticStatus == DiplomaticStatus.War && !it.otherCiv().isDefeated() }

    /**
     * Returns a civilization caption suitable for greetings including player type info:
     * Like "Milan" if the nation is a city state, "Caesar of Rome" otherwise, with an added
     * " (AI)", " (Human - Hotseat)", or " (Human - Multiplayer)" if the game is multiplayer.
     */ 
    fun getLeaderDisplayName(): String {
        return nation.getLeaderDisplayName().tr() +
            when {
                !gameInfo.gameParameters.isOnlineMultiplayer ->
                    ""
                playerType == PlayerType.AI ->
                    " (" + "AI".tr() + ")"
                gameInfo.civilizations.count { it.playerType == PlayerType.Human } > 1 ->
                    " (" + "Human".tr() + " - " + "Hotseat".tr() + ")"
                else ->
                    " (" + "Human".tr() + " - " + "Multiplayer".tr() + ")"
            }
    }

    fun canSignResearchAgreement(): Boolean {
        if (!isMajorCiv()) return false
        if (!hasUnique("Enables Research agreements")) return false
        if (gameInfo.ruleSet.technologies.values
                        .none { tech.canBeResearched(it.name) && !tech.isResearched(it.name) }) return false
        return true
    }

    fun canSignResearchAgreementsWith(otherCiv: CivilizationInfo): Boolean {
        val diplomacyManager = getDiplomacyManager(otherCiv)
        val cost = getResearchAgreementCost()
        return canSignResearchAgreement() && otherCiv.canSignResearchAgreement()
                && diplomacyManager.hasFlag(DiplomacyFlags.DeclarationOfFriendship)
                && !diplomacyManager.hasFlag(DiplomacyFlags.ResearchAgreement)
                && !diplomacyManager.otherCivDiplomacy().hasFlag(DiplomacyFlags.ResearchAgreement)
                && gold >= cost && otherCiv.gold >= cost
    }

    fun getStatForRanking(category: RankingType): Int {
        return when (category) {
            RankingType.Population -> cities.sumBy { it.population.population }
            RankingType.Crop_Yield -> statsForNextTurn.food.roundToInt()
            RankingType.Production -> statsForNextTurn.production.roundToInt()
            RankingType.Gold -> gold
            RankingType.Territory -> cities.sumBy { it.tiles.size }
            RankingType.Force -> units.sumBy { it.baseUnit.strength }
            RankingType.Happiness -> getHappiness()
            RankingType.Technologies -> tech.researchedTechnologies.size
            RankingType.Culture -> policies.adoptedPolicies.count { !it.endsWith("Complete") }
        }
    }


    fun getGreatPeople() = gameInfo.ruleSet.units.values.asSequence()
            .filter { it.isGreatPerson() }.map { getEquivalentUnit(it.name) }.toHashSet()

    //endregion

    //region state-changing functions

    /** This is separate because the REGULAR setTransients updates the viewable ties,
     *  and updateVisibleTiles tries to meet civs...
     *  And if the civs don't yet know who they are then they don't know if they're barbarians =\
     *  */
    fun setNationTransient() {
        nation = gameInfo.ruleSet.nations[civName]
                ?: throw java.lang.Exception("Nation $civName is not found!")
    }

    fun setTransients() {
        goldenAges.civInfo = this

        policies.civInfo = this
        if (policies.adoptedPolicies.size > 0 && policies.numberOfAdoptedPolicies == 0)
            policies.numberOfAdoptedPolicies = policies.adoptedPolicies.count { !it.endsWith("Complete") }
        policies.setTransients()

        questManager.civInfo = this
        questManager.setTransients()

        if (citiesCreated == 0 && cities.any())
            citiesCreated = cities.filter { it.name in nation.cities }.count()

        religionManager.civInfo = this // needs to be before tech, since tech setTransients looks at all uniques

        tech.civInfo = this
        tech.setTransients()


        for (diplomacyManager in diplomacy.values) {
            diplomacyManager.civInfo = this
            diplomacyManager.updateHasOpenBorders()
        }

        victoryManager.civInfo = this

        for (cityInfo in cities) {
            cityInfo.civInfo = this // must be before the city's setTransients because it depends on the tilemap, that comes from the currentPlayerCivInfo
            cityInfo.setTransients()
        }
    }

    fun updateSightAndResources() {
        updateViewableTiles()
        updateHasActiveGreatWall()
        updateDetailedCivResources()
    }

    // implementation in a separate class, to not clog up CivInfo
    fun initialSetCitiesConnectedToCapitalTransients() = transients().updateCitiesConnectedToCapital(true)
    fun updateHasActiveGreatWall() = transients().updateHasActiveGreatWall()
    fun updateViewableTiles() = transients().updateViewableTiles()
    fun updateDetailedCivResources() = transients().updateDetailedCivResources()

    fun startTurn() {
        policies.startTurn()
        updateStatsForNextTurn() // for things that change when turn passes e.g. golden age, city state influence

        // Generate great people at the start of the turn,
        // so they won't be generated out in the open and vulnerable to enemy attacks before you can control them
        if (cities.isNotEmpty()) { //if no city available, addGreatPerson will throw exception
            val greatPerson = greatPeople.getNewGreatPerson()
            if (greatPerson != null && gameInfo.ruleSet.units.containsKey(greatPerson)) addUnit(greatPerson)
        }

        updateViewableTiles() // adds explored tiles so that the units will be able to perform automated actions better
        transients().updateCitiesConnectedToCapital()
        for (city in cities) city.startTurn()

        for (unit in getCivUnits()) unit.startTurn()

        for (tradeRequest in tradeRequests.toList()) { // remove trade requests where one of the sides can no longer supply
            val offeringCiv = gameInfo.getCivilization(tradeRequest.requestingCiv)
            if (offeringCiv.isDefeated() || !TradeEvaluation().isTradeValid(tradeRequest.trade, this, offeringCiv)) {
                tradeRequests.remove(tradeRequest)
                // Yes, this is the right direction. I checked.
                offeringCiv.addNotification("Our proposed trade is no longer relevant!", NotificationIcon.Trade)
            }
        }
        updateDetailedCivResources() // If you offered a trade last turn, this turn it will have been accepted/declined
    }

    fun endTurn() {
        notifications.clear()

        val nextTurnStats = statsForNextTurn

        policies.endTurn(nextTurnStats.culture.toInt())

        if (isCityState())
            questManager.endTurn()

        // disband units until there are none left OR the gold values are normal
        if (!isBarbarian() && gold < -100 && nextTurnStats.gold.toInt() < 0) {
            for (i in 1 until (gold / -100)) {
                var civMilitaryUnits = getCivUnits().filter { !it.type.isCivilian() }
                if (civMilitaryUnits.any()) {
                    val unitToDisband = civMilitaryUnits.first()
                    unitToDisband.disband()
                    civMilitaryUnits -= unitToDisband
                    val unitName = unitToDisband.displayName()
                    addNotification("Cannot provide unit upkeep for [$unitName] - unit has been disbanded!", unitName, NotificationIcon.Death)
                }
            }
        }

        addGold( nextTurnStats.gold.toInt() )

        if (cities.isNotEmpty() && gameInfo.ruleSet.technologies.isNotEmpty())
            tech.endTurn(nextTurnStats.science.toInt())

        religionManager.endTurn(nextTurnStats.faith.toInt())

        if (isMajorCiv()) greatPeople.addGreatPersonPoints(getGreatPersonPointsForNextTurn()) // City-states don't get great people!

        for (city in cities.toList()) { // a city can be removed while iterating (if it's being razed) so we need to iterate over a copy
            city.endTurn()
        }

        goldenAges.endTurn(getHappiness())
        getCivUnits().forEach { it.endTurn() }
        diplomacy.values.toList().forEach { it.nextTurn() } // we copy the diplomacy values so if it changes in-loop we won't crash
        updateAllyCivForCityState()
        updateHasActiveGreatWall()
    }

    fun addGold(delta: Int) {
        gold = when {
            delta > 0 && gold > Int.MAX_VALUE - delta -> Int.MAX_VALUE
            delta < 0 && gold < Int.MIN_VALUE - delta -> Int.MIN_VALUE
            else -> gold + delta
        }
    }

    fun getGreatPersonPointsForNextTurn(): Stats {
        val stats = Stats()
        for (city in cities) stats.add(city.getGreatPersonPoints())
        return stats
    }

    fun canEnterTiles(otherCiv: CivilizationInfo): Boolean {
        if (otherCiv == this) return true
        if (otherCiv.isBarbarian()) return true
        if (nation.isBarbarian() && gameInfo.turns >= gameInfo.difficultyObject.turnBarbariansCanEnterPlayerTiles)
            return true
        val diplomacyManager = diplomacy[otherCiv.civName]
                ?: return false // not encountered yet
        return (diplomacyManager.hasOpenBorders || diplomacyManager.diplomaticStatus == DiplomaticStatus.War)
    }


    fun addNotification(text: String, location: Vector2, vararg notificationIcons: String) {
        addNotification(text, LocationAction(listOf(location)), *notificationIcons)
    }

    fun addNotification(text: String, vararg notificationIcons: String) = addNotification(text, null, *notificationIcons)

    fun addNotification(text: String, action: NotificationAction?, vararg notificationIcons: String) {
        if (playerType == PlayerType.AI) return // no point in lengthening the saved game info if no one will read it
        val arrayList = ArrayList<String>().apply { addAll(notificationIcons) }
        notifications.add(Notification(text, arrayList, action))
    }

    fun addUnit(unitName: String, city: CityInfo? = null) {
        if (cities.isEmpty()) return
        val cityToAddTo = city ?: cities.random()
        if (!gameInfo.ruleSet.units.containsKey(unitName)) return
        val unit = getEquivalentUnit(unitName)
        // silently bail if no tile to place the unit is found
        val placedUnit = placeUnitNearTile(cityToAddTo.location, unit.name)
        if (placedUnit != null && unit.isGreatPerson()) {
            addNotification("A [${unit.name}] has been born in [${cityToAddTo.name}]!", placedUnit.getTile().position, unit.name)
        }
    }

    fun placeUnitNearTile(location: Vector2, unitName: String): MapUnit? {
        return gameInfo.tileMap.placeUnitNearTile(location, unitName, this)
    }

    fun addCity(location: Vector2) {
        val newCity = CityInfo(this, location)
        newCity.cityConstructions.chooseNextConstruction()

    }


    fun destroy() {
        val destructionText = if (isMajorCiv()) "The civilization of [$civName] has been destroyed!"
        else "The City-State of [$civName] has been destroyed!"
        for (civ in gameInfo.civilizations)
            civ.addNotification(destructionText, civName, NotificationIcon.Death)
        getCivUnits().forEach { it.destroy() }
        tradeRequests.clear() // if we don't do this then there could be resources taken by "pending" trades forever
        for (diplomacyManager in diplomacy.values) {
            diplomacyManager.trades.clear()
            diplomacyManager.otherCiv().getDiplomacyManager(this).trades.clear()
            for (tradeRequest in diplomacyManager.otherCiv().tradeRequests.filter { it.requestingCiv == civName })
                diplomacyManager.otherCiv().tradeRequests.remove(tradeRequest) // it  would be really weird to get a trade request from a dead civ
        }
    }

    fun influenceGainedByGift(cityState: CivilizationInfo, giftAmount: Int): Int {
        var influenceGained = giftAmount / 10f
        for (unique in cityState.getMatchingUniques("Gifts of Gold to City-States generate []% more Influence"))
            influenceGained *= (100f + unique.params[0].toInt()) / 100
        return influenceGained.toInt()
    }

    fun giveGoldGift(cityState: CivilizationInfo, giftAmount: Int) {
        if (!cityState.isCityState()) throw Exception("You can only gain influence with City-States!")
        addGold(-giftAmount)
        cityState.addGold(giftAmount)
        cityState.getDiplomacyManager(this).influence += influenceGainedByGift(cityState, giftAmount)
        cityState.updateAllyCivForCityState()
        updateStatsForNextTurn()
    }

    fun getResearchAgreementCost(): Int {
        // https://forums.civfanatics.com/resources/research-agreements-bnw.25568/
        val basicGoldCostOfSignResearchAgreement = when (getEra()) {
            Constants.medievalEra, Constants.renaissanceEra -> 250
            Constants.industrialEra -> 300
            Constants.modernEra -> 350
            Constants.informationEra, Constants.futureEra -> 400
            else -> 0
        }
        return (basicGoldCostOfSignResearchAgreement * gameInfo.gameParameters.gameSpeed.modifier).toInt()
    }

    fun gainMilitaryUnitFromCityState(otherCiv: CivilizationInfo) {
        val cities = NextTurnAutomation.getClosestCities(this, otherCiv)
        val city = cities.city1
        val militaryUnit = city.cityConstructions.getConstructableUnits()
                .filter { !it.unitType.isCivilian() && it.unitType.isLandUnit() && it.uniqueTo==null }
                .toList().random()
        // placing the unit may fail - in that case stay quiet
        val placedUnit = placeUnitNearTile(city.location, militaryUnit.name) ?: return
        // Point to the places mentioned in the message _in that order_ (debatable)
        val placedLocation = placedUnit.getTile().position
        val locations = LocationAction(listOf(placedLocation, cities.city2.location, city.location))
        addNotification("[${otherCiv.civName}] gave us a [${militaryUnit.name}] as gift near [${city.name}]!", locations, otherCiv.civName, militaryUnit.name)
    }

    fun getAllyCiv() = allyCivName

    fun getProtectorCivs() : List<CivilizationInfo> {
        if(this.isMajorCiv()) return emptyList()
        return diplomacy.values
                .filter{ !it.otherCiv().isDefeated() && it.diplomaticStatus == DiplomaticStatus.Protector }
                .map{ it.otherCiv() }
    }

    fun addProtectorCiv(otherCiv: CivilizationInfo) {
        if(!this.isCityState() or !otherCiv.isMajorCiv() or otherCiv.isDefeated()) return
        if(!knows(otherCiv) or isAtWarWith(otherCiv)) return //Exception

        val diplomacy = getDiplomacyManager(otherCiv.civName)
        diplomacy.diplomaticStatus = DiplomaticStatus.Protector
    }

    fun removeProtectorCiv(otherCiv: CivilizationInfo) {
        if(!this.isCityState() or !otherCiv.isMajorCiv() or otherCiv.isDefeated()) return
        if(!knows(otherCiv) or isAtWarWith(otherCiv)) return //Exception

        val diplomacy = getDiplomacyManager(otherCiv.civName)
        diplomacy.diplomaticStatus = DiplomaticStatus.Peace
        diplomacy.influence -= 20
    }

    fun updateAllyCivForCityState() {
        var newAllyName = ""
        if (!isCityState()) return
        val maxInfluence = diplomacy
                .filter { !it.value.otherCiv().isCityState() && !it.value.otherCiv().isDefeated() }
                .maxByOrNull { it.value.influence }
        if (maxInfluence != null && maxInfluence.value.influence >= 60) {
            newAllyName = maxInfluence.key
        }

        if (allyCivName != newAllyName) {
            val oldAllyName = allyCivName
            allyCivName = newAllyName

            // If the city-state is captured by a civ, it stops being the ally of the civ it was previously an ally of.
            //  This means that it will NOT HAVE a capital at that time, so if we run getCapital we'll get a crash!
            val capitalLocation = if (cities.isNotEmpty()) getCapital().location else null

            if (newAllyName != "") {
                val newAllyCiv = gameInfo.getCivilization(newAllyName)
                val text = "We have allied with [${civName}]."
                if (capitalLocation != null) newAllyCiv.addNotification(text, capitalLocation, civName, NotificationIcon.Diplomacy)
                else newAllyCiv.addNotification(text, civName, NotificationIcon.Diplomacy)
                newAllyCiv.updateViewableTiles()
                newAllyCiv.updateDetailedCivResources()
            }
            if (oldAllyName != "") {
                val oldAllyCiv = gameInfo.getCivilization(oldAllyName)
                val text = "We have lost alliance with [${civName}]."
                if (capitalLocation != null) oldAllyCiv.addNotification(text, capitalLocation, civName, NotificationIcon.Diplomacy)
                else oldAllyCiv.addNotification(text, civName, NotificationIcon.Diplomacy)
                oldAllyCiv.updateViewableTiles()
                oldAllyCiv.updateDetailedCivResources()
            }
        }
    }

    //endregion
}

// reduced variant only for load preview
class CivilizationInfoPreview {
    var civName = ""
    var playerType = PlayerType.AI
    var playerId = ""
    fun isPlayerCivilization() = playerType == PlayerType.Human
}
