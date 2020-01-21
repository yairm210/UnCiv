package com.unciv.logic.civilization


import com.badlogic.gdx.graphics.Color
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.RoadStatus
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
    @Transient lateinit var civInfo: CivilizationInfo
    @Transient var researchedTechnologies=ArrayList<Technology>()
    @Transient private var researchedTechUniques=ArrayList<String>()

    // MapUnit.canPassThrough is the most called function in the game, and having these extremey specific booleans is or way of improving the time cost
    @Transient var unitsCanEmbark=false
    @Transient var embarkedUnitsCanEnterOcean=false

    // UnitMovementAlgorithms.getMovementCostBetweenAdjacentTiles is a close second =)
    @Transient var movementSpeedOnRoadsImproved=false

    var freeTechs = 0
    /** For calculating Great Scientist yields - see https://civilization.fandom.com/wiki/Great_Scientist_(Civ5)  */
    var scienceOfLast8Turns = IntArray(8){0}
    var techsResearched = HashSet<String>()
    /** When moving towards a certain tech, the user doesn't have to manually pick every one. */
    var techsToResearch = ArrayList<String>()
    private var techsInProgress = HashMap<String, Int>()
    var overflowScience = 0

    //region state-changing functions
    fun clone(): TechManager {
        val toReturn = TechManager()
        toReturn.techsResearched.addAll(techsResearched)
        toReturn.freeTechs=freeTechs
        toReturn.techsInProgress.putAll(techsInProgress)
        toReturn.techsToResearch.addAll(techsToResearch)
        toReturn.scienceOfLast8Turns=scienceOfLast8Turns.clone()
        toReturn.overflowScience=overflowScience
        return toReturn
    }

    fun getRuleset() = civInfo.gameInfo.ruleSet

    fun costOfTech(techName: String): Int {
        var techCost = getRuleset().technologies[techName]!!.cost.toFloat()
        if (civInfo.isPlayerCivilization())
            techCost *= civInfo.getDifficulty().researchCostModifier
        techCost *= civInfo.gameInfo.gameParameters.gameSpeed.getModifier()
        val techsResearchedKnownCivs = civInfo.getKnownCivs().count { it.isMajorCiv() && it.tech.isResearched(techName) }
        val undefeatedCivs = UncivGame.Current.gameInfo.civilizations.count { it.isMajorCiv() && !it.isDefeated() }
        // https://forums.civfanatics.com/threads/the-mechanics-of-overflow-inflation.517970/
        techCost /= 1 + techsResearchedKnownCivs / undefeatedCivs.toFloat() * 0.3f
        // http://www.civclub.net/bbs/forum.php?mod=viewthread&tid=123976
        val worldSizeModifier = when(civInfo.gameInfo.tileMap.mapParameters.size) {
            MapSize.Medium -> floatArrayOf(1.1f, 0.05f)
            MapSize.Large -> floatArrayOf(1.2f, 0.03f)
            MapSize.Huge -> floatArrayOf(1.3f, 0.02f)
            else -> floatArrayOf(1f, 0.05f)
        }
        techCost *= worldSizeModifier[0]
        techCost *= 1 + (civInfo.cities.size -1) * worldSizeModifier[1]
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

    private fun researchOfTech(TechName: String?): Int {
        return if (techsInProgress.containsKey(TechName)) techsInProgress[TechName]!! else 0
    }

    fun remainingScienceToTech(techName: String) = costOfTech(techName) - researchOfTech(techName)

    fun turnsToTech(techName: String): Int {
        return max(1, ceil( remainingScienceToTech(techName).toDouble() / civInfo.statsForNextTurn.science).toInt())
    }

    fun isResearched(TechName: String): Boolean = techsResearched.contains(TechName)

    fun canBeResearched(TechName: String): Boolean {
        return getRuleset().technologies[TechName]!!.prerequisites.all { isResearched(it) }
    }

    fun getTechUniques() = researchedTechUniques

    //endregion

    fun getRequiredTechsToDestination(destinationTech: Technology): List<String> {
        val prerequisites = Stack<Technology>()

        val checkPrerequisites = ArrayDeque<Technology>()
        checkPrerequisites.add(destinationTech)

        while (!checkPrerequisites.isEmpty()) {
            val techToCheck = checkPrerequisites.pop()!!
            // future tech can have been researched even when we're researching it,
            // so...if we skip it we'll end up with 0 techs in the "required techs", which will mean that we don't have anything to research. Yeah.
            if (techToCheck.name!=Constants.futureTech &&
                    (isResearched(techToCheck.name) || prerequisites.contains(techToCheck)) )
                continue //no need to add or check prerequisites
            for (prerequisite in techToCheck.prerequisites)
                checkPrerequisites.add(getRuleset().technologies[prerequisite]!!)
            prerequisites.add(techToCheck)
        }

        return prerequisites.sortedBy { it.column!!.columnNumber }.map { it.name }
    }

    fun getScienceFromGreatScientist(): Int {
        // https://civilization.fandom.com/wiki/Great_Scientist_(Civ5)
        return (scienceOfLast8Turns.sum() * civInfo.gameInfo.gameParameters.gameSpeed.getModifier()).toInt()
    }

    fun addCurrentScienceToScienceOfLast8Turns() {
        // The Science the Great Scientist generates does not include Science from Policies, Trade routes and City States.
        var allCitiesScience = 0f
        civInfo.cities.forEach{ it ->
            val totalBaseScience= it.cityStats.baseStatList.values.map { it.science }.sum()
            val totalBonusPercents= it.cityStats.statPercentBonusList.filter { it.key!="Policies" }.values.map { it.science }.sum()
            allCitiesScience += totalBaseScience*(1+totalBonusPercents/100)
        }
        scienceOfLast8Turns[civInfo.gameInfo.turns%8] = allCitiesScience.toInt()
    }

    fun hurryResearch() {
        val currentTechnology = currentTechnologyName()
        if (currentTechnology == null) return
        techsInProgress[currentTechnology] = researchOfTech(currentTechnology) + getScienceFromGreatScientist()
        if (techsInProgress[currentTechnology]!! < costOfTech(currentTechnology))
            return

        // We finished it!
        // http://www.civclub.net/bbs/forum.php?mod=viewthread&tid=123976
        val extraScienceLeftOver = techsInProgress[currentTechnology]!! - costOfTech(currentTechnology)
        overflowScience += limitOverflowScience(extraScienceLeftOver)
        addTechnology(currentTechnology)
    }

    fun limitOverflowScience(overflowscience: Int): Int {
        // http://www.civclub.net/bbs/forum.php?mod=viewthread&tid=123976
        // Apparently yes, we care about the absolute tech cost, not the actual calculated-for-this-player tech cost,
        //  so don't change to costOfTech()
        return min(overflowscience, max(civInfo.statsForNextTurn.science.toInt() * 5,
                getRuleset().technologies[currentTechnologyName()]!!.cost))
    }

    fun nextTurn(scienceForNewTurn: Int) {
        addCurrentScienceToScienceOfLast8Turns()
        val currentTechnology = currentTechnologyName()
        if (currentTechnology == null) return
        techsInProgress[currentTechnology] = researchOfTech(currentTechnology) + scienceForNewTurn
        if (overflowScience != 0){ // https://forums.civfanatics.com/threads/the-mechanics-of-overflow-inflation.517970/
            val techsResearchedKnownCivs = civInfo.getKnownCivs().count { it.isMajorCiv() && it.tech.isResearched(currentTechnologyName()!!) }
            val undefeatedCivs = UncivGame.Current.gameInfo.civilizations.count { it.isMajorCiv() && !it.isDefeated() }
            techsInProgress[currentTechnology] = techsInProgress[currentTechnology]!! + ((1 + techsResearchedKnownCivs / undefeatedCivs.toFloat() * 0.3f)* overflowScience).toInt()
            overflowScience = 0
        }
        if (techsInProgress[currentTechnology]!! < costOfTech(currentTechnology))
            return

        // We finished it!
        val overflowscience = techsInProgress[currentTechnology]!! - costOfTech(currentTechnology)
        overflowScience = limitOverflowScience(overflowscience)
        addTechnology(currentTechnology)
    }

    fun getFreeTechnology(techName:String){
        freeTechs--
        addTechnology(techName)
    }

    fun addTechnology(techName:String) {
        if(techName!= Constants.futureTech)
            techsToResearch.remove(techName)
        techsInProgress.remove(techName)

        val previousEra = civInfo.getEra()
        techsResearched.add(techName)

        // this is to avoid concurrent modification problems
        val newTech = getRuleset().technologies[techName]!!
        researchedTechnologies = researchedTechnologies.withItem(newTech)
        for(unique in newTech.uniques)
            researchedTechUniques = researchedTechUniques.withItem(unique)
        updateTransientBooleans()

        civInfo.addNotification("Research of [$techName] has completed!", Color.BLUE, TechAction(techName))
        civInfo.popupAlerts.add(PopupAlert(AlertType.TechResearched,techName))

        val currentEra = civInfo.getEra()
        if (previousEra < currentEra) {
            civInfo.addNotification("You have entered the [$currentEra era]!", null, Color.GOLD)
            civInfo.getKnownCivs().forEach {
                it.addNotification("[${civInfo.civName}] has entered the [$currentEra era]!", null, Color.BLUE)
            }
            getRuleset().policyBranches.values.filter { it.era == currentEra }
                    .forEach { civInfo.addNotification("[" + it.name + "] policy branch unlocked!", null, Color.PURPLE) }
        }

        for(revealedResource in getRuleset().tileResources.values.filter{ techName == it.revealedBy }){
            for (tileInfo in civInfo.gameInfo.tileMap.values
                    .filter { it.resource == revealedResource.name && civInfo == it.getOwner() }) {

                val closestCityTile = tileInfo.getTilesInDistance(4)
                        .firstOrNull { it.isCityCenter() }
                if (closestCityTile != null) {
                    val text = "[${revealedResource.name}] revealed near [${closestCityTile.getCity()!!.name}]"
                    civInfo.addNotification(text, tileInfo.position, Color.BLUE)
                    break
                }
            }
        }

        val obsoleteUnits = getRuleset().units.values.filter { it.obsoleteTech == techName }
        for (city in civInfo.cities)
            if (city.cityConstructions.getCurrentConstruction() in obsoleteUnits) {
                val currentConstructionUnit = city.cityConstructions.getCurrentConstruction() as BaseUnit
                city.cityConstructions.currentConstruction = currentConstructionUnit.upgradesTo!!
            }

        if(techName=="Writing" && civInfo.nation.unique=="Receive free Great Scientist when you discover Writing, Earn Great Scientists 50% faster"
                && civInfo.cities.any())
            civInfo.addGreatPerson("Great Scientist")
    }

    fun setTransients(){
        // As of 2.10.16, removed mass media, since our tech tree is like G&K
        techsResearched.remove("Mass Media")
        techsToResearch.remove("Mass Media")
        techsInProgress.remove("Mass Media")

        // As of 2.13.15, "Replacable parts" is renamed to "Replaceable Parts"
        val badTechName = "Replacable Parts"
        val goodTechName = "Replaceable Parts"
        if(techsResearched.contains(badTechName)){
            techsResearched.remove(badTechName)
            techsResearched.add(goodTechName)
        }
        if(techsInProgress.containsKey(badTechName)){
            techsInProgress[goodTechName] = techsInProgress[badTechName]!!
            techsInProgress.remove(badTechName)
        }
        if(techsToResearch.contains(badTechName)){
            val newTechToReseach= ArrayList<String>()
            for(tech in techsToResearch)
                newTechToReseach.add(if(tech!=badTechName) tech else goodTechName)
            techsToResearch = newTechToReseach
        }

        researchedTechnologies.addAll(techsResearched.map { getRuleset().technologies[it]!! })
        researchedTechUniques.addAll(researchedTechnologies.flatMap { it.uniques })
        updateTransientBooleans()
    }

    fun updateTransientBooleans(){
        if(researchedTechUniques.contains("Enables embarkation for land units")
                || civInfo.nation.unique=="Can embark and move over Coasts and Oceans immediately. +1 Sight when embarked. +10% Combat Strength bonus if within 2 tiles of a Moai.")
            unitsCanEmbark=true

        if(researchedTechUniques.contains("Enables embarked units to enter ocean tiles")
                || civInfo.nation.unique=="Can embark and move over Coasts and Oceans immediately. +1 Sight when embarked. +10% Combat Strength bonus if within 2 tiles of a Moai.")
            embarkedUnitsCanEnterOcean=true

        if(researchedTechUniques.contains("Improves movement speed on roads")) movementSpeedOnRoadsImproved = true
    }

    fun getBestRoadAvailable(): RoadStatus {
        if (!isResearched(RoadStatus.Road.improvement(getRuleset())!!.techRequired!!)) return RoadStatus.None

        val techEnablingRailroad = RoadStatus.Railroad.improvement(getRuleset())!!.techRequired!!
        val canBuildRailroad = isResearched(techEnablingRailroad)

        return if (canBuildRailroad) RoadStatus.Railroad else RoadStatus.Road
    }
}