package com.unciv.logic.map

import com.badlogic.gdx.math.Vector2
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.city.CityInfo
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.tile.*
import com.unciv.models.stats.Stats
import com.unciv.models.translations.tr
import kotlin.math.abs

open class TileInfo {
    @Transient lateinit var tileMap: TileMap
    @Transient lateinit var ruleset: Ruleset  // a tile can be a tile with a ruleset, even without a map.
    @Transient var owningCity:CityInfo?=null
    @Transient private lateinit var baseTerrainObject:Terrain

    // These are for performance - checked with every tile movement and "canEnter" check, which makes them performance-critical
    @Transient var isLand = false
    @Transient var isWater = false
    @Transient var isOcean = false

    var militaryUnit:MapUnit?=null
    var civilianUnit:MapUnit?=null
    var airUnits=ArrayList<MapUnit>()

    var position: Vector2 = Vector2.Zero
    lateinit var baseTerrain: String
    var terrainFeature: String? = null
    var naturalWonder: String? = null
    var resource: String? = null
    var improvement: String? = null
    var improvementInProgress: String? = null

    var roadStatus = RoadStatus.None
    var turnsToImprovement: Int = 0

    var hasBottomRightRiver = false
    var hasBottomRiver = false
    var hasBottomLeftRiver = false

    fun clone(): TileInfo {
        val toReturn = TileInfo()
        if(militaryUnit!=null) toReturn.militaryUnit=militaryUnit!!.clone()
        if(civilianUnit!=null) toReturn.civilianUnit=civilianUnit!!.clone()
        for(airUnit in airUnits) toReturn.airUnits.add(airUnit.clone())
        toReturn.position=position.cpy()
        toReturn.baseTerrain=baseTerrain
        toReturn.terrainFeature=terrainFeature
        toReturn.naturalWonder=naturalWonder
        toReturn.resource=resource
        toReturn.improvement=improvement
        toReturn.improvementInProgress=improvementInProgress
        toReturn.roadStatus=roadStatus
        toReturn.turnsToImprovement=turnsToImprovement
        return toReturn
    }

    fun containsGreatImprovement(): Boolean {
        if (improvement in Constants.greatImprovements) return true
        return false
    }

    fun containsUnfinishedGreatImprovement(): Boolean {
        if (improvementInProgress in Constants.greatImprovements) return true
        return false
    }

    fun containsUnique(unique: String): Boolean {
        return isNaturalWonder() && getNaturalWonder().uniques.contains(unique)
    }
    //region pure functions

    /** Returns military, civilian and air units in tile */
    fun getUnits(): List<MapUnit> {
        if(militaryUnit==null && civilianUnit==null && airUnits.isEmpty())
            return emptyList() // for performance reasons - costs much less to initialize than an empty ArrayList or list()
        val list = ArrayList<MapUnit>(2)
        if(militaryUnit!=null) list.add(militaryUnit!!)
        if(civilianUnit!=null) list.add(civilianUnit!!)
        list.addAll(airUnits)
        return list
    }

    fun getCity(): CityInfo? = owningCity

    fun getLastTerrain(): Terrain = if (terrainFeature != null) getTerrainFeature()!! else if(naturalWonder != null) getNaturalWonder() else getBaseTerrain()

    fun getTileResource(): TileResource =
            if (resource == null) throw Exception("No resource exists for this tile!")
            else ruleset.tileResources[resource!!]!!

    fun getNaturalWonder() : Terrain =
            if (naturalWonder == null) throw Exception("No natural wonder exists for this tile!")
            else ruleset.terrains[naturalWonder!!]!!

    fun isCityCenter(): Boolean = getCity()?.location == position
    fun isNaturalWonder() : Boolean = naturalWonder != null

    fun getTileImprovement(): TileImprovement? = if (improvement == null) null else ruleset.tileImprovements[improvement!!]


    // This is for performance - since we access the neighbors of a tile ALL THE TIME,
    // and the neighbors of a tile never change, it's much more efficient to save the list once and for all!
    @Transient private var internalNeighbors : List<TileInfo>?=null
    val neighbors: List<TileInfo>
        get(){
            if(internalNeighbors==null)
                internalNeighbors = getTilesAtDistance(1)
            return internalNeighbors!!
        }

    fun getHeight(): Int {
        if (baseTerrain == Constants.mountain) return 4
        if (baseTerrain == Constants.hill) return 2
        if (terrainFeature == Constants.forest || terrainFeature == Constants.jungle) return 1
        return 0
    }

    fun getBaseTerrain(): Terrain = baseTerrainObject

    fun getOwner(): CivilizationInfo? {
        val containingCity = getCity()
        if(containingCity==null) return null
        return containingCity.civInfo
    }

    fun getTerrainFeature(): Terrain? {
        return if (terrainFeature == null) null else ruleset.terrains[terrainFeature!!]
    }

    fun isWorked(): Boolean {
        val city = getCity()
        return city!=null && city.workedTiles.contains(position)
    }

    fun getTileStats(observingCiv: CivilizationInfo): Stats {
        return getTileStats(getCity(), observingCiv)
    }

    fun getTileStats(city: CityInfo?, observingCiv: CivilizationInfo): Stats {
        var stats = getBaseTerrain().clone()

        if((baseTerrain== Constants.ocean||baseTerrain==Constants.coast) && city!=null
                && city.containsBuildingUnique("+1 food from Ocean and Coast tiles"))
            stats.food += 1

        if (terrainFeature != null) {
            val terrainFeatureBase = getTerrainFeature()
            if (terrainFeatureBase!!.overrideStats)
                stats = terrainFeatureBase.clone()
            else
                stats.add(terrainFeatureBase)

            if (terrainFeature == Constants.jungle && city != null
                    && city.containsBuildingUnique("+2 Science from each worked Jungle tile"))
                stats.science += 2f
            if (terrainFeature == "Oasis" && city != null
                    && city.containsBuildingUnique("+2 Gold for each source of Oil and oasis"))
                stats.gold += 2
            if (terrainFeature == Constants.forest && city != null
                    && city.containsBuildingUnique("+1 Production from each worked Forest tile"))
                stats.production += 1
        }

        if (naturalWonder != null) {
            val wonder = getNaturalWonder()
            stats.add(wonder)

            // Spain doubles tile yield
            if (city != null && city.civInfo.nation.unique == "100 Gold for discovering a Natural Wonder (bonus enhanced to 500 Gold if first to discover it). Culture, Happiness and tile yields from Natural Wonders doubled.") {
                stats.add(wonder)
            }
        }

        if (hasViewableResource(observingCiv)) {
            val resource = getTileResource()
            stats.add(getTileResource()) // resource base
            if (resource.building != null && city != null && city.cityConstructions.isBuilt(resource.building!!)) {
                val resourceBuilding = tileMap.gameInfo.ruleSet.buildings[resource.building!!]!!
                stats.add(resourceBuilding.resourceBonusStats!!) // resource-specific building (eg forge, stable) bonus
            }
            if(resource.resourceType==ResourceType.Strategic
                    && observingCiv.nation.unique=="Strategic Resources provide +1 Production, and Horses, Iron and Uranium Resources provide double quantity")
                stats.production+=1
            if(resource.name=="Oil" && city!=null
                    && city.containsBuildingUnique("+2 Gold for each source of Oil and oasis"))
                stats.gold += 2
            if(city!=null && isWater){
                if(city.containsBuildingUnique("+1 production from all sea resources worked by the city"))
                    stats.production+=1
                if(city.containsBuildingUnique("+1 production and gold from all sea resources worked by the city")){
                    stats.production+=1
                    stats.gold+=1
                }
            }
        }

        val improvement = getTileImprovement()
        if (improvement != null) {
            if (hasViewableResource(observingCiv) && getTileResource().improvement == improvement.name)
                stats.add(getTileResource().improvementStats!!) // resource-specific improvement
            else
                stats.add(improvement) // basic improvement

            if (improvement.improvingTech != null && observingCiv.tech.isResearched(improvement.improvingTech!!)) stats.add(improvement.improvingTechStats!!) // eg Chemistry for mines
            if (improvement.name == "Trading post" && city != null && city.civInfo.policies.isAdopted("Free Thought"))
                stats.science += 1f
            if (improvement.name == "Trading post" && city != null && city.civInfo.policies.isAdopted("Commerce Complete"))
                stats.gold += 1f
            if (containsGreatImprovement() && observingCiv.policies.isAdopted("Freedom Complete"))
                stats.add(improvement) // again, for the double effect
            if (containsGreatImprovement() && city != null && city.civInfo.nation.unique == "+2 Science for all specialists and Great Person tile improvements")
                stats.science += 2

            if(improvement.uniques.contains("+1 additional Culture for each adjacent Moai"))
                stats.culture += neighbors.count{it.improvement=="Moai"}
        }

        if(city!=null && isWater && city.containsBuildingUnique("+1 gold from worked water tiles in city"))
            stats.gold += 1

        if (isCityCenter()) {
            if (stats.food < 2) stats.food = 2f
            if (stats.production < 1) stats.production = 1f
        }

        if (stats.gold != 0f && observingCiv.goldenAges.isGoldenAge())
            stats.gold++

        if (stats.production < 0) stats.production = 0f

        return stats
    }

    fun canBuildImprovement(improvement: TileImprovement, civInfo: CivilizationInfo): Boolean {
        if (isCityCenter() || improvement.name == this.improvement) return false
        if(improvement.uniqueTo!=null && improvement.uniqueTo!=civInfo.civName) return false
        if (improvement.techRequired != null && !civInfo.tech.isResearched(improvement.techRequired!!)) return false

        val topTerrain = getLastTerrain()
        if (improvement.terrainsCanBeBuiltOn.contains(topTerrain.name)) return true

        if (improvement.name == "Road" && this.roadStatus === RoadStatus.None) return true
        if (improvement.name == "Railroad" && this.roadStatus !== RoadStatus.Railroad) return true
        if(improvement.name == "Remove Road" && this.roadStatus===RoadStatus.Road) return true
        if(improvement.name == "Remove Railroad" && this.roadStatus===RoadStatus.Railroad) return true

        if("Can only be built on Coastal tiles" in improvement.uniques && isCoastalTile()) return true
        if (topTerrain.unbuildable && !(topTerrain.name==Constants.forest && improvement.name=="Camp")) return false
        return hasViewableResource(civInfo) && getTileResource().improvement == improvement.name

    }

    fun hasImprovementInProgress() = improvementInProgress!=null

    fun isCoastalTile() = neighbors.any { it.baseTerrain==Constants.coast }

    fun hasViewableResource(civInfo: CivilizationInfo): Boolean {
        return resource != null && (getTileResource().revealedBy == null || civInfo.tech.isResearched(getTileResource().revealedBy!!))
    }

    fun getViewableTiles(distance:Int, ignoreCurrentTileHeight:Boolean = false): List<TileInfo> {
        return tileMap.getViewableTiles(this.position,distance,ignoreCurrentTileHeight)
    }

    fun getTilesInDistance(distance:Int): List<TileInfo> {
        return tileMap.getTilesInDistance(position,distance)
    }

    fun getTilesAtDistance(distance:Int): List<TileInfo> {
        return tileMap.getTilesAtDistance(position,distance)
    }

    fun getDefensiveBonus(): Float {
        var bonus = getBaseTerrain().defenceBonus
        if(terrainFeature!=null) bonus += getTerrainFeature()!!.defenceBonus
        return bonus
    }

    fun arialDistanceTo(otherTile:TileInfo): Int {
        val xDelta = position.x-otherTile.position.x
        val yDelta = position.y-otherTile.position.y
        return listOf(abs(xDelta),abs(yDelta), abs(xDelta-yDelta)).max()!!.toInt()
    }

    fun isRoughTerrain() = getBaseTerrain().rough || getTerrainFeature()?.rough == true

    fun toString(viewingCiv: CivilizationInfo): String {
        val lineList = ArrayList<String>() // more readable than StringBuilder, with same performance for our use-case
        val isViewableToPlayer = UncivGame.Current.viewEntireMapForDebug
                || viewingCiv.viewableTiles.contains(this)

        if (isCityCenter()) {
            val city = getCity()!!
            var cityString = city.name
            if(isViewableToPlayer) cityString += " ("+city.health+")"
            lineList += cityString
            if(UncivGame.Current.viewEntireMapForDebug || city.civInfo.isCurrentPlayer())
                lineList += city.cityConstructions.getProductionForTileInfo()
        }
        lineList += baseTerrain.tr()
        if (terrainFeature != null) lineList += terrainFeature!!.tr()
        if (hasViewableResource(tileMap.gameInfo.getCurrentPlayerCivilization())) lineList += resource!!.tr()
        if (naturalWonder != null) lineList += naturalWonder!!.tr()
        if (roadStatus !== RoadStatus.None && !isCityCenter()) lineList += roadStatus.toString().tr()
        if (improvement != null) lineList += improvement!!.tr()
        if (improvementInProgress != null && isViewableToPlayer)
            lineList += "{$improvementInProgress}\r\n{in} $turnsToImprovement {turns}".tr() // todo change to [] translation notation
        if (civilianUnit != null && isViewableToPlayer)
            lineList += civilianUnit!!.name.tr()+" - "+civilianUnit!!.civInfo.civName.tr()
        if(militaryUnit!=null && isViewableToPlayer){
            var milUnitString = militaryUnit!!.name.tr()
            if(militaryUnit!!.health<100) milUnitString += "(" + militaryUnit!!.health + ")"
            milUnitString += " - "+militaryUnit!!.civInfo.civName.tr()
            lineList += milUnitString
        }
        if(getDefensiveBonus()!=0f){
            var defencePercentString = (getDefensiveBonus()*100).toInt().toString()+"%"
            if(!defencePercentString.startsWith("-")) defencePercentString = "+$defencePercentString"
            lineList += "[$defencePercentString] to unit defence".tr()
        }
        if(getBaseTerrain().impassable) lineList += "Impassible".tr()

        return lineList.joinToString("\n")
    }

    //endregion

    //region state-changing functions
    fun setTransients(){
        baseTerrainObject = ruleset.terrains[baseTerrain]!! // This is a HACK.
        isWater = getBaseTerrain().type==TerrainType.Water
        isLand = getBaseTerrain().type==TerrainType.Land
        isOcean = baseTerrain == Constants.ocean

        for (unit in getUnits()) {
            unit.currentTile = this
            unit.assignOwner(tileMap.gameInfo.getCivilization(unit.owner),false)
            unit.setTransients(ruleset)
        }
    }

    fun startWorkingOnImprovement(improvement: TileImprovement, civInfo: CivilizationInfo) {
        improvementInProgress = improvement.name
        turnsToImprovement = improvement.getTurnsToBuild(civInfo)
    }

    fun hasEnemySubmarine(viewingCiv:CivilizationInfo): Boolean {
        val unitsInTile = getUnits()
        if (unitsInTile.isEmpty()) return false
        if (unitsInTile.first().civInfo!=viewingCiv &&
                unitsInTile.firstOrNull { it.isInvisible() } != null) {
            return true
        }
        return false
    }

    fun hasRoad(civInfo: CivilizationInfo): Boolean {
        if(roadStatus != RoadStatus.None) return true
        if(civInfo.nation.forestsAndJunglesAreRoads && (terrainFeature==Constants.jungle || terrainFeature==Constants.forest))
            return true
        return false
    }
    //endregion
}