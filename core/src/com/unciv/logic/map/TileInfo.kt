package com.unciv.logic.map

import com.badlogic.gdx.math.Vector2
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.HexMath
import com.unciv.logic.city.CityInfo
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.tile.*
import com.unciv.models.stats.Stats
import com.unciv.models.translations.tr
import com.unciv.ui.civilopedia.FormattedLine
import com.unciv.ui.utils.Fonts
import kotlin.math.abs
import kotlin.math.min

open class TileInfo {
    @Transient
    lateinit var tileMap: TileMap

    @Transient
    lateinit var ruleset: Ruleset  // a tile can be a tile with a ruleset, even without a map.

    @Transient
    var owningCity: CityInfo? = null

    @Transient
    private lateinit var baseTerrainObject: Terrain

    // These are for performance - checked with every tile movement and "canEnter" check, which makes them performance-critical
    @Transient
    var isLand = false

    @Transient
    var isWater = false

    @Transient
    var isOcean = false

    // This will be called often - farm can be built on Hill and tundra if adjacent to fresh water
    // and farms on adjacent to fresh water tiles will have +1 additional Food after researching Civil Service
    @delegate:Transient
    val isAdjacentToFreshwater: Boolean by lazy {
        matchesTerrainFilter("River") || matchesTerrainFilter("Fresh water")
                || neighbors.any { it.matchesTerrainFilter("Fresh water") }
    }

    var militaryUnit: MapUnit? = null
    var civilianUnit: MapUnit? = null
    var airUnits = ArrayList<MapUnit>()

    var position: Vector2 = Vector2.Zero
    lateinit var baseTerrain: String
    val terrainFeatures: ArrayList<String> = ArrayList()

    // Deprecation level can't be increased because of convertTerrainFeatureToArray
    // Can't be flagged transient because it won't deserialize then
    // but it should not serialize because it always has the default value on serialization and is flagged optional
    // Can be removed together with convertTerrainFeatureToArray to drop support for save files from version 3.13.7 and below
    @Deprecated(message = "Since 3.13.7 - replaced by terrainFeatures")
    private var terrainFeature: String? = null
    private fun convertTerrainFeatureToArray() {
        if (terrainFeature != null) {
            terrainFeatures.add(terrainFeature!!)
            terrainFeature = null
        }
    }

    var naturalWonder: String? = null
    var resource: String? = null
    var improvement: String? = null
    var improvementInProgress: String? = null

    var roadStatus = RoadStatus.None
    var turnsToImprovement: Int = 0

    fun isHill() = baseTerrain == Constants.hill || terrainFeatures.contains(Constants.hill)

    var hasBottomRightRiver = false
    var hasBottomRiver = false
    var hasBottomLeftRiver = false

    val latitude: Float
        get() = HexMath.getLatitude(position)
    val longitude: Float
        get() = HexMath.getLongitude(position)

    fun clone(): TileInfo {
        val toReturn = TileInfo()
        if (militaryUnit != null) toReturn.militaryUnit = militaryUnit!!.clone()
        if (civilianUnit != null) toReturn.civilianUnit = civilianUnit!!.clone()
        for (airUnit in airUnits) toReturn.airUnits.add(airUnit.clone())
        toReturn.position = position.cpy()
        toReturn.baseTerrain = baseTerrain
        convertTerrainFeatureToArray()
        toReturn.terrainFeatures.addAll(terrainFeatures)
        toReturn.naturalWonder = naturalWonder
        toReturn.resource = resource
        toReturn.improvement = improvement
        toReturn.improvementInProgress = improvementInProgress
        toReturn.roadStatus = roadStatus
        toReturn.turnsToImprovement = turnsToImprovement
        toReturn.hasBottomLeftRiver = hasBottomLeftRiver
        toReturn.hasBottomRightRiver = hasBottomRightRiver
        toReturn.hasBottomRiver = hasBottomRiver
        return toReturn
    }

    fun containsGreatImprovement(): Boolean {
        return getTileImprovement()?.isGreatImprovement() == true
    }

    fun containsUnfinishedGreatImprovement(): Boolean {
        if (improvementInProgress == null) return false
        return ruleset.tileImprovements[improvementInProgress!!]!!.isGreatImprovement()
    }
    //region pure functions

    /** Returns military, civilian and air units in tile */
    fun getUnits() = sequence {
        if (militaryUnit != null) yield(militaryUnit!!)
        if (civilianUnit != null) yield(civilianUnit!!)
        if (airUnits.isNotEmpty()) yieldAll(airUnits)
    }

    /** This is for performance reasons of canPassThrough() - faster than getUnits().firstOrNull() */
    fun getFirstUnit(): MapUnit? {
        if (militaryUnit != null) return militaryUnit!!
        if (civilianUnit != null) return civilianUnit!!
        if (airUnits.isNotEmpty()) return airUnits.first()
        return null
    }

    fun getCity(): CityInfo? = owningCity

    fun getLastTerrain(): Terrain = when {
        terrainFeatures.isNotEmpty() -> getTerrainFeatures().last()
        naturalWonder != null -> getNaturalWonder()
        else -> getBaseTerrain()
    }

    fun getTileResource(): TileResource =
            if (resource == null) throw Exception("No resource exists for this tile!")
            else if (!ruleset.tileResources.containsKey(resource!!)) throw Exception("Resource $resource does not exist in this ruleset!")
            else ruleset.tileResources[resource!!]!!

    private fun getNaturalWonder(): Terrain =
            if (naturalWonder == null) throw Exception("No natural wonder exists for this tile!")
            else ruleset.terrains[naturalWonder!!]!!

    fun isCityCenter(): Boolean = getCity()?.location == position
    fun isNaturalWonder(): Boolean = naturalWonder != null
    fun isImpassible() = getLastTerrain().impassable

    fun getTileImprovement(): TileImprovement? = if (improvement == null) null else ruleset.tileImprovements[improvement!!]
    fun getTileImprovementInProgress(): TileImprovement? = if (improvementInProgress == null) null else ruleset.tileImprovements[improvementInProgress!!]


    // This is for performance - since we access the neighbors of a tile ALL THE TIME,
    // and the neighbors of a tile never change, it's much more efficient to save the list once and for all!
    @delegate:Transient
    val neighbors: Sequence<TileInfo> by lazy { getTilesAtDistance(1).toList().asSequence() }
    // We have to .toList() so that the values are stored together once for caching,
    // and the toSequence so that aggregations (like neighbors.flatMap{it.units} don't take up their own space

    fun getHeight(): Int {
        return getAllTerrains().flatMap { it.uniqueObjects }
            .filter { it.placeholderText == "Has an elevation of [] for visibility calculations" }
            .map { it.params[0].toInt() }.sum()
    }

    fun getBaseTerrain(): Terrain = baseTerrainObject

    fun getOwner(): CivilizationInfo? {
        val containingCity = getCity() ?: return null
        return containingCity.civInfo
    }

    fun isFriendlyTerritory(civInfo: CivilizationInfo): Boolean {
        val tileOwner = getOwner()
        return when {
            tileOwner == null -> false
            tileOwner == civInfo -> true
            !civInfo.knows(tileOwner) -> false
            else -> tileOwner.getDiplomacyManager(civInfo).isConsideredFriendlyTerritory()
        }
    }

    fun getTerrainFeatures(): List<Terrain> = terrainFeatures.mapNotNull { ruleset.terrains[it] }
    fun getAllTerrains(): Sequence<Terrain> = sequence {
        yield(baseTerrainObject)
        if (naturalWonder != null) yield(getNaturalWonder())
        yieldAll(terrainFeatures.asSequence().mapNotNull { ruleset.terrains[it] })
    }

    fun isRoughTerrain() = getAllTerrains().any{ it.isRough() }

    fun hasUnique(unique: String) = getAllTerrains().any { it.uniques.contains(unique) }

    fun getWorkingCity(): CityInfo? {
        val civInfo = getOwner() ?: return null
        return civInfo.cities.firstOrNull { it.isWorked(this) }
    }

    fun isWorked(): Boolean = getWorkingCity() != null
    fun providesYield() = getCity() != null && (isCityCenter() || isWorked()
            || getTileImprovement()?.hasUnique("Tile provides yield without assigned population") == true)

    fun isLocked(): Boolean {
        val workingCity = getWorkingCity()
        return workingCity != null && workingCity.lockedTiles.contains(position)
    }

    fun getTileStats(observingCiv: CivilizationInfo): Stats = getTileStats(getCity(), observingCiv)

    fun getTileStats(city: CityInfo?, observingCiv: CivilizationInfo): Stats {
        var stats = getBaseTerrain().clone()

        for (terrainFeatureBase in getTerrainFeatures()) {
            if (terrainFeatureBase.overrideStats)
                stats = terrainFeatureBase.clone()
            else
                stats.add(terrainFeatureBase)
        }

        if (city != null) {
            var tileUniques = city.getMatchingUniques("[] from [] tiles []")
                .filter { city.matchesFilter(it.params[2]) }
            // Deprecated since 3.15.9
                tileUniques += city.getLocalMatchingUniques("[] from [] tiles in this city")
            //
            tileUniques += city.getMatchingUniques("[] from every []")
            for (unique in tileUniques) {
                val tileType = unique.params[1]
                if (tileType == improvement) continue // This is added to the calculation in getImprovementStats. we don't want to add it twice
                if (matchesTerrainFilter(tileType, observingCiv)) 
                    stats.add(unique.stats)
            }
            
            for (unique in city.getMatchingUniques("[] from [] tiles without [] []")) 
                if (
                    matchesTerrainFilter(unique.params[1]) &&
                    !matchesTerrainFilter(unique.params[2]) &&
                    city.matchesFilter(unique.params[3])
                )
                    stats.add(unique.stats)
        }

        if (naturalWonder != null) {
            val wonder = getNaturalWonder()
            stats.add(wonder)

            // Spain doubles tile yield
            if (city != null && city.civInfo.hasUnique("Tile yields from Natural Wonders doubled")) {
                stats.add(wonder)
            }
        }
        // resource base
        if (hasViewableResource(observingCiv)) stats.add(getTileResource())

        val improvement = getTileImprovement()
        if (improvement != null)
            stats.add(getImprovementStats(improvement, observingCiv, city))

        if (isCityCenter()) {
            if (stats.food < 2) stats.food = 2f
            if (stats.production < 1) stats.production = 1f
        }

        if (isAdjacentToRiver()) stats.gold++

        if (stats.gold != 0f && observingCiv.goldenAges.isGoldenAge())
            stats.gold++

        if (stats.production < 0) stats.production = 0f

        return stats
    }

    fun getImprovementStats(improvement: TileImprovement, observingCiv: CivilizationInfo, city: CityInfo?): Stats {
        val stats = improvement.clone() // clones the stats of the improvement, not the improvement itself
        if (hasViewableResource(observingCiv) && getTileResource().improvement == improvement.name)
            stats.add(getTileResource().improvementStats!!.clone()) // resource-specific improvement

        for (unique in improvement.uniqueObjects)
            if (unique.placeholderText == "[] once [] is discovered" && observingCiv.tech.isResearched(unique.params[1]))
                stats.add(unique.stats)

        if (city != null) {
            var tileUniques = city.getMatchingUniques("[] from [] tiles []")
                .filter { city.matchesFilter(it.params[2]) }
            // Deprecated since 3.15.9
                tileUniques += city.getLocalMatchingUniques("[] from [] tiles in this city")
            //
            val improvementUniques = improvement.uniqueObjects.filter {
                it.placeholderText == "[] on [] tiles once [] is discovered"
                        && observingCiv.tech.isResearched(it.params[2])
            }
            for (unique in tileUniques + improvementUniques) {
                if (improvement.matchesFilter(unique.params[1])
                    // Freshwater and non-freshwater cannot be moved to matchesUniqueFilter since that creates an endless feedback.
                    // If you're attempting that, check that it works!
                    || unique.params[1] == "Fresh water" && isAdjacentToFreshwater
                    || unique.params[1] == "non-fresh water" && !isAdjacentToFreshwater)
                        stats.add(unique.stats)
            }

            for (unique in city.getMatchingUniques("[] from every []")) {
                if (improvement.matchesFilter(unique.params[1])) {
                    stats.add(unique.stats)
                }
            }
        }

        for (unique in improvement.uniqueObjects)
            if (unique.placeholderText == "[] for each adjacent []") {
                val adjacent = unique.params[1]
                val numberOfBonuses = neighbors.count {
                    it.matchesFilter(adjacent, observingCiv)
                        || it.roadStatus.name == adjacent
                }
                stats.add(unique.stats.times(numberOfBonuses.toFloat()))
            }

        for (unique in observingCiv.getMatchingUniques("+[]% yield from every []"))
            if (improvement.matchesFilter(unique.params[1]))
                stats.timesInPlace(1f + unique.params[0].toFloat() / 100f)

        // Deprecated since 3.15
            if (containsGreatImprovement() && observingCiv.hasUnique("Tile yield from Great Improvements +100%"))
                stats.timesInPlace(2f)
        //

        return stats
    }

    /** Returns true if the [improvement] can be built on this [TileInfo] */
    fun canBuildImprovement(improvement: TileImprovement, civInfo: CivilizationInfo): Boolean {
        return when {
            improvement.uniqueTo != null && improvement.uniqueTo != civInfo.civName -> false
            improvement.techRequired != null && !civInfo.tech.isResearched(improvement.techRequired!!) -> false
            getOwner() != civInfo && !(
                    improvement.hasUnique("Can be built outside your borders")
                            // citadel can be built only next to or within own borders
                            || improvement.hasUnique("Can be built just outside your borders")
                                && neighbors.any { it.getOwner() == civInfo } && civInfo.cities.isNotEmpty()
                    ) -> false
            improvement.uniqueObjects.any {
                it.placeholderText == "Obsolete with []" && civInfo.tech.isResearched(it.params[0])
            } -> return false
            else -> canImprovementBeBuiltHere(improvement, hasViewableResource(civInfo))
        }
    }

    /** Without regards to what CivInfo it is, a lot of the checks are just for the improvement on the tile.
     *  Doubles as a check for the map editor.
     */
    private fun canImprovementBeBuiltHere(improvement: TileImprovement, resourceIsVisible: Boolean = resource != null): Boolean {
        val topTerrain = getLastTerrain()

        return when {
            improvement.name == this.improvement -> false
            isCityCenter() -> false
            "Cannot be built on bonus resource" in improvement.uniques && resource != null
                    && getTileResource().resourceType == ResourceType.Bonus -> false
            improvement.uniqueObjects.filter { it.placeholderText == "Cannot be built on [] tiles" }.any {
                    unique -> matchesTerrainFilter(unique.params[0])
            } -> false

            // Road improvements can change on tiles with irremovable improvements - nothing else can, though.
            RoadStatus.values().none { it.name == improvement.name || it.removeAction == improvement.name }
                    && getTileImprovement().let { it != null && it.hasUnique("Irremovable") } -> false

            // Decide cancelImprovementOrder earlier, otherwise next check breaks it
            improvement.name == Constants.cancelImprovementOrder -> (this.improvementInProgress != null)
            // Tiles with no terrains, and no turns to build, are like great improvements - they're placeable
            improvement.terrainsCanBeBuiltOn.isEmpty() && improvement.turnsToBuild == 0 && isLand -> true
            improvement.terrainsCanBeBuiltOn.contains(topTerrain.name) -> true
            improvement.uniqueObjects.filter { it.placeholderText == "Must be next to []" }.any {
                val filter = it.params[0]
                if (filter == "River") return@any !isAdjacentToRiver()
                else return@any !neighbors.any { neighbor -> neighbor.matchesFilter(filter) }
            } -> false
            !isWater && RoadStatus.values().any { it.name == improvement.name && it > roadStatus } -> true
            improvement.name == roadStatus.removeAction -> true
            topTerrain.unbuildable && !improvement.isAllowedOnFeature(topTerrain.name) -> false
            // DO NOT reverse this &&. isAdjacentToFreshwater() is a lazy which calls a function, and reversing it breaks the tests.
            improvement.hasUnique("Can also be built on tiles adjacent to fresh water") && isAdjacentToFreshwater -> true
            "Can only be built on Coastal tiles" in improvement.uniques && isCoastalTile() -> true
            improvement.uniqueObjects.filter { it.placeholderText == "Can only be built on [] tiles" }.any {
                unique -> !matchesTerrainFilter(unique.params[0])
            } -> false
            else -> resourceIsVisible && getTileResource().improvement == improvement.name
        }
    }

    /**
     * Implementation of _`tileFilter`_
     * @see <a href="https://github.com/yairm210/Unciv/wiki/uniques#user-content-tilefilter">tileFilter</a>
     */
    fun matchesFilter(filter: String, civInfo: CivilizationInfo? = null): Boolean {
        if (matchesTerrainFilter(filter, civInfo)) return true
        if (improvement != null && ruleset.tileImprovements[improvement]!!.matchesFilter(filter)) return true
        return false
    }

    fun matchesTerrainFilter(filter: String, observingCiv: CivilizationInfo? = null): Boolean {
        return when (filter) {
            "All" -> true
            baseTerrain -> true
            "Water" -> isWater
            "Land" -> isLand
            "Coastal" -> isCoastalTile()
            "River" -> isAdjacentToRiver()
            naturalWonder -> true
            "Open terrain" -> !isRoughTerrain()
            "Rough terrain" -> isRoughTerrain()
            "Foreign Land", "Foreign" -> observingCiv != null && !isFriendlyTerritory(observingCiv)
            "Friendly Land", "Friendly" -> observingCiv != null && isFriendlyTerritory(observingCiv)
            resource -> observingCiv != null && hasViewableResource(observingCiv)
            "Water resource" -> isWater && observingCiv != null && hasViewableResource(observingCiv)
            "Natural Wonder" -> naturalWonder != null
            else -> {
                if (terrainFeatures.contains(filter)) return true
                if (hasUnique(filter)) return true
                // Checks 'luxury resource', 'strategic resource' and 'bonus resource' - only those that are visible of course
                if (observingCiv != null && hasViewableResource(observingCiv) 
                    && getTileResource().resourceType.name + " resource" == filter) 
                        return true
                return false
            }
        }
    }

    fun hasImprovementInProgress() = improvementInProgress != null

    @delegate:Transient
    private val _isCoastalTile: Boolean by lazy { neighbors.any { it.baseTerrain == Constants.coast } }
    fun isCoastalTile() = _isCoastalTile

    fun hasViewableResource(civInfo: CivilizationInfo): Boolean =
            resource != null && (getTileResource().revealedBy == null || civInfo.tech.isResearched(getTileResource().revealedBy!!))

    fun getViewableTilesList(distance: Int): List<TileInfo> =
            tileMap.getViewableTiles(position, distance)

    fun getTilesInDistance(distance: Int): Sequence<TileInfo> =
            tileMap.getTilesInDistance(position, distance)

    fun getTilesInDistanceRange(range: IntRange): Sequence<TileInfo> =
            tileMap.getTilesInDistanceRange(position, range)

    fun getTilesAtDistance(distance: Int): Sequence<TileInfo> =
            tileMap.getTilesAtDistance(position, distance)

    fun getDefensiveBonus(): Float {
        var bonus = getLastTerrain().defenceBonus
        val tileImprovement = getTileImprovement()
        if (tileImprovement != null) {
            for (unique in tileImprovement.uniqueObjects)
                if (unique.placeholderText == "Gives a defensive bonus of []%")
                    bonus += unique.params[0].toFloat() / 100
        }
        return bonus
    }

    fun aerialDistanceTo(otherTile: TileInfo): Int {
        val xDelta = position.x - otherTile.position.x
        val yDelta = position.y - otherTile.position.y
        val distance = maxOf(abs(xDelta), abs(yDelta), abs(xDelta - yDelta))

        var wrappedDistance = Float.MAX_VALUE
        if (tileMap.mapParameters.worldWrap) {
            val otherTileUnwrappedPos = tileMap.getUnWrappedPosition(otherTile.position)
            val xDeltaWrapped = position.x - otherTileUnwrappedPos.x
            val yDeltaWrapped = position.y - otherTileUnwrappedPos.y
            wrappedDistance = maxOf(abs(xDeltaWrapped), abs(yDeltaWrapped), abs(xDeltaWrapped - yDeltaWrapped))
        }

        return min(distance, wrappedDistance).toInt()
    }

    /** Shows important properties of this tile for debugging _only_, it helps to see what you're doing */
    override fun toString(): String {
        val lineList = arrayListOf("TileInfo @$position")
        if (!this::baseTerrain.isInitialized) return lineList[0] + ", uninitialized"
        if (isCityCenter()) lineList += getCity()!!.name
        lineList += baseTerrain
        for (terrainFeature in terrainFeatures) lineList += terrainFeature
        if (resource != null) lineList += resource!!
        if (naturalWonder != null) lineList += naturalWonder!!
        if (roadStatus !== RoadStatus.None && !isCityCenter()) lineList += roadStatus.name
        if (improvement != null) lineList += improvement!!
        if (civilianUnit != null) lineList += civilianUnit!!.name + " - " + civilianUnit!!.civInfo.civName
        if (militaryUnit != null) lineList += militaryUnit!!.name + " - " + militaryUnit!!.civInfo.civName
        if (this::baseTerrainObject.isInitialized && isImpassible()) lineList += Constants.impassable
        return lineList.joinToString()
    }

    /** The two tiles have a river between them */
    fun isConnectedByRiver(otherTile: TileInfo): Boolean {
        if (otherTile == this) throw Exception("Should not be called to compare to self!")

        return when (tileMap.getNeighborTileClockPosition(this, otherTile)) {
            2 -> otherTile.hasBottomLeftRiver // we're to the bottom-left of it
            4 -> hasBottomRightRiver // we're to the top-left of it
            6 -> hasBottomRiver // we're directly above it
            8 -> hasBottomLeftRiver // we're to the top-right of it
            10 -> otherTile.hasBottomRightRiver // we're to the bottom-right of it
            12 -> otherTile.hasBottomRiver // we're directly below it
            else -> throw Exception("Should never call this function on a non-neighbor!")
        }
    }

    fun isAdjacentToRiver() = neighbors.any { isConnectedByRiver(it) }

    fun canCivEnter(civInfo: CivilizationInfo): Boolean {
        val tileOwner = getOwner()
        if (tileOwner == null || tileOwner == civInfo) return true
        // comparing the CivInfo objects is cheaper than comparing strings
        if (isCityCenter() && civInfo.isAtWarWith(tileOwner)
                && !getCity()!!.hasJustBeenConquered) return false
        if (!civInfo.canEnterTiles(tileOwner)
                && !(civInfo.isPlayerCivilization() && tileOwner.isCityState())) return false
        // AIs won't enter city-state's border.
        return true
    }

    fun toMarkup(viewingCiv: CivilizationInfo?): ArrayList<FormattedLine> {
        val lineList = ArrayList<FormattedLine>() // more readable than StringBuilder, with same performance for our use-case
        val isViewableToPlayer = viewingCiv == null || UncivGame.Current.viewEntireMapForDebug
                || viewingCiv.viewableTiles.contains(this)

        if (isCityCenter()) {
            val city = getCity()!!
            var cityString = city.name.tr()
            if (isViewableToPlayer) cityString += " (" + city.health + ")"
            lineList += FormattedLine(cityString)
            if (UncivGame.Current.viewEntireMapForDebug || city.civInfo == viewingCiv)
                lineList += city.cityConstructions.getProductionMarkup(ruleset)
        }
        lineList += FormattedLine(baseTerrain, link="Terrain/$baseTerrain")
        for (terrainFeature in terrainFeatures)
            lineList += FormattedLine(terrainFeature, link="Terrain/$terrainFeature")
        if (resource != null && (viewingCiv == null || hasViewableResource(viewingCiv)))
            lineList += FormattedLine(resource!!, link="Resource/$resource")
        if (naturalWonder != null)
            lineList += FormattedLine(naturalWonder!!, link="Terrain/$naturalWonder")
        if (roadStatus !== RoadStatus.None && !isCityCenter())
            lineList += FormattedLine(roadStatus.name, link="Improvement/${roadStatus.name}")
        if (improvement != null)
            lineList += FormattedLine(improvement!!, link="Improvement/$improvement")
        if (improvementInProgress != null && isViewableToPlayer) {
            val line = "{$improvementInProgress}" +
                if (turnsToImprovement > 0) " - $turnsToImprovement${Fonts.turn}" else " ({Under construction})"
            lineList += FormattedLine(line, link="Improvement/$improvementInProgress")
        }
        if (civilianUnit != null && isViewableToPlayer)
            lineList += FormattedLine(civilianUnit!!.name.tr() + " - " + civilianUnit!!.civInfo.civName.tr(),
                link="Unit/${civilianUnit!!.name}")
        if (militaryUnit != null && isViewableToPlayer) {
            val milUnitString = militaryUnit!!.name.tr() +
                (if (militaryUnit!!.health < 100) "(" + militaryUnit!!.health + ")" else "") +
                " - " + militaryUnit!!.civInfo.civName.tr()
            lineList += FormattedLine(milUnitString, link="Unit/${militaryUnit!!.name}")
        }
        val defenceBonus = getDefensiveBonus()
        if (defenceBonus != 0f) {
            var defencePercentString = (defenceBonus * 100).toInt().toString() + "%"
            if (!defencePercentString.startsWith("-")) defencePercentString = "+$defencePercentString"
            lineList += FormattedLine("[$defencePercentString] to unit defence")
        }
        if (isImpassible()) lineList += FormattedLine(Constants.impassable)

        return lineList
    }

    fun hasEnemyInvisibleUnit(viewingCiv: CivilizationInfo): Boolean {
        val unitsInTile = getUnits()
        if (unitsInTile.none()) return false
        if (unitsInTile.first().civInfo != viewingCiv &&
                unitsInTile.firstOrNull { it.isInvisible() } != null) {
            return true
        }
        return false
    }

    fun hasConnection(civInfo: CivilizationInfo) =
            roadStatus != RoadStatus.None || forestOrJungleAreRoads(civInfo)


    private fun forestOrJungleAreRoads(civInfo: CivilizationInfo) =
            civInfo.nation.forestsAndJunglesAreRoads
                    && (terrainFeatures.contains(Constants.jungle) || terrainFeatures.contains(Constants.forest))
                    && isFriendlyTerritory(civInfo)

    fun getRulesetIncompatibility(ruleset: Ruleset): HashSet<String> {
        val out = HashSet<String>()
        if (!ruleset.terrains.containsKey(baseTerrain))
            out.add("Base terrain [$baseTerrain] does not exist in ruleset!")
        for (terrainFeature in terrainFeatures.filter { !ruleset.terrains.containsKey(it) })
            out.add("Terrain feature [$terrainFeature] does not exist in ruleset!")
        if (resource != null && !ruleset.tileResources.containsKey(resource))
            out.add("Resource [$resource] does not exist in ruleset!")
        if (improvement != null && !improvement!!.startsWith("StartingLocation")
                && !ruleset.tileImprovements.containsKey(improvement))
            out.add("Improvement [$improvement] does not exist in ruleset!")
        return out
    }


    //endregion

    //region state-changing functions
    fun setTransients() {
        setTerrainTransients()
        setUnitTransients(true)
    }

    fun setTerrainTransients() {
        convertTerrainFeatureToArray()
        // Uninitialized tilemap - when you're displaying a tile in the civilopedia or map editor
        if (::tileMap.isInitialized) convertHillToTerrainFeature()
        if (!ruleset.terrains.containsKey(baseTerrain))
            throw Exception()
        baseTerrainObject = ruleset.terrains[baseTerrain]!!
        isWater = getBaseTerrain().type == TerrainType.Water
        isLand = getBaseTerrain().type == TerrainType.Land
        isOcean = baseTerrain == Constants.ocean
    }

    fun setUnitTransients(unitCivTransients: Boolean) {
        for (unit in getUnits()) {
            unit.currentTile = this
            if (unitCivTransients)
                unit.assignOwner(tileMap.gameInfo.getCivilization(unit.owner), false)
            unit.setTransients(ruleset)
        }
    }

    fun stripUnits() {
        for (unit in this.getUnits()) removeUnit(unit)
    }


    /** If the unit isn't in the ruleset we can't even know what type of unit this is! So check each place
     * This works with no transients so can be called from gameInfo.setTransients with no fear
     */
    fun removeUnit(mapUnit: MapUnit) {
        when {
            airUnits.contains(mapUnit) -> airUnits.remove(mapUnit)
            civilianUnit == mapUnit -> civilianUnit = null
            else -> militaryUnit = null
        }
    }

    fun startWorkingOnImprovement(improvement: TileImprovement, civInfo: CivilizationInfo) {
        improvementInProgress = improvement.name
        turnsToImprovement = if (civInfo.gameInfo.gameParameters.godMode) 1 else improvement.getTurnsToBuild(civInfo)
    }

    fun stopWorkingOnImprovement() {
        improvementInProgress = null
        turnsToImprovement = 0
    }

    fun normalizeToRuleset(ruleset: Ruleset) {
        if (!ruleset.terrains.containsKey(naturalWonder)) naturalWonder = null
        if (naturalWonder != null) {
            val naturalWonder = ruleset.terrains[naturalWonder]!!
            baseTerrain = naturalWonder.turnsInto!!
            terrainFeatures.clear()
            resource = null
            improvement = null
        }

        for (terrainFeature in terrainFeatures.toList()) {
            val terrainFeatureObject = ruleset.terrains[terrainFeature]
            if (terrainFeatureObject == null) {
                terrainFeatures.remove(terrainFeature)
                continue
            }

            if (terrainFeatureObject.occursOn.isNotEmpty() && !terrainFeatureObject.occursOn.contains(baseTerrain))
                terrainFeatures.remove(terrainFeature)
        }


        if (resource != null && !ruleset.tileResources.containsKey(resource)) resource = null
        if (resource != null) {
            val resourceObject = ruleset.tileResources[resource]!!
            if (resourceObject.terrainsCanBeFoundOn.none { it == baseTerrain || terrainFeatures.contains(it) })
                resource = null
        }

        // If we're checking this at gameInfo.setTransients, we can't check the top terrain
        if (improvement != null && ::baseTerrainObject.isInitialized) normalizeTileImprovement(ruleset)
        if (isWater || isImpassible())
            roadStatus = RoadStatus.None
    }


    private fun normalizeTileImprovement(ruleset: Ruleset) {
        if (improvement!!.startsWith("StartingLocation")) {
            if (!isLand || getLastTerrain().impassable) improvement = null
            return
        }
        val improvementObject = ruleset.tileImprovements[improvement]
        if (improvementObject == null) {
            improvement = null
            return
        }
        improvement = null // Unset, and check if it can be reset. If so, do it, if not, invalid.
        if (canImprovementBeBuiltHere(improvementObject)
                // Allow building 'other' improvements like city ruins, barb encampments, Great Improvements etc
                || (improvementObject.terrainsCanBeBuiltOn.isEmpty()
                        && ruleset.tileResources.values.none { it.improvement == improvementObject.name }
                        && !isImpassible() && isLand))
            improvement = improvementObject.name
    }

    private fun convertHillToTerrainFeature() {
        if (baseTerrain == Constants.hill &&
                ruleset.terrains[Constants.hill]?.type == TerrainType.TerrainFeature) {
            val mostCommonBaseTerrain = neighbors.filter { it.isLand && !it.isImpassible() }
                    .groupBy { it.baseTerrain }.maxByOrNull { it.value.size }
            baseTerrain = mostCommonBaseTerrain?.key ?: Constants.grassland
            //We have to add hill as first terrain feature
            val copy = terrainFeatures.toTypedArray()
            terrainFeatures.clear()
            terrainFeatures.add(Constants.hill)
            terrainFeatures.addAll(copy)
        }
    }

    //endregion
}
