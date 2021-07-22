//  Taken from https://github.com/TomGrill/gdx-testing
package com.unciv.logic.map

import com.badlogic.gdx.math.Vector2
import com.unciv.logic.city.CityInfo
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.GdxTestRunner
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class TileImprovementConstructionTests {

    private var tile = TileInfo()
    private var civInfo = CivilizationInfo()
    private var city = CityInfo()
    private var ruleSet = Ruleset()

    @Before
    fun initTheWorld() {
        RulesetCache.loadRulesets()
        ruleSet = RulesetCache.getBaseRuleset()
        tile.ruleset = ruleSet
        civInfo.tech.researchedTechnologies.addAll(ruleSet.technologies.values)
        civInfo.tech.techsResearched.addAll(ruleSet.technologies.keys)

        // This is so all improvements can be built
        tile.owningCity = city
        tile.position = Vector2(1f, 1f) // so that it's not on the same position as the city
        city.civInfo = civInfo


        // Needed for convertHillToTerrainFeature to not crash
        val tileMap = TileMap()
        tileMap.tileMatrix.add(ArrayList<TileInfo?>().apply { add(tile) })
        tile.tileMap = tileMap
    }


    @Test
    fun allTerrainSpecificImprovementsCanBeBuilt() {

        for (improvement in ruleSet.tileImprovements.values) {
            val terrain = improvement.terrainsCanBeBuiltOn.firstOrNull() ?: continue
            tile.baseTerrain = terrain
            tile.terrainFeatures.clear()
            tile.setTransients()
            if (improvement.uniqueTo != null) civInfo.civName = improvement.uniqueTo!!
            val canBeBuilt = tile.canBuildImprovement(improvement, civInfo)
            Assert.assertTrue(improvement.name, canBeBuilt)
        }
    }

    @Test
    fun allResourceImprovementsCanBeBuilt() {
        for (improvement in ruleSet.tileImprovements.values) {
            tile.resource = ruleSet.tileResources.values
                    .firstOrNull { it.improvement == improvement.name }?.name
            if (tile.resource == null) continue
            tile.baseTerrain = "Plains"
            tile.setTransients()
            val canBeBuilt = tile.canBuildImprovement(improvement, civInfo)
            Assert.assertTrue(improvement.name, canBeBuilt)
        }
    }

    @Test
    fun coastalImprovementsCanBeBuilt() {
        val map = TileMap()
        tile.baseTerrain = "Plains"
        tile.tileMap = map
        tile.setTransients()

        val otherTile = tile.clone()
        otherTile.baseTerrain = "Coast"
        otherTile.position.y = 1f

        map.tileMatrix.add(arrayListOf(tile, otherTile))

        for (improvement in ruleSet.tileImprovements.values) {
            if (!improvement.uniques.contains("Can only be built on [Coastal] tiles")) continue
            civInfo.civName = improvement.uniqueTo ?: "OtherCiv"
            val canBeBuilt = tile.canBuildImprovement(improvement, civInfo)
            Assert.assertTrue(improvement.name, canBeBuilt)
        }
    }

    @Test
    fun uniqueToOtherImprovementsCanNOTBeBuilt() {
        for (improvement in ruleSet.tileImprovements.values) {
            if (improvement.uniqueTo == null) continue
            civInfo.civName = "OtherCiv"
            tile.baseTerrain = "Plains"
            tile.setTransients()
            val canBeBuilt = tile.canBuildImprovement(improvement, civInfo)
            Assert.assertFalse(improvement.name, canBeBuilt)
        }
    }

    @Test
    fun terraceFarmCanNOTBeBuiltOnBonus() {
        tile.baseTerrain = "Plains"
        tile.terrainFeatures.add("Hill")
        tile.resource = "Sheep"
        tile.setTransients()
        civInfo.civName = "Inca"

        for (improvement in ruleSet.tileImprovements.values) {
            if (!improvement.uniques.contains("Cannot be built on [Bonus resource] tiles")) continue
            val canBeBuilt = tile.canBuildImprovement(improvement, civInfo)
            Assert.assertFalse(improvement.name, canBeBuilt)
        }
    }
}