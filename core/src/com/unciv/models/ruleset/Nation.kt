 package com.unciv.models.ruleset

import com.badlogic.gdx.graphics.Color
import com.unciv.Constants
import com.unciv.logic.civilization.CityStateType
import com.unciv.models.stats.INamed
import com.unciv.models.translations.tr
import com.unciv.ui.utils.Fonts
import com.unciv.ui.utils.colorFromRGB

enum class VictoryType {
    Neutral,
    Cultural,
    Domination,
    Scientific
}

class Nation : INamed {
    override lateinit var name: String

    var leaderName = ""
    fun getLeaderDisplayName() = if (isCityState()) name
    else "[$leaderName] of [$name]"

    val style = ""
    var cityStateType: CityStateType? = null
    var preferredVictoryType: VictoryType = VictoryType.Neutral
    var declaringWar = ""
    var attacked = ""
    var defeated = ""
    var introduction = ""
    var tradeRequest = ""

    var neutralHello = ""
    var hateHello = ""

    lateinit var outerColor: List<Int>
    var uniqueName = ""
    var uniques = HashSet<String>()
    val uniqueObjects: List<Unique> by lazy { uniques.map { Unique(it) } }
    var uniqueText = ""
    var innerColor: List<Int>? = null
    var startBias = ArrayList<String>()
    
    var startIntroPart1 = ""
    var startIntroPart2 = ""

    /* Properties present in json but not yet implemented:
    var adjective = ArrayList<String>()
     */

    @Transient
    private lateinit var outerColorObject: Color
    fun getOuterColor(): Color = outerColorObject

    @Transient
    private lateinit var innerColorObject: Color

    fun getInnerColor(): Color = innerColorObject

    fun isCityState() = cityStateType != null
    fun isMajorCiv() = !isBarbarian() && !isCityState() && !isSpectator()
    fun isBarbarian() = name == Constants.barbarians
    fun isSpectator() = name == Constants.spectator

    // This is its own transient because we'll need to check this for every tile-to-tile movement which is harsh
    @Transient
    var forestsAndJunglesAreRoads = false

    // Same for Inca unique
    @Transient
    var ignoreHillMovementCost = false

    @Transient
    var disembarkCosts1 = false

    fun setTransients() {
        outerColorObject = colorFromRGB(outerColor)

        innerColorObject = if (innerColor == null) Color.BLACK
                           else colorFromRGB(innerColor!!)

        forestsAndJunglesAreRoads = uniques.contains("All units move through Forest and Jungle Tiles in friendly territory as if they have roads. These tiles can be used to establish City Connections upon researching the Wheel.")
        ignoreHillMovementCost = uniques.contains("Units ignore terrain costs when moving into any tile with Hills")
        disembarkCosts1 = uniques.contains("Units pay only 1 movement point to disembark")
    }

    lateinit var cities: ArrayList<String>


    fun getUniqueString(ruleset: Ruleset, forPickerScreen: Boolean = true): String {
        val textList = ArrayList<String>()

        if (leaderName.isNotEmpty() && !forPickerScreen) {
            textList += getLeaderDisplayName().tr()
            textList += ""
        }

        if (uniqueName != "") textList += uniqueName.tr() + ":"
        if (uniqueText != "") {
            textList += " " + uniqueText.tr()
        } else {
            textList += "  " + uniques.joinToString(", ") { it.tr() }
            textList += ""
        }

        if (startBias.isNotEmpty()) {
            textList += "Start bias:".tr() + startBias.joinToString(", ", " ") { it.tr() }
            textList += ""
        }
        addUniqueBuildingsText(textList, ruleset)
        addUniqueUnitsText(textList, ruleset)
        addUniqueImprovementsText(textList, ruleset)

        return textList.joinToString("\n")
    }

    private fun addUniqueBuildingsText(textList: ArrayList<String>, ruleset: Ruleset) {
        for (building in ruleset.buildings.values
                .filter { it.uniqueTo == name && "Will not be displayed in Civilopedia" !in it.uniques }) {
            if (building.replaces != null && ruleset.buildings.containsKey(building.replaces!!)) {
                val originalBuilding = ruleset.buildings[building.replaces!!]!!

                textList += building.name.tr() + " - " + "Replaces [${originalBuilding.name}]".tr()
                val originalBuildingStatMap = originalBuilding.toHashMap()
                for (stat in building.toHashMap())
                    if (stat.value != originalBuildingStatMap[stat.key])
                        textList += "  " + stat.key.toString().tr() + " " + "[${stat.value.toInt()}] vs [${originalBuildingStatMap[stat.key]!!.toInt()}]".tr()

                for (unique in building.uniques.filter { it !in originalBuilding.uniques })
                    textList += "  " + unique.tr()
                if (building.maintenance != originalBuilding.maintenance)
                    textList += "  {Maintenance} " + "[${building.maintenance}] vs [${originalBuilding.maintenance}]".tr()
                if (building.cost != originalBuilding.cost)
                    textList += "  {Cost} " + "[${building.cost}] vs [${originalBuilding.cost}]".tr()
                if (building.cityStrength != originalBuilding.cityStrength)
                    textList += "  {City strength} " + "[${building.cityStrength}] vs [${originalBuilding.cityStrength}]".tr()
                if (building.cityHealth != originalBuilding.cityHealth)
                    textList += "  {City health} " + "[${building.cityHealth}] vs [${originalBuilding.cityHealth}]".tr()
                textList += ""
            } else if (building.replaces != null) {
                textList += building.name.tr() + " - " + "Replaces [${building.replaces}], which is not found in the ruleset!".tr()
            } else textList += building.getShortDescription(ruleset)
        }
    }

    private fun addUniqueUnitsText(textList: ArrayList<String>, ruleset: Ruleset) {
        for (unit in ruleset.units.values
                .filter { it.uniqueTo == name && "Will not be displayed in Civilopedia" !in it.uniques }) {
            if (unit.replaces != null && ruleset.units.containsKey(unit.replaces!!)) {
                val originalUnit = ruleset.units[unit.replaces!!]!!
                textList += unit.name.tr() + " - " + "Replaces [${originalUnit.name}]".tr()
                if (unit.cost != originalUnit.cost)
                    textList += "  {Cost} " + "[${unit.cost}] vs [${originalUnit.cost}]".tr()
                if (unit.strength != originalUnit.strength)
                    textList += "  ${Fonts.strength} " + "[${unit.strength}] vs [${originalUnit.strength}]".tr()
                if (unit.rangedStrength != originalUnit.rangedStrength)
                    textList += "  ${Fonts.rangedStrength} " + "[${unit.rangedStrength}] vs [${originalUnit.rangedStrength}]".tr()
                if (unit.range != originalUnit.range)
                    textList += "  ${Fonts.range} " + "[${unit.range}] vs [${originalUnit.range}]".tr()
                if (unit.movement != originalUnit.movement)
                    textList += "  ${Fonts.movement} " + "[${unit.movement}] vs [${originalUnit.movement}]".tr()
                for (resource in originalUnit.getResourceRequirements().keys)
                    if (!unit.getResourceRequirements().containsKey(resource))
                        textList += "  " + "[$resource] not required".tr()
                for (unique in unit.uniques.filterNot { it in originalUnit.uniques })
                    textList += "  " + unique.tr()
                for (unique in originalUnit.uniques.filterNot { it in unit.uniques })
                    textList += "  " + "Lost ability".tr() + "(" + "vs [${originalUnit.name}]".tr() + "): " + unique.tr()
                for (promotion in unit.promotions.filter { it !in originalUnit.promotions })
                    textList += "  " + promotion.tr() + " (" + ruleset.unitPromotions[promotion]!!.uniques.joinToString(",") { it.tr() } + ")"
            } else if (unit.replaces != null) {
                textList += unit.name.tr() + " - " + "Replaces [${unit.replaces}], which is not found in the ruleset!".tr()
            } else {
                textList += unit.name.tr()
                textList += "  " + unit.getDescription().split("\n").joinToString("\n  ")
            }

            textList += ""
        }
    }

    private fun addUniqueImprovementsText(textList: ArrayList<String>, ruleset: Ruleset) {
        for (improvement in ruleset.tileImprovements.values
                .filter { it.uniqueTo == name }) {

            textList += improvement.name.tr()
            textList += "  " + improvement.clone().toString()
            for (unique in improvement.uniques)
                textList += "  " + unique.tr()
        }
    }
}
