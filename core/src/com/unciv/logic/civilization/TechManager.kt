package com.unciv.logic.civilization

import com.unciv.logic.city.CityInfo
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.RoadStatus
import com.unciv.logic.map.TileInfo
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.Unique
import com.unciv.models.ruleset.UniqueTriggerActivation
import com.unciv.models.ruleset.tech.Technology
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.ui.utils.withItem
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashSet
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class TechManager {
    @Transient
    lateinit var civInfo: CivilizationInfo
    /** This is the Transient list of Technologies */
    @Transient
    var researchedTechnologies = ArrayList<Technology>()
    @Transient
    private var researchedTechUniques = ArrayList<Unique>()

    // MapUnit.canPassThrough is the most called function in the game, and having these extremely specific booleans is or way of improving the time cost
    @Transient
    var wayfinding = false
    @Transient
    var unitsCanEmbark = false
    @Transient
    var embarkedUnitsCanEnterOcean = false

    // UnitMovementAlgorithms.getMovementCostBetweenAdjacentTiles is a close second =)
    @Transient
    var movementSpeedOnRoads = 1f
    @Transient
    var roadsConnectAcrossRivers = false

    var freeTechs = 0

    /** For calculating Great Scientist yields - see https://civilization.fandom.com/wiki/Great_Scientist_(Civ5)  */
    var scienceOfLast8Turns = IntArray(8) { 0 }
    var scienceFromResearchAgreements = 0
    /** This is the lit of strings, which is serialized */
    var techsResearched = HashSet<String>()

    /** When moving towards a certain tech, the user doesn't have to manually pick every one. */
    var techsToResearch = ArrayList<String>()
    var overflowScience = 0
    private var techsInProgress = HashMap<String, Int>()

    /** In civ IV, you can auto-convert a certain percentage of gold in cities to science */
    var goldPercentConvertedToScience = 0.6f

    //region state-changing functions
    fun clone(): TechManager {
        val toReturn = TechManager()
        toReturn.techsResearched.addAll(techsResearched)
        toReturn.freeTechs = freeTechs
        toReturn.techsInProgress.putAll(techsInProgress)
        toReturn.techsToResearch.addAll(techsToResearch)
        toReturn.scienceOfLast8Turns = scienceOfLast8Turns.clone()
        toReturn.scienceFromResearchAgreements = scienceFromResearchAgreements
        toReturn.overflowScience = overflowScience
        toReturn.goldPercentConvertedToScience = goldPercentConvertedToScience
        return toReturn
    }

    fun getNumberOfTechsResearched(): Int = techsResearched.size

    private fun getRuleset() = civInfo.gameInfo.ruleSet

    fun costOfTech(techName: String): Int {
        var techCost = getRuleset().technologies[techName]!!.cost.toFloat()
        if (civInfo.isPlayerCivilization())
            techCost *= civInfo.getDifficulty().researchCostModifier
        techCost *= civInfo.gameInfo.gameParameters.gameSpeed.modifier
        val techsResearchedKnownCivs = civInfo.getKnownCivs()
                .count { it.isMajorCiv() && it.tech.isResearched(techName) }
        val undefeatedCivs = civInfo.gameInfo.civilizations
                .count { it.isMajorCiv() && !it.isDefeated() }
        // https://forums.civfanatics.com/threads/the-mechanics-of-overflow-inflation.517970/
        techCost /= 1 + techsResearchedKnownCivs / undefeatedCivs.toFloat() * 0.3f
        // http://www.civclub.net/bbs/forum.php?mod=viewthread&tid=123976
        val worldSizeModifier = with (civInfo.gameInfo.tileMap.mapParameters.mapSize) {
            when {
                radius >= MapSize.Huge.radius -> floatArrayOf(1.3f, 0.02f)
                radius >= MapSize.Large.radius -> floatArrayOf(1.2f, 0.03f)
                radius >= MapSize.Medium.radius -> floatArrayOf(1.1f, 0.05f)
                else -> floatArrayOf(1f, 0.05f)
            }
        }
        techCost *= worldSizeModifier[0]
        techCost *= 1 + (civInfo.cities.size - 1) * worldSizeModifier[1]
        return techCost.toInt()
    }

    fun currentTechnology(): Technology? {
        val currentTechnologyName = currentTechnologyName()
        if (currentTechnologyName == null) return null
        return getRuleset().technologies[currentTechnologyName]
    }

    fun currentTechnologyName(): String? {
        return if (techsToResearch.isEmpty()) null else techsToResearch[0]
    }

    fun researchOfTech(TechName: String?) = techsInProgress[TechName] ?: 0
    // Was once duplicated as fun scienceSpentOnTech(tech: String): Int
    
    /** Replace a changed tech name, only temporarily used for breaking ruleset updates */
    fun replaceUpdatedTechName(oldTechName: String, newTechName: String) {
        if (oldTechName in techsResearched) {
            techsResearched.remove(oldTechName)
            techsResearched.add(newTechName)
        }
        val index = techsToResearch.indexOf(oldTechName)
        if (index >= 0) {
            techsToResearch[index] = newTechName
        }
        if (oldTechName in techsInProgress) {
            techsInProgress[newTechName] = researchOfTech(oldTechName)
            techsInProgress.remove(oldTechName)
        }
    }

    fun remainingScienceToTech(techName: String) = costOfTech(techName) - researchOfTech(techName)

    fun turnsToTech(techName: String): String {
        return if (civInfo.cities.isEmpty()) "∞" else max(1, ceil(remainingScienceToTech(techName).toDouble() / civInfo.statsForNextTurn.science).toInt()).toString()
    }

    fun isResearched(techName: String): Boolean = techsResearched.contains(techName)

    fun canBeResearched(techName: String): Boolean {
        val tech = getRuleset().technologies[techName]!!
        if (tech.uniqueObjects.any { it.placeholderText=="Incompatible with []" && isResearched(it.params[0]) })
            return false
        if (isResearched(tech.name) && !tech.isContinuallyResearchable())
            return false
        return tech.prerequisites.all { isResearched(it) }
    }

    fun getTechUniques() = researchedTechUniques.asSequence()

    //endregion

    fun getRequiredTechsToDestination(destinationTech: Technology): List<Technology> {
        val prerequisites = Stack<Technology>()

        val checkPrerequisites = ArrayDeque<Technology>()
        checkPrerequisites.add(destinationTech)

        while (!checkPrerequisites.isEmpty()) {
            val techToCheck = checkPrerequisites.pop()
            // future tech can have been researched even when we're researching it,
            // so...if we skip it we'll end up with 0 techs in the "required techs", which will mean that we don't have anything to research. Yeah.
            if (!techToCheck.isContinuallyResearchable() &&
                    (isResearched(techToCheck.name) || prerequisites.contains(techToCheck)))
                continue //no need to add or check prerequisites
            for (prerequisite in techToCheck.prerequisites)
                checkPrerequisites.add(getRuleset().technologies[prerequisite]!!)
            prerequisites.add(techToCheck)
        }

        return prerequisites.sortedBy { it.column!!.columnNumber }
    }

    fun getScienceFromGreatScientist(): Int {
        // https://civilization.fandom.com/wiki/Great_Scientist_(Civ5)
        return (scienceOfLast8Turns.sum() * civInfo.gameInfo.gameParameters.gameSpeed.modifier).toInt()
    }

    private fun addCurrentScienceToScienceOfLast8Turns() {
        // The Science the Great Scientist generates does not include Science from Policies, Trade routes and City-States.
        var allCitiesScience = 0f
        civInfo.cities.forEach { it ->
            val totalBaseScience = it.cityStats.baseStatList.values.map { it.science }.sum()
            val totalBonusPercents = it.cityStats.statPercentBonusList.filter { it.key != "Policies" }.values.map { it.science }.sum()
            allCitiesScience += totalBaseScience * (1 + totalBonusPercents / 100)
        }
        scienceOfLast8Turns[civInfo.gameInfo.turns % 8] = allCitiesScience.toInt()
    }

    private fun limitOverflowScience(overflowScience: Int): Int {
        // http://www.civclub.net/bbs/forum.php?mod=viewthread&tid=123976
        // Apparently yes, we care about the absolute tech cost, not the actual calculated-for-this-player tech cost,
        //  so don't change to costOfTech()
        return min(overflowScience, max(civInfo.statsForNextTurn.science.toInt() * 5,
                getRuleset().technologies[currentTechnologyName()]!!.cost))
    }

    private fun scienceFromResearchAgreements(): Int {
        // https://forums.civfanatics.com/resources/research-agreements-bnw.25568/
        var researchAgreementModifier = 0.5f
        for (unique in civInfo.getMatchingUniques("Science gained from research agreements []%")) {
            researchAgreementModifier += unique.params[0].toFloat() / 200f
        }
        return (scienceFromResearchAgreements / 3 * researchAgreementModifier).toInt()
    }

    fun endTurn(scienceForNewTurn: Int) {
        addCurrentScienceToScienceOfLast8Turns()
        if (currentTechnologyName() == null) return

        var finalScienceToAdd = scienceForNewTurn

        if (scienceFromResearchAgreements != 0) {
            finalScienceToAdd += scienceFromResearchAgreements()
            scienceFromResearchAgreements = 0
        }
        if (overflowScience != 0) { // https://forums.civfanatics.com/threads/the-mechanics-of-overflow-inflation.517970/
            val techsResearchedKnownCivs = civInfo.getKnownCivs()
                    .count { it.isMajorCiv() && it.tech.isResearched(currentTechnologyName()!!) }
            val undefeatedCivs = civInfo.gameInfo.civilizations.count { it.isMajorCiv() && !it.isDefeated() }
            val finalScienceFromOverflow = ((1 + techsResearchedKnownCivs / undefeatedCivs.toFloat() * 0.3f) * overflowScience).toInt()
            finalScienceToAdd += finalScienceFromOverflow
            overflowScience = 0
        }

        addScience(finalScienceToAdd)
    }

    fun addScience(scienceGet: Int) {
        val currentTechnology = currentTechnologyName()
        if (currentTechnology == null) return
        techsInProgress[currentTechnology] = researchOfTech(currentTechnology) + scienceGet
        if (techsInProgress[currentTechnology]!! < costOfTech(currentTechnology))
            return

        // We finished it!
        // http://www.civclub.net/bbs/forum.php?mod=viewthread&tid=123976
        val extraScienceLeftOver = techsInProgress[currentTechnology]!! - costOfTech(currentTechnology)
        overflowScience += limitOverflowScience(extraScienceLeftOver)
        addTechnology(currentTechnology)
    }

    fun getFreeTechnology(techName: String) {
        freeTechs--
        addTechnology(techName)
    }

    fun addTechnology(techName: String) {
        techsInProgress.remove(techName)

        val previousEra = civInfo.getEra()
        techsResearched.add(techName)

        // this is to avoid concurrent modification problems
        val newTech = getRuleset().technologies[techName]!!
        if (!newTech.isContinuallyResearchable())
            techsToResearch.remove(techName)
        researchedTechnologies = researchedTechnologies.withItem(newTech)
        for (unique in newTech.uniqueObjects) {
            researchedTechUniques = researchedTechUniques.withItem(unique)
            UniqueTriggerActivation.triggerCivwideUnique(unique, civInfo)
        }
        updateTransientBooleans()

        civInfo.addNotification("Research of [$techName] has completed!", TechAction(techName), NotificationIcon.Science, techName)
        civInfo.popupAlerts.add(PopupAlert(AlertType.TechResearched, techName))

        val currentEra = civInfo.getEra()
        if (previousEra != currentEra) {
            civInfo.addNotification("You have entered the [$currentEra]!", NotificationIcon.Science)
            if (civInfo.isMajorCiv()) {
                for (knownCiv in civInfo.getKnownCivs()) {
                    knownCiv.addNotification("[${civInfo.civName}] has entered the [$currentEra]!", civInfo.civName, NotificationIcon.Science)
                }
            }
            for (it in getRuleset().policyBranches.values.filter { it.era == currentEra && civInfo.policies.isAdoptable(it) }) {
                civInfo.addNotification("[" + it.name + "] policy branch unlocked!", NotificationIcon.Culture)
            }
        }

        if (civInfo.playerType == PlayerType.Human) notifyRevealedResources(techName)

        val obsoleteUnits = getRuleset().units.values.filter { it.obsoleteTech == techName }.map { it.name }
        val unitUpgrades = HashMap<String, ArrayList<CityInfo>>()
        for (city in civInfo.cities) {
            // Do not use replaceAll - that's a Java 8 feature and will fail on older phones!
            val oldQueue = city.cityConstructions.constructionQueue.toList()  // copy, since we're changing the queue
            city.cityConstructions.constructionQueue.clear()
            for (constructionName in oldQueue) {
                if (constructionName in obsoleteUnits) {
                    if (constructionName !in unitUpgrades.keys) {
                        unitUpgrades[constructionName] = ArrayList<CityInfo>()
                    }
                    unitUpgrades[constructionName]?.add(city)
                    val construction = city.cityConstructions.getConstruction(constructionName)
                    if (construction is BaseUnit && construction.upgradesTo != null) {
                        city.cityConstructions.constructionQueue.add(construction.upgradesTo!!)
                    }
                } else city.cityConstructions.constructionQueue.add(constructionName)
            }
        }

        // Add notifications for obsolete units/constructions
        for ((unit, cities) in unitUpgrades) {
            val construction = cities[0].cityConstructions.getConstruction(unit)
            if (cities.size == 1) {
                val city = cities[0]
                if (construction is BaseUnit && construction.upgradesTo != null) {
                    val text = "[${city.name}] changed production from [$unit] to [${construction.upgradesTo!!}]"
                    civInfo.addNotification(text, city.location, unit, NotificationIcon.Construction, construction.upgradesTo!!)
                } else {
                    val text = "[$unit] has become obsolete and was removed from the queue in [${city.name}]!"
                    civInfo.addNotification(text, city.location, NotificationIcon.Construction)
                }
            } else {
                val locationAction = LocationAction(cities.map { it.location })
                if (construction is BaseUnit && construction.upgradesTo != null) {
                    val text = "[${cities.size}] cities changed production from [$unit] to [${construction.upgradesTo!!}]"
                    civInfo.addNotification(text, locationAction, unit, NotificationIcon.Construction, construction.upgradesTo!!)
                } else {
                    val text = "[$unit] has become osbolete and was removed from the queue in [${cities.size}] cities!"
                    civInfo.addNotification(text, locationAction, NotificationIcon.Construction)
                }
            }
        }

        for (unique in civInfo.getMatchingUniques("Receive free [] when you discover []")) {
            if (unique.params[1] != techName) continue
            civInfo.addUnit(unique.params[0])
        }
    }

    private fun notifyRevealedResources(techName: String) {
        data class CityTileAndDistance(val city: CityInfo, val tile: TileInfo, val distance: Int)

        for (revealedResource in getRuleset().tileResources.values.filter { techName == it.revealedBy }) {
            val revealedName = revealedResource.name

            val visibleRevealTiles = civInfo.viewableTiles.asSequence()
                .filter { it.resource == revealedName }
                .flatMap { tile -> civInfo.cities.asSequence()
                    .map {
                        // build a full cross join all revealed tiles * civ's cities (should rarely surpass a few hundred)
                        // cache distance for each pair as sort will call it ~ 2n log n times
                        // should still be cheaper than looking up 'the' closest city per reveal tile before sorting
                        city -> CityTileAndDistance(city, tile, tile.aerialDistanceTo(city.getCenterTile()))
                    }
                }
                .filter { it.distance <= 5 && (it.tile.getOwner() == null || it.tile.getOwner() == civInfo) }
                .sortedWith ( compareBy { it.distance } )
                .distinctBy { it.tile }

            val chosenCity = visibleRevealTiles.firstOrNull()?.city ?: continue
            val positions = visibleRevealTiles
                // re-sort to a more pleasant display order
                .sortedWith(compareBy{ it.tile.aerialDistanceTo(chosenCity.getCenterTile()) })
                .map { it.tile.position }
                .toList()       // explicit materialization of sequence to satisfy addNotification overload

            val text =  if(positions.size==1)
                "[$revealedName] revealed near [${chosenCity.name}]"
            else
                "[${positions.size}] sources of [$revealedName] revealed, e.g. near [${chosenCity.name}]"

            civInfo.addNotification(
                text,
                LocationAction(positions),
                "ResourceIcons/$revealedName"
            )
        }
    }

    fun setTransients() {
        researchedTechnologies.addAll(techsResearched.map { getRuleset().technologies[it]!! })
        researchedTechUniques.addAll(researchedTechnologies.asSequence().flatMap { it.uniqueObjects.asSequence() })
        updateTransientBooleans()
        movementSpeedOnRoads = if (civInfo.hasUnique("Improves movement speed on roads"))
            RoadStatus.Road.movementImproved else RoadStatus.Road.movement
    }

    private fun updateTransientBooleans() {
        wayfinding = civInfo.hasUnique("Can embark and move over Coasts and Oceans immediately")
        unitsCanEmbark = wayfinding || civInfo.hasUnique("Enables embarkation for land units")

        embarkedUnitsCanEnterOcean = wayfinding || civInfo.hasUnique("Enables embarked units to enter ocean tiles")
        roadsConnectAcrossRivers = civInfo.hasUnique("Roads connect tiles across rivers")
    }

    fun getBestRoadAvailable(): RoadStatus {
        val roadImprovement = RoadStatus.Road.improvement(getRuleset()) // May not exist in mods
        if (roadImprovement == null || !isResearched(roadImprovement.techRequired!!)) return RoadStatus.None

        val railroadImprovement = RoadStatus.Railroad.improvement(getRuleset())
        val canBuildRailroad = railroadImprovement != null && isResearched(railroadImprovement.techRequired!!)

        return if (canBuildRailroad) RoadStatus.Railroad else RoadStatus.Road
    }

    fun canResearchTech(): Boolean {
        return getRuleset().technologies.values.any { canBeResearched(it.name) }
    }
}
