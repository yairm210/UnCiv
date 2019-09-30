package com.unciv.logic.city

import com.badlogic.gdx.graphics.Color
import com.unciv.logic.automation.Automation
import com.unciv.logic.map.TileInfo
import com.unciv.ui.utils.withItem
import com.unciv.ui.utils.withoutItem

class CityExpansionManager {
    @Transient
    lateinit var cityInfo: CityInfo
    var cultureStored: Int = 0
    var tilesNotImproved: Int = 0

    fun clone(): CityExpansionManager {
        val toReturn = CityExpansionManager()
        toReturn.cultureStored=cultureStored
        toReturn.tilesNotImproved = tilesNotImproved
        return toReturn
    }

    // This one has conflicting sources -
    // http://civilization.wikia.com/wiki/Mathematics_of_Civilization_V says it's 20+(10(t-1))^1.1
    // https://www.reddit.com/r/civ/comments/58rxkk/how_in_gods_name_do_borders_expand_in_civ_vi/ has it
    //   (per game XML files) at 6*(t+0.4813)^1.3
    // The second seems to be more based, so I'll go with that

    fun getCultureToNextTile(): Int {
        val numTilesClaimed = cityInfo.tiles.size - 7
        var cultureToNextTile = 6 * Math.pow(numTilesClaimed + 1.4813, 1.3)
        if (cityInfo.civInfo.containsBuildingUnique("Cost of acquiring new tiles reduced by 25%"))
            cultureToNextTile *= 0.75 //Speciality of Angkor Wat
        if(cityInfo.containsBuildingUnique("Culture and Gold costs of acquiring new tiles reduced by 25% in this city"))
            cultureToNextTile *= 0.75 // Specialty of Krepost
        if (cityInfo.civInfo.policies.isAdopted("Tradition")) cultureToNextTile *= 0.75
        return Math.round(cultureToNextTile).toInt()
    }

    fun buyTile(tileInfo: TileInfo){
        val goldCost = getGoldCostOfTile(tileInfo)
        class NotEnoughGoldToBuyTileException : Exception()
        if(cityInfo.civInfo.gold<goldCost) throw NotEnoughGoldToBuyTileException()
        cityInfo.civInfo.gold -= goldCost
        takeOwnership(tileInfo)
    }

    fun getGoldCostOfTile(tileInfo: TileInfo): Int {
        val baseCost = 50
        val numTilesClaimed= cityInfo.tiles.size - 7
        val distanceFromCenter = tileInfo.arialDistanceTo(cityInfo.getCenterTile())
        var cost = baseCost * (distanceFromCenter-1) + numTilesClaimed*5.0

        if (cityInfo.civInfo.containsBuildingUnique("Cost of acquiring new tiles reduced by 25%"))
            cost *= 0.75 //Speciality of Angkor Wat
        if(cityInfo.containsBuildingUnique("Culture and Gold costs of acquiring new tiles reduced by 25% in this city"))
            cost *= 0.75 // Specialty of Krepost

        if(cityInfo.civInfo.nation.unique=="All land military units have +1 sight, 50% discount when purchasing tiles")
            cost /= 2
        return cost.toInt()
    }


    fun chooseNewTileToOwn(): TileInfo? {
        for (i in 2..5) {
            val tiles = cityInfo.getCenterTile().getTilesInDistance(i)
                    .filter {it.getOwner() == null && it.neighbors.any { tile->tile.getOwner()==cityInfo.civInfo }}
            if (tiles.isEmpty()) continue
            val chosenTile = tiles.maxBy { Automation().rankTile(it,cityInfo.civInfo) }
            return chosenTile
        }
        return null
    }

    //region state-changing functions
    fun reset() {
        for(tile in cityInfo.tiles.map { cityInfo.tileMap[it] })
            relinquishOwnership(tile)

        cityInfo.getCenterTile().getTilesInDistance(1)
                .filter { it.getCity()==null } // can't take ownership of owned tiles
                .forEach { takeOwnership(it) }
    }

    private fun addNewTileWithCulture() {
        cultureStored -= getCultureToNextTile()

        val chosenTile = chooseNewTileToOwn()
        if(chosenTile!=null){
            takeOwnership(chosenTile)
        }
    }

    fun relinquishOwnership(tileInfo: TileInfo){
        cityInfo.tiles = cityInfo.tiles.withoutItem(tileInfo.position)
        if(cityInfo.workedTiles.contains(tileInfo.position))
            cityInfo.workedTiles = cityInfo.workedTiles.withoutItem(tileInfo.position)
        tileInfo.owningCity=null

        cityInfo.civInfo.updateDetailedCivResources()
        cityInfo.cityStats.update()
    }

    fun takeOwnership(tileInfo: TileInfo){
        if(tileInfo.isCityCenter()) throw Exception("What?!")
        if(tileInfo.getCity()!=null)
            tileInfo.getCity()!!.expansion.relinquishOwnership(tileInfo)

        cityInfo.tiles = cityInfo.tiles.withItem(tileInfo.position)
        tileInfo.owningCity = cityInfo
        cityInfo.population.autoAssignPopulation()
        cityInfo.civInfo.updateDetailedCivResources()
        cityInfo.cityStats.update()

        for(unit in tileInfo.getUnits())
            if(!unit.civInfo.canEnterTiles(cityInfo.civInfo))
                unit.movement.teleportToClosestMoveableTile()

        cityInfo.civInfo.updateViewableTiles()
    }


    fun nextTurn(culture: Float) {
        cultureStored += culture.toInt()
        if (cultureStored >= getCultureToNextTile()) {
            addNewTileWithCulture()
            cityInfo.civInfo.addNotification("["+cityInfo.name + "] has expanded its borders!", cityInfo.location, Color.PURPLE)
        }
        tilesNotImproved = cityInfo.tiles.map { cityInfo.tileMap[it] }.filter { it.isLand && it.improvement == "" }.size
    }

    fun setTransients(){
        val tiles = cityInfo.tiles.map { cityInfo.tileMap[it] }
        for(tile in tiles )
            tile.owningCity=cityInfo
        tilesNotImproved = tiles.filter { it.isLand && it.improvement == "" }.size
    }
    //endregion
}