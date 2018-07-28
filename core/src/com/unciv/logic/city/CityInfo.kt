package com.unciv.logic.city

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector2
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.map.RoadStatus
import com.unciv.logic.map.TileInfo
import com.unciv.logic.map.TileMap
import com.unciv.models.Counter
import com.unciv.models.gamebasics.GameBasics
import com.unciv.models.gamebasics.tile.ResourceType
import com.unciv.models.gamebasics.tile.TileResource
import com.unciv.models.stats.Stats
import kotlin.math.min

class CityInfo {
    @Transient lateinit var civInfo: CivilizationInfo
    var location: Vector2 = Vector2.Zero
    var name: String = ""
    var health = 200

    var population = PopulationManager()
    var cityConstructions = CityConstructions()
    var expansion = CityExpansionManager()
    var cityStats = CityStats()

    var tiles = HashSet<Vector2>()
    var workedTiles = HashSet<Vector2>()
    var isBeingRazed = false

    internal val tileMap: TileMap
        get() = civInfo.gameInfo.tileMap

    fun getCenterTile(): TileInfo = tileMap[location]
    fun getTiles(): List<TileInfo> = tiles.map { tileMap[it] }

    fun getTilesInRange(): List<TileInfo> = getCenterTile().getTilesInDistance( 3)


    // Remove resources required by buildings
    fun getCityResources(): Counter<TileResource> {
        val cityResources = Counter<TileResource>()

        for (tileInfo in getTiles().filter { it.resource != null }) {
            val resource = tileInfo.getTileResource()
            if(resource.revealedBy!=null && !civInfo.tech.isResearched(resource.revealedBy!!)) continue
            if (resource.improvement == tileInfo.improvement || tileInfo.isCityCenter()){
                if(resource.resourceType == ResourceType.Strategic) cityResources.add(resource, 2)
                else cityResources.add(resource, 1)
            }

        }

        for (building in cityConstructions.getBuiltBuildings().filter { it.requiredResource != null }) {
            val resource = GameBasics.TileResources[building.requiredResource]
            cityResources.add(resource, -1)
        }
        return cityResources
    }

    val buildingUniques: List<String?>
        get() = cityConstructions.getBuiltBuildings().filter { it.unique!=null }.map { it.unique }

    fun getGreatPersonPoints(): Stats {
        var greatPersonPoints = population.getSpecialists().times(3f)

        for (building in cityConstructions.getBuiltBuildings())
            if (building.greatPersonPoints != null)
                greatPersonPoints.add(building.greatPersonPoints!!)

        if (civInfo.buildingUniques.contains("+33% great person generation in all cities"))
            greatPersonPoints = greatPersonPoints.times(1.33f)
        if (civInfo.policies.isAdopted("Entrepreneurship"))
            greatPersonPoints.gold *= 1.25f
        if (civInfo.policies.isAdopted("Freedom"))
            greatPersonPoints = greatPersonPoints.times(1.25f)

        return greatPersonPoints
    }

    constructor()   // for json parsing, we need to have a default constructor


    constructor(civInfo: CivilizationInfo, cityLocation: Vector2) {
        this.civInfo = civInfo
        setTransients()

        // Since cities can be captures between civilizations,
        // we need to check which other cities exist globally and name accordingly
        val allExistingCityNames = civInfo.gameInfo.civilizations.flatMap { it.cities }.map { it.name }.toHashSet()
        val probablyName = civInfo.getCivilization().cities.firstOrNull { !allExistingCityNames.contains(it) }
        if(probablyName!=null) name=probablyName
        else name = civInfo.getCivilization().cities.map { "New $it" }.first { !allExistingCityNames.contains(it) }

        this.location = cityLocation
        civInfo.cities.add(this)
        if(civInfo == civInfo.gameInfo.getPlayerCivilization())
            civInfo.addNotification("$name {has been founded}!", cityLocation, Color.PURPLE)
        if (civInfo.policies.isAdopted("Legalism") && civInfo.cities.size <= 4) cityConstructions.addCultureBuilding()
        if (civInfo.cities.size == 1) {
            cityConstructions.builtBuildings.add("Palace")
            cityConstructions.currentConstruction = "Worker" // Default for first city only!
        }

        expansion.reset()
        civInfo.gameInfo.updateTilesToCities()

        val tile = getCenterTile()
        tile.roadStatus = RoadStatus.Railroad
        if (listOf("Forest", "Jungle", "Marsh").contains(tile.terrainFeature))
            tile.terrainFeature = null

        population.autoAssignPopulation()
        cityStats.update()
    }

    fun setTransients() {
        population.cityInfo = this
        expansion.cityInfo = this
        cityStats.cityInfo = this
        cityConstructions.cityInfo = this
    }


    fun endTurn() {
        val stats = cityStats.currentCityStats
        if (cityConstructions.currentConstruction == CityConstructions.Settler && stats.food > 0) {
            stats.production += stats.food
            stats.food = 0f
        }


        cityConstructions.nextTurn(stats)
        expansion.nextTurn(stats.culture)
        if(isBeingRazed){
            population.population--
            if(population.population==0){
                civInfo.addNotification("$name {has been razed to the ground}!",location, Color.RED)
                civInfo.cities.remove(this)
                if(isCapital() && civInfo.cities.isNotEmpty()) // Yes, we actually razed the capital. Some people do this.
                    civInfo.cities.first().cityConstructions.builtBuildings.add("Palace")
            }
        }
        else population.nextTurn(stats.food)

        health = min(health+20, getMaxHealth())
        population.unassignExtraPopulation()
    }

    fun isCapital() = cityConstructions.isBuilt("Palace")

    internal fun getMaxHealth(): Int {
        return 200 + cityConstructions.getBuiltBuildings().sumBy { it.cityHealth }
    }

    override fun toString(): String {return name} // for debug
}