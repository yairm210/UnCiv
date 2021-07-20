package com.unciv.logic.city

import com.unciv.logic.automation.ConstructionAutomation
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.PopupAlert
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.UniqueMap
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stats
import com.unciv.models.translations.tr
import com.unciv.ui.civilopedia.CivilopediaCategories
import com.unciv.ui.civilopedia.FormattedLine
import com.unciv.ui.utils.Fonts
import com.unciv.ui.utils.withItem
import com.unciv.ui.utils.withoutItem
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * City constructions manager.
 *
 * @property cityInfo the city it refers to
 * @property currentConstructionFromQueue the name of the construction is currently worked, default = "Monument"
 * @property currentConstructionIsUserSet a flag indicating if the [currentConstructionFromQueue] has been set by the user or by the AI
 * @property constructionQueue a list of constructions names enqueued
 */
class CityConstructions {
    @Transient
    lateinit var cityInfo: CityInfo

    @Transient
    private var builtBuildingObjects = ArrayList<Building>()

    @Transient
    val builtBuildingUniqueMap = UniqueMap()

    var builtBuildings = HashSet<String>()
    val inProgressConstructions = HashMap<String, Int>()
    var currentConstructionFromQueue: String
        get() {
            if (constructionQueue.isEmpty()) return "" else return constructionQueue.first()
        }
        set(value) {
            if (constructionQueue.isEmpty()) constructionQueue.add(value) else constructionQueue[0] = value
        }
    var currentConstructionIsUserSet = false
    var constructionQueue = mutableListOf<String>()
    var productionOverflow = 0
    val queueMaxSize = 10

    //region pure functions
    fun clone(): CityConstructions {
        val toReturn = CityConstructions()
        toReturn.builtBuildings.addAll(builtBuildings)
        toReturn.inProgressConstructions.putAll(inProgressConstructions)
        toReturn.currentConstructionIsUserSet = currentConstructionIsUserSet
        toReturn.constructionQueue.addAll(constructionQueue)
        toReturn.productionOverflow = productionOverflow
        return toReturn
    }

    internal fun getBuildableBuildings(): Sequence<Building> = cityInfo.getRuleset().buildings.values
            .asSequence().filter { it.isBuildable(this) }

    fun getConstructableUnits() = cityInfo.getRuleset().units.values
            .asSequence().filter { it.isBuildable(this) }

    fun getBasicCultureBuildings() = cityInfo.getRuleset().buildings.values
            .asSequence().filter { it.culture > 0f && !it.isAnyWonder() && it.replaces == null }

    /**
     * @return [Stats] provided by all built buildings in city plus the bonus from Library
     */
    fun getStats(): Stats {
        val stats = Stats()
        for (building in getBuiltBuildings())
            stats.add(building.getStats(cityInfo))
        
        // Why only the local matching uniques you ask? Well, the non-local uniques are evaluated in
        // CityStats.getStatsFromUniques(uniques). This function gets a list of uniques and one of
        // the placeholderTexts it filters for is "[] per [] population []". Why doesn't that function
        // then not also handle the local (i.e. cityFilter == "in this city") versions?
        // This is because of what it is used for. The only time time a unique with this placeholderText
        // is actually contained in `uniques`, is when `getStatsFromUniques` is called for determining
        // the stats a city receives from wonders. It is then called with `unique` being the list
        // of all specifically non-local uniques of all cities.
        // 
        // This averts the problem, albeit it barely, and it might change in the future without 
        // anyone noticing, which might lead to further bugs. So why can't these two unique checks
        // just be merged then? Because of another problem.
        //
        // As noted earlier, `getStatsFromUniques` is called in that case to calculate the stats
        // this city received from wonders, both local and non-local. This function, `getStats`,
        // is only called to calculate the stats the city receives from local buildings.
        // In the current codebase with the current JSON objects it just so happens to be that
        // all non-local uniques with this placeholderText from other cities belong to wonders, 
        // while the local uniques with this placeholderText are from buildings, but this is in no
        // way a given. In reality, there should be functions getBuildingStats and getWonderStats,
        // to solve this, with getStats merely adding these two together. Implementing this is on
        // my ToDoList, but this PR is already large enough as it is.
        for (unique in cityInfo.getLocalMatchingUniques("[] per [] population []")
                .filter { cityInfo.matchesFilter(it.params[2])}
        ) {
            stats.add(unique.stats.times(cityInfo.population.population / unique.params[1].toFloat()))
        }
        
        for (unique in cityInfo.getLocalMatchingUniques("[] once [] is discovered")) {
            if (cityInfo.civInfo.tech.isResearched(unique.params[1]))
                stats.add(unique.stats)
        }
        
        return stats
    }

    /**
     * @return Maintenance cost of all built buildings
     */
    fun getMaintenanceCosts(): Int {
        var maintenanceCost = 0
        // We cache this to increase performance
        val freeBuildings = cityInfo.civInfo.policies.getListOfFreeBuildings(cityInfo.id)
        for (building in getBuiltBuildings()) {
            if (building.name !in freeBuildings) {
                maintenanceCost += building.maintenance
            }
        }
        return maintenanceCost
    }

    /**
     * @return Bonus (%) [Stats] provided by all built buildings in city
     */
    fun getStatPercentBonuses(): Stats {
        val stats = Stats()
        for (building in getBuiltBuildings())
            stats.add(building.getStatPercentageBonuses(cityInfo))
        return stats
    }

    fun getCityProductionTextForCityButton(): String {
        val currentConstructionSnapshot = currentConstructionFromQueue // See below
        var result = currentConstructionSnapshot.tr()
        if (currentConstructionSnapshot != "") {
            val construction = PerpetualConstruction.perpetualConstructionsMap[currentConstructionSnapshot]
            if (construction == null) result += getTurnsToConstructionString(currentConstructionSnapshot)
            else result += construction.getProductionTooltip(cityInfo)
        }
        return result
    }


    internal fun getTurnsToConstructionString(constructionName: String, useStoredProduction:Boolean = true): String {
        val construction = getConstruction(constructionName)
        val cost = construction.getProductionCost(cityInfo.civInfo)
        val turnsToConstruction = turnsToConstruction(constructionName, useStoredProduction)
        val currentProgress = if (useStoredProduction) getWorkDone(constructionName) else 0
        if (currentProgress == 0) return "\n$cost${Fonts.production} $turnsToConstruction${Fonts.turn}"
        else return "\n$currentProgress/$cost${Fonts.production}\n$turnsToConstruction${Fonts.turn}"
    }

    fun getProductionForTileInfo(): String {
        /* this is because there were rare errors that I assume were caused because
           currentConstruction changed on another thread */
        val currentConstructionSnapshot = currentConstructionFromQueue
        var result = currentConstructionSnapshot.tr()
        if (currentConstructionSnapshot != ""
                && !PerpetualConstruction.perpetualConstructionsMap.containsKey(currentConstructionSnapshot)) {
            val turnsLeft = turnsToConstruction(currentConstructionSnapshot)
            result += " - $turnsLeft${Fonts.turn}"
        }
        return result
    }

    fun getProductionMarkup(ruleset: Ruleset): FormattedLine {
        val currentConstructionSnapshot = currentConstructionFromQueue
        if (currentConstructionSnapshot.isEmpty()) return FormattedLine()
        val category = when {
            ruleset.buildings[currentConstructionSnapshot]?.isAnyWonder() == true ->
                CivilopediaCategories.Wonder.name
            currentConstructionSnapshot in ruleset.buildings ->
                CivilopediaCategories.Building.name
            currentConstructionSnapshot in ruleset.units ->
                CivilopediaCategories.Unit.name
            else -> ""
        }
        var label = "{$currentConstructionSnapshot}"
        if (!PerpetualConstruction.perpetualConstructionsMap.containsKey(currentConstructionSnapshot)) {
            val turnsLeft = turnsToConstruction(currentConstructionSnapshot)
            label += " - $turnsLeft${Fonts.turn}"
        }
        return if (category.isEmpty()) FormattedLine(label)
            else FormattedLine(label, link="$category/$currentConstructionSnapshot")
    }

    fun getCurrentConstruction(): IConstruction = getConstruction(currentConstructionFromQueue)

    fun isBuilt(buildingName: String): Boolean = builtBuildings.contains(buildingName)
    fun isBeingConstructed(constructionName: String): Boolean = currentConstructionFromQueue == constructionName
    fun isEnqueued(constructionName: String): Boolean = constructionQueue.contains(constructionName)
    fun isBeingConstructedOrEnqueued(constructionName: String): Boolean = isBeingConstructed(constructionName) || isEnqueued(constructionName)

    fun isQueueFull(): Boolean = constructionQueue.size == queueMaxSize

    fun isBuildingWonder(): Boolean {
        val currentConstruction = getCurrentConstruction()
        return currentConstruction is Building && currentConstruction.isWonder
    }

    /** If the city is constructing multiple units of the same type, subsequent units will require the full cost  */
    fun isFirstConstructionOfItsKind(constructionQueueIndex: Int, name: String): Boolean {
        // if the construction name is the same as the current construction, it isn't the first
        return constructionQueueIndex == constructionQueue.indexOfFirst { it == name }
    }


    internal fun getConstruction(constructionName: String): IConstruction {
        val gameBasics = cityInfo.getRuleset()
        when {
            constructionName == "" -> return getConstruction("Nothing")
            gameBasics.buildings.containsKey(constructionName) -> return gameBasics.buildings[constructionName]!!
            gameBasics.units.containsKey(constructionName) -> return gameBasics.units[constructionName]!!
            else -> {
                val special = PerpetualConstruction.perpetualConstructionsMap[constructionName]
                if (special != null) return special
            }
        }

        class NotBuildingOrUnitException(message: String) : Exception(message)
        throw NotBuildingOrUnitException("$constructionName is not a building or a unit!")
    }

    internal fun getBuiltBuildings(): Sequence<Building> = builtBuildingObjects.asSequence()

    fun containsBuildingOrEquivalent(building: String): Boolean =
            isBuilt(building) || getBuiltBuildings().any { it.replaces == building }

    fun getWorkDone(constructionName: String): Int {
        if (inProgressConstructions.containsKey(constructionName)) return inProgressConstructions[constructionName]!!
        else return 0
    }

    fun getRemainingWork(constructionName: String, useStoredProduction: Boolean = true): Int {
        val constr = getConstruction(constructionName)
        return when {
            constr is PerpetualConstruction -> 0
            useStoredProduction -> constr.getProductionCost(cityInfo.civInfo) - getWorkDone(constructionName)
            else -> constr.getProductionCost(cityInfo.civInfo)
        }
    }

    fun turnsToConstruction(constructionName: String, useStoredProduction: Boolean = true): Int {
        val workLeft = getRemainingWork(constructionName, useStoredProduction)
        if (workLeft < 0) // This most often happens when a production is more than finished in a multiplayer game while its not your turn
            return 0 // So we finish it at the start of the next turn. This could technically also happen when we lower production costs during our turn,
        // but distinguishing those two cases is difficult, and the second one is much rarer than the first
        if (workLeft <= productionOverflow) // if we already have stored up enough production to finish it directly
            return 1 // we'll finish this next turn

        val cityStatsForConstruction: Stats
        if (currentConstructionFromQueue == constructionName) cityStatsForConstruction = cityInfo.cityStats.currentCityStats
        else {
            /*
            The ol' Switcharoo - what would our stats be if that was our current construction?
            Since this is only ever used for UI purposes, I feel fine with having it be a bit inefficient
            and recalculating the entire city stats
            We don't want to change our current construction queue - what if we have an empty queue,
             this can affect the city if we run it on another thread like in ConstructionsTable -
            So we run the numbers for the other construction
            ALSO apparently if we run on the actual cityStats from another thread,
              we get all sorts of fun concurrency problems when accessing various parts of the cityStats.
            SO, we create an entirely new CityStats and iterate there - problem solve!
            */
            val cityStats = CityStats()
            cityStats.cityInfo = cityInfo
            val construction = cityInfo.cityConstructions.getConstruction(constructionName)
            cityStats.update(construction)
            cityStatsForConstruction = cityStats.currentCityStats
        }

        val production = cityStatsForConstruction.production.roundToInt()

        return ceil((workLeft-productionOverflow) / production.toDouble()).toInt()
    }
    //endregion

    //region state changing functions
    fun setTransients() {
        builtBuildingObjects = ArrayList(builtBuildings.map {
            cityInfo.getRuleset().buildings[it]
                    ?: throw java.lang.Exception("Building $it is not found!")
        })
        updateUniques()
    }

    fun addProductionPoints(productionToAdd: Int) {
        if (!inProgressConstructions.containsKey(currentConstructionFromQueue))
            inProgressConstructions[currentConstructionFromQueue] = 0
        inProgressConstructions[currentConstructionFromQueue] = inProgressConstructions[currentConstructionFromQueue]!! + productionToAdd
    }

    fun constructIfEnough() {
        validateConstructionQueue()

        // Update InProgressConstructions for any available refunds
        validateInProgressConstructions()

        val construction = getConstruction(currentConstructionFromQueue)
        if (construction is PerpetualConstruction) chooseNextConstruction() // check every turn if we could be doing something better, because this doesn't end by itself
        else {
            val productionCost = construction.getProductionCost(cityInfo.civInfo)
            if (inProgressConstructions.containsKey(currentConstructionFromQueue)
                    && inProgressConstructions[currentConstructionFromQueue]!! >= productionCost) {
                productionOverflow = inProgressConstructions[currentConstructionFromQueue]!! - productionCost
                // See the URL below for explanation for this cap
                // https://forums.civfanatics.com/threads/hammer-overflow.419352/
                val maxOverflow = maxOf(productionCost, cityInfo.cityStats.currentCityStats.production.roundToInt())
                if (productionOverflow > maxOverflow)
                    productionOverflow = maxOverflow
                constructionComplete(construction)
            }
        }
    }

    fun endTurn(cityStats: Stats) {
        validateConstructionQueue()
        validateInProgressConstructions()

        if (getConstruction(currentConstructionFromQueue) !is PerpetualConstruction) {
            if (getWorkDone(currentConstructionFromQueue) == 0) {
                constructionBegun(getConstruction(currentConstructionFromQueue))
            }
            addProductionPoints(cityStats.production.roundToInt() + productionOverflow)
            productionOverflow = 0
        }
    }


    private fun validateConstructionQueue() {
        val queueSnapshot = constructionQueue.toMutableList()
        constructionQueue.clear()

        for (constructionName in queueSnapshot) {
            if (getConstruction(constructionName).isBuildable(this))
                constructionQueue.add(constructionName)
        }
    }

    private fun validateInProgressConstructions() {
        // remove obsolete stuff from in progress constructions - happens often and leaves clutter in memory and save files
        // should have little visible consequences - any accumulated points that may be reused later should stay (nukes when manhattan project city lost, nat wonder when conquered an empty city...), all other points should be refunded
        // Should at least be called before each turn - if another civ completes a wonder after our previous turn, we should get the refund this turn
        val inProgressSnapshot = inProgressConstructions.keys.filter { it != currentConstructionFromQueue }
        for (constructionName in inProgressSnapshot) {
            val construction = getConstruction(constructionName)
            val rejectionReason: String =
                    when (construction) {
                        is Building -> construction.getRejectionReason(this)
                        is BaseUnit -> construction.getRejectionReason(this)
                        else -> ""
                    }

            if (rejectionReason.endsWith("lready built")
                    || rejectionReason.startsWith("Cannot be built with")
                    || rejectionReason.startsWith("Don't need to build any more")
                    || rejectionReason.startsWith("Obsolete")
            ) {
                if (construction is Building) {
                    // Production put into wonders gets refunded
                    if (construction.isWonder && getWorkDone(constructionName) != 0) {
                        cityInfo.civInfo.addGold( getWorkDone(constructionName) )
                        val buildingIcon = "BuildingIcons/${constructionName}"
                        cityInfo.civInfo.addNotification("Excess production for [$constructionName] converted to [${getWorkDone(constructionName)}] gold", NotificationIcon.Gold, buildingIcon)
                    }
                } else if (construction is BaseUnit) {
                    // Production put into upgradable units gets put into upgraded version
                    if (rejectionReason.startsWith("Obsolete") && construction.upgradesTo != null) {
                        // I'd love to use the '+=' operator but since 'inProgressConstructions[...]' can be null, kotlin doesn't allow me to
                        if (!inProgressConstructions.contains(construction.upgradesTo)) {
                            inProgressConstructions[construction.upgradesTo!!] = getWorkDone(constructionName)
                        } else {
                            inProgressConstructions[construction.upgradesTo!!] = inProgressConstructions[construction.upgradesTo!!]!! + getWorkDone(constructionName)
                        }
                    }
                }
                inProgressConstructions.remove(constructionName)
            }
        }
    }

    private fun constructionBegun(construction: IConstruction) {
        if (construction !is Building) return;
        if (construction.uniqueObjects.none { it.placeholderText == "Triggers a global alert upon build start" }) return
        val buildingIcon = "BuildingIcons/${construction.name}"
        for (otherCiv in cityInfo.civInfo.gameInfo.civilizations) {
            if (otherCiv == cityInfo.civInfo) continue
            when {
                (otherCiv.exploredTiles.contains(cityInfo.location) && otherCiv != cityInfo.civInfo) ->
                    otherCiv.addNotification("The city of [${cityInfo.name}] has started constructing [${construction.name}]!",
                        cityInfo.location, NotificationIcon.Construction, buildingIcon)
                (otherCiv.knows(cityInfo.civInfo)) ->
                    otherCiv.addNotification("[${cityInfo.civInfo.civName}] has started constructing [${construction.name}]!",
                        NotificationIcon.Construction, buildingIcon)
                else -> otherCiv.addNotification("An unknown civilization has started constructing [${construction.name}]!",
                    NotificationIcon.Construction, buildingIcon)
            }
        }
    }

    private fun constructionComplete(construction: IConstruction) {
        construction.postBuildEvent(this)
        if (construction.name in inProgressConstructions)
            inProgressConstructions.remove(construction.name)
        if (construction.name == currentConstructionFromQueue)
            removeCurrentConstruction()

        validateConstructionQueue() // if we've build e.g. the Great Lighthouse, then Lighthouse is no longer relevant in the queue

        val buildingIcon = "BuildingIcons/${construction.name}"
        if (construction is Building && construction.isWonder) {
            cityInfo.civInfo.popupAlerts.add(PopupAlert(AlertType.WonderBuilt, construction.name))
            for (civ in cityInfo.civInfo.gameInfo.civilizations) {
                if (civ.exploredTiles.contains(cityInfo.location))
                    civ.addNotification("[${construction.name}] has been built in [${cityInfo.name}]",
                            cityInfo.location, NotificationIcon.Construction, buildingIcon)
                else
                    civ.addNotification("[${construction.name}] has been built in a faraway land", buildingIcon)
            }
        } else {
            val icon = if (construction is Building) buildingIcon else construction.name // could be a unit, in which case take the unit name.
            cityInfo.civInfo.addNotification("[${construction.name}] has been built in [" + cityInfo.name + "]",
                    cityInfo.location, NotificationIcon.Construction, icon)
        }
        if (construction is Building && construction.uniqueObjects.any { it.placeholderText == "Triggers a global alert upon completion" } ) {
            for (otherCiv in cityInfo.civInfo.gameInfo.civilizations) {
                // No need to notify ourself, since we already got the building notification anyway
                if (otherCiv == cityInfo.civInfo) continue
                val completingCivDescription =
                    if (otherCiv.knows(cityInfo.civInfo)) "[${cityInfo.civInfo.civName}]" else "An unknown civilization"
                otherCiv.addNotification("$completingCivDescription has completed [${construction.name}]!",
                    NotificationIcon.Construction, buildingIcon)
            }
        }
    }

    fun addBuilding(buildingName: String) {
        val buildingObject = cityInfo.getRuleset().buildings[buildingName]!!
        builtBuildingObjects = builtBuildingObjects.withItem(buildingObject)
        builtBuildings.add(buildingName)
        updateUniques()
    }

    fun removeBuilding(buildingName: String) {
        val buildingObject = cityInfo.getRuleset().buildings[buildingName]!!
        builtBuildingObjects = builtBuildingObjects.withoutItem(buildingObject)
        builtBuildings.remove(buildingName)
        updateUniques()
    }

    fun updateUniques() {
        builtBuildingUniqueMap.clear()
        for (building in getBuiltBuildings())
            for (unique in building.uniqueObjects)
                builtBuildingUniqueMap.addUnique(unique)
    }

    /**
     *  Purchase a construction for gold
     *  called from NextTurnAutomation and the City UI
     *  Build / place the new item, deduct cost, and maintain queue.
     *
     *  @param constructionName What to buy (needed since buying something not queued is allowed)
     *  @param queuePosition    Position in the queue or -1 if not from queue
     *                          Note: -1 does not guarantee queue will remain unchanged (validation)
     *  @param automatic        Flag whether automation should try to choose what next to build (not coming from UI)
     *                          Note: settings.autoAssignCityProduction is handled later
     *  @param withFaith        Flag whether the purchase was made with faith or gold. False = gold, true = faith.
     *  @return                 Success (false e.g. unit cannot be placed
     */
    fun purchaseConstruction(
        constructionName: String, 
        queuePosition: Int, 
        automatic: Boolean, 
        withFaith: Boolean = false
    ): Boolean {
        if (!getConstruction(constructionName).postBuildEvent(this, true))
            return false // nothing built - no pay

        if (!cityInfo.civInfo.gameInfo.gameParameters.godMode) {
            if (!withFaith)
                cityInfo.civInfo.addGold(-getConstruction(constructionName).getGoldCost(cityInfo.civInfo))
            else cityInfo.civInfo.religionManager.storedFaith -= getConstruction(constructionName).getFaithCost(cityInfo.civInfo)
        }

        if (queuePosition in 0 until constructionQueue.size)
            removeFromQueue(queuePosition, automatic)
        validateConstructionQueue()

        return true
    }

    fun hasBuildableCultureBuilding(): Boolean {
        return getBasicCultureBuildings()
                .map { cityInfo.civInfo.getEquivalentBuilding(it.name) }
                .filter { it.isBuildable(this) || isBeingConstructedOrEnqueued(it.name) }
                .any()
    }

    fun addCultureBuilding(): String? {
        val buildableCultureBuildings = getBasicCultureBuildings()
                .map { cityInfo.civInfo.getEquivalentBuilding(it.name) }
                .filter { it.isBuildable(this) || isBeingConstructedOrEnqueued(it.name) }

        if (!buildableCultureBuildings.any())
            return null

        val cultureBuildingToBuild = buildableCultureBuildings.minByOrNull { it.cost }!!.name
        constructionComplete(getConstruction(cultureBuildingToBuild))

        return cultureBuildingToBuild
    }

    private fun removeCurrentConstruction() = removeFromQueue(0, true)

    fun chooseNextConstruction() {
        validateConstructionQueue()
        if (constructionQueue.isNotEmpty()) {
            if (currentConstructionFromQueue != ""
                    // If the USER set a perpetual construction, then keep it!
                    && (getConstruction(currentConstructionFromQueue) !is PerpetualConstruction || currentConstructionIsUserSet)) return
        }

        ConstructionAutomation(this).chooseNextConstruction()
    }

    fun addToQueue(constructionName: String) {
        if (isQueueFull()) return
        if (currentConstructionFromQueue == "" || currentConstructionFromQueue == "Nothing") {
            currentConstructionFromQueue = constructionName
        } else if (getConstruction(constructionQueue.last()) is PerpetualConstruction) {
            if (getConstruction(constructionName) is PerpetualConstruction) {  // perpetual constructions will replace each other
                constructionQueue.removeAt(constructionQueue.size - 1)
                constructionQueue.add(constructionName)
            } else
                constructionQueue.add(constructionQueue.size - 1, constructionName) // insert new construction before perpetual one
        } else
            constructionQueue.add(constructionName)
        currentConstructionIsUserSet = true
    }

    /** If this was done automatically, we should automatically try to choose a new construction and treat it as such */
    fun removeFromQueue(constructionQueueIndex: Int, automatic: Boolean) {
        val constructionName = constructionQueue.removeAt(constructionQueueIndex)
        val construction = getConstruction(constructionName)
        if (construction is Building) {
            val improvement = construction.getImprovement(cityInfo.getRuleset())
            if (improvement != null) {
                val tileWithImprovement = cityInfo.getTiles().firstOrNull { it.improvementInProgress == improvement.name }
                tileWithImprovement?.improvementInProgress = null
                tileWithImprovement?.turnsToImprovement = 0
            }
        }

        if (constructionQueue.isEmpty()) {
            if (automatic) chooseNextConstruction()
            else constructionQueue.add("Nothing") // To prevent Construction Automation
            currentConstructionIsUserSet = false
        } else currentConstructionIsUserSet = true // we're just continuing the regular queue
    }

    fun raisePriority(constructionQueueIndex: Int) {
        constructionQueue.swap(constructionQueueIndex - 1, constructionQueueIndex)
    }

    // Lowering == Highering next element in queue
    fun lowerPriority(constructionQueueIndex: Int) {
        raisePriority(constructionQueueIndex + 1)
    }

    //endregion
    private fun MutableList<String>.swap(idx1: Int, idx2: Int) {
        val tmp = this[idx1]
        this[idx1] = this[idx2]
        this[idx2] = tmp
    }
}
