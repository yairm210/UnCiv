package com.unciv.logic.city

import com.badlogic.gdx.math.Vector2
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.map.RoadStatus
import com.unciv.logic.map.TileInfo
import com.unciv.logic.map.TileMap
import com.unciv.models.ruleset.Unique
import com.unciv.models.ruleset.tile.ResourceSupplyList
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import com.unciv.models.stats.StatMap
import com.unciv.models.stats.Stats
import java.util.*
import kotlin.collections.HashSet
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

class CityInfo {
    @Transient
    lateinit var civInfo: CivilizationInfo

    @Transient
    private lateinit var centerTileInfo: TileInfo  // cached for better performance

    @Transient
    val range = 2

    @Transient
    lateinit var tileMap: TileMap

    @Transient
    lateinit var tilesInRange: HashSet<TileInfo>

    @Transient
    var hasJustBeenConquered = false  // this is so that military units can enter the city, even before we decide what to do with it

    var location: Vector2 = Vector2.Zero
    var id: String = UUID.randomUUID().toString()
    var name: String = ""
    var foundingCiv = ""
    var previousOwner = "" // This is so that cities in resistance that re recaptured aren't in resistance anymore
    var turnAcquired = 0
    var health = 200
    var resistanceCounter = 0

    var religion = CityInfoReligionManager()
    var population = PopulationManager()
    var cityConstructions = CityConstructions()
    var expansion = CityExpansionManager()
    var cityStats = CityStats()

    /** All tiles that this city controls */
    var tiles = HashSet<Vector2>()

    /** Tiles that have population assigned to them */
    var workedTiles = HashSet<Vector2>()

    /** Tiles that the population in them won't be reassigned */
    var lockedTiles = HashSet<Vector2>()
    var isBeingRazed = false
    var attackedThisTurn = false
    var hasSoldBuildingThisTurn = false
    var isPuppet = false

    /** The very first found city is the _original_ capital,
     * while the _current_ capital can be any other city after the original one is captured.
     * It is important to distinct them since the original cannot be razed and defines the Domination Victory. */
    var isOriginalCapital = false

    constructor()   // for json parsing, we need to have a default constructor
    constructor(civInfo: CivilizationInfo, cityLocation: Vector2) {  // new city!
        this.civInfo = civInfo
        foundingCiv = civInfo.civName
        turnAcquired = civInfo.gameInfo.turns
        this.location = cityLocation
        setTransients()

        setNewCityName(civInfo)

        isOriginalCapital = civInfo.citiesCreated == 0
        civInfo.citiesCreated++

        civInfo.cities = civInfo.cities.toMutableList().apply { add(this@CityInfo) }

        if (civInfo.cities.size == 1) cityConstructions.addBuilding(capitalCityIndicator())

        civInfo.policies.tryToAddPolicyBuildings()

        for (unique in civInfo.getMatchingUniques("Gain a free [] []")) {
            val freeBuildingName = unique.params[0]
            if (matchesFilter(unique.params[1])) {
                if (!cityConstructions.isBuilt(freeBuildingName))
                    cityConstructions.addBuilding(freeBuildingName)
            }
        }
        
        // Add buildings and pop we get from starting in this era
        val ruleset = civInfo.gameInfo.ruleSet
        val startingEra = civInfo.gameInfo.gameParameters.startingEra
        if (startingEra in ruleset.eras) {
            for (building in ruleset.eras[startingEra]!!.settlerBuildings) {
                if (ruleset.buildings[building]!!.isBuildable(cityConstructions)) {
                    cityConstructions.addBuilding(civInfo.getEquivalentBuilding(building).name)
                }
            }
        }

        expansion.reset()


        tryUpdateRoadStatus()

        val tile = getCenterTile()
        for (terrainFeature in tile.terrainFeatures.filter { getRuleset().tileImprovements.containsKey("Remove $it") })
            tile.terrainFeatures.remove(terrainFeature)

        tile.improvement = null
        tile.improvementInProgress = null

        workedTiles = hashSetOf() //reassign 1st working tile
        if (startingEra in ruleset.eras)
            population.setPopulation(ruleset.eras[startingEra]!!.settlerPopulation)
        population.autoAssignPopulation()
        cityStats.update()

        triggerCitiesSettledNearOtherCiv()
    }

    private fun setNewCityName(civInfo: CivilizationInfo) {
        val nationCities = civInfo.nation.cities
        val cityNameIndex = civInfo.citiesCreated % nationCities.size
        val cityName = nationCities[cityNameIndex]

        val cityNameRounds = civInfo.citiesCreated / nationCities.size
        if (cityNameRounds > 0 && civInfo.hasUnique("\"Borrows\" city names from other civilizations in the game")) {
            name = borrowCityName()
            return
        }
        val cityNamePrefix = when (cityNameRounds) {
            0 -> ""
            1 -> "New "
            else -> "Neo "
        }

        name = cityNamePrefix + cityName
    }
    
    private fun borrowCityName(): String {
        val usedCityNames =
            civInfo.gameInfo.civilizations.flatMap { it.cities.map { city -> city.name } }
        // We take the last unused city name for each other civ in this game, skipping civs whose
        // names are exhausted, and choose a random one from that pool if it's not empty.
        var newNames = civInfo.gameInfo.civilizations
            .filter { it.isMajorCiv() && it != civInfo }
            .mapNotNull { it.nation.cities
                .lastOrNull { city -> city !in usedCityNames } 
            }
        if (newNames.isNotEmpty()) {
            return newNames.random()
        }
        
        // As per fandom wiki, once the names from the other nations in the game are exhausted,
        // names are taken from the rest of the nations in the ruleset
        newNames = getRuleset()
            .nations
            .filter { it.key !in civInfo.gameInfo.civilizations.map { civ -> civ.nation.name } }
            .values
            .map {
                it.cities
                .filter { city -> city !in usedCityNames }
            }.flatten()
        if (newNames.isNotEmpty()) {
            return newNames.random()
        }
        // If for some reason we have used every single city name in the game,
        // (are we using some sort of baserule mod without city names?)
        // just return something so we at least have a name
        return "The City without a Name"
    }


    //region pure functions
    fun clone(): CityInfo {
        val toReturn = CityInfo()
        toReturn.location = location
        toReturn.id = id
        toReturn.name = name
        toReturn.health = health
        toReturn.population = population.clone()
        toReturn.cityConstructions = cityConstructions.clone()
        toReturn.expansion = expansion.clone()
        toReturn.tiles = tiles
        toReturn.workedTiles = workedTiles
        toReturn.lockedTiles = lockedTiles
        toReturn.isBeingRazed = isBeingRazed
        toReturn.attackedThisTurn = attackedThisTurn
        toReturn.resistanceCounter = resistanceCounter
        toReturn.foundingCiv = foundingCiv
        toReturn.turnAcquired = turnAcquired
        toReturn.isPuppet = isPuppet
        toReturn.isOriginalCapital = isOriginalCapital
        toReturn.religion = CityInfoReligionManager().apply { putAll(religion) }
        return toReturn
    }

    fun canBombard() = !attackedThisTurn && !isInResistance()
    fun getCenterTile(): TileInfo = centerTileInfo
    fun getTiles(): Sequence<TileInfo> = tiles.asSequence().map { tileMap[it] }
    fun getWorkableTiles() = tilesInRange.asSequence().filter { it.getOwner() == civInfo }
    fun isWorked(tileInfo: TileInfo) = workedTiles.contains(tileInfo.position)

    fun isCapital(): Boolean = cityConstructions.builtBuildings.contains(capitalCityIndicator())
    fun isCoastal(): Boolean = centerTileInfo.isCoastalTile()
    fun capitalCityIndicator(): String {
        val indicatorBuildings = getRuleset().buildings.values.asSequence().filter { it.uniques.contains("Indicates the capital city") }
        val civSpecificBuilding = indicatorBuildings.firstOrNull { it.uniqueTo == civInfo.civName }
        if (civSpecificBuilding != null) return civSpecificBuilding.name
        else return indicatorBuildings.first().name
    }

    fun isConnectedToCapital(connectionTypePredicate: (Set<String>) -> Boolean = { true }): Boolean {
        val mediumTypes = civInfo.citiesConnectedToCapitalToMediums[this] ?: return false
        return connectionTypePredicate(mediumTypes)
    }

    fun isInResistance() = resistanceCounter > 0


    fun getRuleset() = civInfo.gameInfo.ruleSet

    fun getCityResources(): ResourceSupplyList {
        val cityResources = ResourceSupplyList()

        for (tileInfo in getTiles().filter { it.resource != null }) {
            val resource = tileInfo.getTileResource()
            val amount = getTileResourceAmount(tileInfo) * civInfo.getResourceModifier(resource)
            if (amount > 0) cityResources.add(resource, amount, "Tiles")
        }
        for (tileInfo in getTiles()) {
            if (tileInfo.improvement == null) continue
            val tileImprovement = tileInfo.getTileImprovement()
            for (unique in tileImprovement!!.uniqueObjects)
                if (unique.placeholderText == "Provides [] []") {
                    val resource = getRuleset().tileResources[unique.params[1]] ?: continue
                    cityResources.add(resource, unique.params[0].toInt() * civInfo.getResourceModifier(resource), "Tiles")
                }
        }
        for (building in cityConstructions.getBuiltBuildings()) {
            for ((resourceName, amount) in building.getResourceRequirements()) {
                val resource = getRuleset().tileResources[resourceName]!!
                cityResources.add(resource, -amount, "Buildings")
            }
        }
        for (unique in getLocalMatchingUniques("Provides [] []")) { // E.G "Provides [1] [Iron]"
            val resource = getRuleset().tileResources[unique.params[1]]
            if (resource != null) {
                cityResources.add(resource, unique.params[0].toInt() * civInfo.getResourceModifier(resource), "Tiles")
            }
        }

        return cityResources
    }

    fun getTileResourceAmount(tileInfo: TileInfo): Int {
        if (tileInfo.resource == null) return 0
        val resource = tileInfo.getTileResource()
        if (resource.revealedBy != null && !civInfo.tech.isResearched(resource.revealedBy!!)) return 0

        // Even if the improvement exists (we conquered an enemy city or somesuch) or we have a city on it, we won't get the resource until the correct tech is researched
        if (resource.improvement != null) {
            val improvement = getRuleset().tileImprovements[resource.improvement!!]!!
            if (improvement.techRequired != null && !civInfo.tech.isResearched(improvement.techRequired!!)) return 0
        }

        if (resource.improvement == tileInfo.improvement || tileInfo.isCityCenter()
                // Per https://gaming.stackexchange.com/questions/53155/do-manufactories-and-customs-houses-sacrifice-the-strategic-or-luxury-resources
                || (resource.resourceType == ResourceType.Strategic && tileInfo.containsGreatImprovement())) {
            var amountToAdd = 1
            if (resource.resourceType == ResourceType.Strategic) {
                amountToAdd = 2
            }
            if (resource.resourceType == ResourceType.Luxury
                    && containsBuildingUnique("Provides 1 extra copy of each improved luxury resource near this City"))
                amountToAdd *= 2

            return amountToAdd
        }
        return 0
    }

    fun isGrowing() = foodForNextTurn() > 0
    fun isStarving() = foodForNextTurn() < 0

    private fun foodForNextTurn() = cityStats.currentCityStats.food.roundToInt()

    /** Take null to mean infinity. */
    fun getNumTurnsToNewPopulation(): Int? {
        if (!isGrowing()) return null
        val roundedFoodPerTurn = foodForNextTurn().toFloat()
        val remainingFood = population.getFoodToNextPopulation() - population.foodStored
        var turnsToGrowth = ceil(remainingFood / roundedFoodPerTurn).toInt()
        if (turnsToGrowth < 1) turnsToGrowth = 1
        return turnsToGrowth
    }

    /** Take null to mean infinity. */
    fun getNumTurnsToStarvation(): Int? {
        if (!isStarving()) return null
        return population.foodStored / -foodForNextTurn() + 1
    }

    fun containsBuildingUnique(unique: String) = cityConstructions.getBuiltBuildings().any { it.uniques.contains(unique) }

    fun getGreatPersonPointsForNextTurn(): StatMap {
        val stats = StatMap()
        for ((specialist, amount) in population.getNewSpecialists())
            if (getRuleset().specialists.containsKey(specialist)) // To solve problems in total remake mods
                stats.add("Specialists", getRuleset().specialists[specialist]!!.greatPersonPoints.times(amount))

        val buildingStats = Stats()
        for (building in cityConstructions.getBuiltBuildings())
            if (building.greatPersonPoints != null)
                buildingStats.add(building.greatPersonPoints!!)
        if (!buildingStats.isEmpty())
            stats["Buildings"] = buildingStats

        for (entry in stats) {
            for (unique in civInfo.getMatchingUniques("[] is earned []% faster")) {
                val unit = civInfo.gameInfo.ruleSet.units[unique.params[0]]
                if (unit == null) continue
                val greatUnitUnique = unit.uniqueObjects.firstOrNull { it.placeholderText == "Great Person - []" }
                if (greatUnitUnique == null) continue
                val statName = greatUnitUnique.params[0]
                val stat = Stat.values().firstOrNull { it.name == statName }
                // this is not very efficient, and if it causes problems we can try and think of a way of improving it
                if (stat != null) entry.value.add(stat, entry.value.get(stat) * unique.params[1].toFloat() / 100)
            }
            
            for (unique in getMatchingUniques("[]% great person generation []")) {
                if (!matchesFilter(unique.params[1])) continue
                stats[entry.key]!!.timesInPlace(1 + unique.params[0].toFloat() / 100f)
            }
            
            // Deprecated since 3.15.9
                for (unique in getMatchingUniques("+[]% great person generation in this city") 
                        + getMatchingUniques("+[]% great person generation in all cities")
                ) {
                    stats[entry.key]!!.timesInPlace(1 + unique.params[0].toFloat() / 100f)
                }
            //
            
        }

        return stats
    }

    fun getGreatPersonPoints(): Stats {
        val stats = Stats()
        for (entry in getGreatPersonPointsForNextTurn().values)
            stats.add(entry)
        return stats
    }

    internal fun getMaxHealth() = 200 + cityConstructions.getBuiltBuildings().sumBy { it.cityHealth }

    override fun toString() = name // for debug
    //endregion

    //region state-changing functions
    fun setTransients() {
        tileMap = civInfo.gameInfo.tileMap
        centerTileInfo = tileMap[location]
        tilesInRange = getCenterTile().getTilesInDistance(3).toHashSet()
        population.cityInfo = this
        expansion.cityInfo = this
        expansion.setTransients()
        cityStats.cityInfo = this
        cityConstructions.cityInfo = this
        cityConstructions.setTransients()
        religion.cityInfo = this
    }

    fun startTurn() {
        // Construct units at the beginning of the turn,
        // so they won't be generated out in the open and vulnerable to enemy attacks before you can control them
        cityConstructions.constructIfEnough()
        cityStats.update()
        tryUpdateRoadStatus()
        attackedThisTurn = false
        if (isInResistance()) {
            resistanceCounter--
            if (!isInResistance())
                civInfo.addNotification("The resistance in [$name] has ended!", location, "StatIcons/Resistance")
        }

        if (isPuppet) reassignPopulation()
    }

    fun reassignPopulation() {
        var foodWeight = 1f
        var foodPerTurn = 0f
        while (foodWeight < 3 && foodPerTurn <= 0) {
            workedTiles = hashSetOf()
            population.specialistAllocations.clear()
            for (i in 0..population.population)
                population.autoAssignPopulation(foodWeight)
            cityStats.update()

            foodPerTurn = foodForNextTurn().toFloat()
            foodWeight += 0.5f
        }
    }

    fun endTurn() {
        val stats = cityStats.currentCityStats

        cityConstructions.endTurn(stats)
        expansion.nextTurn(stats.culture)
        if (isBeingRazed) {
            val removedPopulation = 1 + civInfo.getMatchingUniques("Cities are razed [] times as fast").sumBy { it.params[0].toInt() - 1 }
            population.addPopulation(-1 * removedPopulation)
            if (population.population <= 0) { 
                civInfo.addNotification("[$name] has been razed to the ground!", location, "OtherIcons/Fire")
                destroyCity()
            } else { //if not razed yet:
                if (population.foodStored >= population.getFoodToNextPopulation()) { //if surplus in the granary...
                    population.foodStored = population.getFoodToNextPopulation() - 1 //...reduce below the new growth threshold
                }
            }
        } else population.nextTurn(foodForNextTurn())

        // if (civInfo.gameInfo.hasReligionEnabled()) religion.getAffectedBySurroundingCities()

        if (this in civInfo.cities) { // city was not destroyed
            health = min(health + 20, getMaxHealth())
            population.unassignExtraPopulation()
        }
    }

    fun destroyCity(overrideSafeties: Boolean = false) {
        // Original capitals and holy cities cannot be destroyed,
        // unless, of course, they are captured by a one-city-challenger.
        if (!canBeDestroyed() && !overrideSafeties) return
        
        for (airUnit in getCenterTile().airUnits.toList()) airUnit.destroy() //Destroy planes stationed in city

        // The relinquish ownership MUST come before removing the city,
        // because it updates the city stats which assumes there is a capital, so if you remove the capital it crashes
        getTiles().forEach { expansion.relinquishOwnership(it) }
        civInfo.cities = civInfo.cities.toMutableList().apply { remove(this@CityInfo) }
        getCenterTile().improvement = "City ruins"

        // Edge case! What if a water unit is in a city, and you raze the city?
        // Well, the water unit has to return to the water!
        for (unit in getCenterTile().getUnits()) {
            if (!unit.movement.canPassThrough(getCenterTile()))
                unit.movement.teleportToClosestMoveableTile()
        }

        if (isCapital() && civInfo.cities.isNotEmpty()) { // Move the capital if destroyed (by a nuke or by razing)
            civInfo.cities.first().cityConstructions.addBuilding(capitalCityIndicator())
        }
    }

    fun annexCity()  = CityInfoConquestFunctions(this).annexCity()

    /** This happens when we either puppet OR annex, basically whenever we conquer a city and don't liberate it */
    fun puppetCity(conqueringCiv: CivilizationInfo)  = CityInfoConquestFunctions(this).puppetCity(conqueringCiv)

    /* Liberating is returning a city to its founder - makes you LOSE warmongering points **/
    fun liberateCity(conqueringCiv: CivilizationInfo)  = CityInfoConquestFunctions(this).liberateCity(conqueringCiv)

    fun moveToCiv(newCivInfo: CivilizationInfo)  = CityInfoConquestFunctions(this).moveToCiv(newCivInfo)

    internal fun tryUpdateRoadStatus() {
        if (getCenterTile().roadStatus == RoadStatus.None) {
            val roadImprovement = RoadStatus.Road.improvement(getRuleset())
            if (roadImprovement != null && roadImprovement.techRequired in civInfo.tech.techsResearched)
                getCenterTile().roadStatus = RoadStatus.Road
        } else if (getCenterTile().roadStatus != RoadStatus.Railroad) {
            val railroadImprovement = RoadStatus.Railroad.improvement(getRuleset())
            if (railroadImprovement != null && railroadImprovement.techRequired in civInfo.tech.techsResearched)
                getCenterTile().roadStatus = RoadStatus.Railroad
        }
    }

    fun getGoldForSellingBuilding(buildingName: String) = getRuleset().buildings[buildingName]!!.cost / 10

    fun sellBuilding(buildingName: String) {
        cityConstructions.removeBuilding(buildingName)
        civInfo.addGold(getGoldForSellingBuilding(buildingName))
        hasSoldBuildingThisTurn = true

        population.unassignExtraPopulation() // If the building provided specialists, release them to other work
        population.autoAssignPopulation()
        cityStats.update()
        civInfo.updateDetailedCivResources() // this building could be a resource-requiring one
    }

    /*
     When someone settles a city within 6 tiles of another civ, this makes the AI unhappy and it starts a rolling event.
     The SettledCitiesNearUs flag gets added to the AI so it knows this happened,
        and on its turn it asks the player to stop (with a DemandToStopSettlingCitiesNear alert type)
     If the player says "whatever, I'm not promising to stop", they get a -10 modifier which gradually disappears in 40 turns
     If they DO agree, then if they keep their promise for ~100 turns they get a +10 modifier for keeping the promise,
     But if they don't keep their promise they get a -20 that will only fully disappear in 160 turns.
     There's a lot of triggering going on here.
     */
    private fun triggerCitiesSettledNearOtherCiv() {
        val citiesWithin6Tiles = civInfo.gameInfo.civilizations.filter { it.isMajorCiv() && it != civInfo }
                .flatMap { it.cities }
                .filter { it.getCenterTile().aerialDistanceTo(getCenterTile()) <= 6 }
        val civsWithCloseCities = citiesWithin6Tiles.map { it.civInfo }.distinct()
                .filter { it.knows(civInfo) && it.exploredTiles.contains(location) }
        for (otherCiv in civsWithCloseCities)
            otherCiv.getDiplomacyManager(civInfo).setFlag(DiplomacyFlags.SettledCitiesNearUs, 30)
    }

    fun canPurchase(construction: IConstruction): Boolean {
        if (construction is BaseUnit) {
            val tile = getCenterTile()
            if (construction.unitType.isCivilian())
                return tile.civilianUnit == null
            if (construction.unitType.isAirUnit() || construction.unitType.isMissile())
                return tile.airUnits.filter { !it.isTransported }.size < 6
            else return tile.militaryUnit == null
        }
        return true
    }

    fun matchesFilter(filter: String): Boolean {
        return when (filter) {
            "in this city" -> true
            "in all cities" -> true
            "in all coastal cities" -> isCoastal()
            "in capital" -> isCapital()
            "in all non-occupied cities" -> !cityStats.hasExtraAnnexUnhappiness() || isPuppet
            "in all cities with a world wonder" -> cityConstructions.getBuiltBuildings().any { it.isWonder }
            "in all cities connected to capital" -> isConnectedToCapital()
            "in all cities with a garrison" -> getCenterTile().militaryUnit != null
            else -> false
        }
    }
    
    // So everywhere in the codebase there were continuous calls to either 
    // `cityConstructions.builtBuildingUniqueMap.getUniques()` or `cityConstructions.builtBuildingMap.getAllUniques()`,
    // which was fine as long as those were the only uniques that cities could provide.
    // However, with the introduction of religion, cities might also get uniques from the religion the city follows.
    // Adding both calls to `builtBuildingsUniqueMap` and `Religion` every time is not really modular and also ugly, so something had to be done.
    // Looking at all the use cases, the following functions were written to handle all findMatchingUniques() problems.
    // Sadly, due to the large disparity between use cases, there needed to be lots of functions.


    // Finds matching uniques provided from both local and non-local sources.
    fun getMatchingUniques(
        placeholderText: String,
        // We might have this cached to avoid concurrency problems. If we don't, just get it directly
        localUniques: Sequence<Unique> = getLocalMatchingUniques(placeholderText),
    ): Sequence<Unique> {
        // The localUniques might not be filtered when passed as a parameter, so we filter it anyway
        // The time loss shouldn't be that large I don't think
        return civInfo.getMatchingUniques(placeholderText, this) +
               localUniques.filter { it.placeholderText == placeholderText }
    }
    
    // Matching uniques provided by sources in the city itself
    fun getLocalMatchingUniques(placeholderText: String): Sequence<Unique> {
        return (
            cityConstructions.builtBuildingUniqueMap.getUniques(placeholderText) +
            religion.getMatchingUniques(placeholderText)
        ).asSequence()
    }

    // Get all uniques that originate from this city
    fun getAllLocalUniques(): Sequence<Unique> {
        return cityConstructions.builtBuildingUniqueMap.getAllUniques() + religion.getUniques()
    }
    
    // Get all matching uniques that don't apply to only this city
    fun getMatchingUniquesWithNonLocalEffects(placeholderText: String): Sequence<Unique> {
        return cityConstructions.builtBuildingUniqueMap.getUniques(placeholderText).asSequence()
            .filter { it.params.none { param -> param == "in this city" } }
        // Note that we don't query religion here, as those only have local effects (for now at least)
    }
    
    // Get all uniques that don't apply to only this city
    fun getAllUniquesWithNonLocalEffects(): Sequence<Unique> {
        return cityConstructions.builtBuildingUniqueMap.getAllUniques()
            .filter { it.params.none { param -> param == "in this city" } }
        // Note that we don't query religion here, as those only have local effects (for now at least)
    }

    fun isHolyCity(): Boolean {
        return civInfo.gameInfo.religions.values.any { it.holyCityId == id } 
    }
    
    fun canBeDestroyed(): Boolean {
        return !isOriginalCapital && !isCapital() && !isHolyCity()
    }

    //endregion
}
