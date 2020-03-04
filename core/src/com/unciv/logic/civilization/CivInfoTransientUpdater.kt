package com.unciv.logic.civilization

import com.badlogic.gdx.graphics.Color
import com.unciv.UncivGame
import com.unciv.logic.map.TileInfo
import com.unciv.models.ruleset.tile.ResourceSupplyList

/** CivInfo class was getting too crowded */
class CivInfoTransientUpdater(val civInfo: CivilizationInfo) {

    // This is a big performance
    fun updateViewableTiles() {
        setNewViewableTiles()

        val newViewableInvisibleTiles = HashSet<TileInfo>()
        newViewableInvisibleTiles.addAll(civInfo.getCivUnits()
                .filter { it.hasUnique("Can attack submarines") }
                .flatMap { it.viewableTiles.asSequence() })
        civInfo.viewableInvisibleUnitsTiles = newViewableInvisibleTiles


        // updating the viewable tiles also affects the explored tiles, obvs
        // So why don't we play switcharoo with the explored tiles as well?
        // Well, because it gets REALLY LARGE so it's a lot of memory space,
        // and we never actually iterate on the explored tiles (only check contains()),
        // so there's no fear of concurrency problems.
        val newlyExploredTiles = civInfo.viewableTiles.asSequence().map { it.position }
                .filterNot { civInfo.exploredTiles.contains(it) }
        civInfo.exploredTiles.addAll(newlyExploredTiles)


        val viewedCivs = HashSet<CivilizationInfo>()
        for (tile in civInfo.viewableTiles) {
            val tileOwner = tile.getOwner()
            if (tileOwner != null) viewedCivs += tileOwner
            for (unit in tile.getUnits()) viewedCivs += unit.civInfo
        }

        if (!civInfo.isBarbarian()) {
            for (otherCiv in viewedCivs.filterNot { it == civInfo || it.isBarbarian() }) {
                if (!civInfo.diplomacy.containsKey(otherCiv.civName)) {
                    civInfo.meetCivilization(otherCiv)
                    civInfo.addNotification("We have encountered [" + otherCiv.civName + "]!", null, Color.GOLD)
                }
            }

            discoverNaturalWonders()
        }
    }

    private fun setNewViewableTiles() {
        val newViewableTiles = HashSet<TileInfo>()

        // There are a LOT of tiles usually.
        // And making large lists of them just as intermediaries before we shove them into the hashset is very space-inefficient.
        // Ans so, sequences to the rescue!
        val ownedTiles = civInfo.cities.asSequence().flatMap { it.getTiles() }
        newViewableTiles.addAll(ownedTiles)
        val neighboringUnownedTiles = ownedTiles.flatMap { it.neighbors.asSequence().filter { it.getOwner() != civInfo } }
        newViewableTiles.addAll(neighboringUnownedTiles)
        newViewableTiles.addAll(civInfo.getCivUnits().flatMap { it.viewableTiles.asSequence() })

        if (!civInfo.isCityState()) {
            for (otherCiv in civInfo.getKnownCivs()) {
                if (otherCiv.getAllyCiv() == civInfo.civName) {
                    newViewableTiles.addAll(otherCiv.cities.asSequence().flatMap { it.getTiles().asSequence() })
                }
            }
        }

        civInfo.viewableTiles = newViewableTiles // to avoid concurrent modification problems
    }

    private fun discoverNaturalWonders() {
        val newlyViewedNaturalWonders = HashSet<TileInfo>()
        for (tile in civInfo.viewableTiles) {
            if (tile.naturalWonder != null && !civInfo.naturalWonders.contains(tile.naturalWonder!!))
                newlyViewedNaturalWonders += tile
        }

        for (tile in newlyViewedNaturalWonders) {
            // GBR could be discovered twice otherwise!
            if (civInfo.naturalWonders.contains(tile.naturalWonder))
                continue
            civInfo.discoverNaturalWonder(tile.naturalWonder!!)
            civInfo.addNotification("We have discovered [" + tile.naturalWonder + "]!", tile.position, Color.GOLD)

            var goldGained = 0
            val discoveredNaturalWonders = UncivGame.Current.gameInfo.civilizations.filter { it != civInfo }
                    .flatMap { it.naturalWonders }
            if (tile.containsUnique("Grants 500 Gold to the first civilization to discover it")
                    && !discoveredNaturalWonders.contains(tile.naturalWonder!!)) {
                goldGained += 500
            }

            if (civInfo.nation.unique == "100 Gold for discovering a Natural Wonder (bonus enhanced to 500 Gold if first to discover it). Culture, Happiness and tile yields from Natural Wonders doubled.") {
                if (!discoveredNaturalWonders.contains(tile.naturalWonder!!))
                    goldGained += 500
                else goldGained += 100
            }

            if (goldGained > 0) {
                civInfo.gold += goldGained
                civInfo.addNotification("We have received [" + goldGained + "] Gold for discovering [" + tile.naturalWonder + "]", null, Color.GOLD)
            }

        }
    }

    fun updateHasActiveGreatWall() {
        civInfo.hasActiveGreatWall = !civInfo.tech.isResearched("Dynamite") &&
                civInfo.containsBuildingUnique("Enemy land units must spend 1 extra movement point when inside your territory (obsolete upon Dynamite)")
    }


    fun setCitiesConnectedToCapitalTransients(initialSetup: Boolean = false) {
        if (civInfo.cities.isEmpty()) return // eg barbarians

        val citiesReachedToMediums = CapitalConnectionsFinder(civInfo).find()

        if (!initialSetup) { // In the initial setup we're loading an old game state, so it doesn't really count
            for (city in citiesReachedToMediums.keys)
                if (city !in civInfo.citiesConnectedToCapitalToMediums && city.civInfo == civInfo && city != civInfo.getCapital())
                    civInfo.addNotification("[${city.name}] has been connected to your capital!", city.location, Color.GOLD)

            for (city in civInfo.citiesConnectedToCapitalToMediums.keys)
                if (!citiesReachedToMediums.containsKey(city) && city.civInfo == civInfo)
                    civInfo.addNotification("[${city.name}] has been disconnected from your capital!", city.location, Color.GOLD)
        }

        civInfo.citiesConnectedToCapitalToMediums = citiesReachedToMediums
    }

    fun updateDetailedCivResources() {
        val newDetailedCivResources = ResourceSupplyList()
        for (city in civInfo.cities) newDetailedCivResources.add(city.getCityResources())

        if (!civInfo.isCityState()) {
            for (otherCiv in civInfo.getKnownCivs().filter { it.getAllyCiv() == civInfo.civName }) {
                for (city in otherCiv.cities) {
                    newDetailedCivResources.add(city.getCityResourcesForAlly())
                }
            }
        }

        for (dip in civInfo.diplomacy.values) newDetailedCivResources.add(dip.resourcesFromTrade())
        for(resource in civInfo.getCivUnits().mapNotNull { it.baseUnit.requiredResource }
                .map { UncivGame.Current.gameInfo.ruleSet.tileResources[it]!! })
            newDetailedCivResources.add(resource,-1,"Units")
        civInfo.detailedCivResources = newDetailedCivResources
    }
}