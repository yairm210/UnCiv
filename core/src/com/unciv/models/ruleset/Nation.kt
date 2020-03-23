package com.unciv.models.ruleset

import com.badlogic.gdx.graphics.Color
import com.unciv.UniqueAbility
import com.unciv.logic.civilization.CityStateType
import com.unciv.models.stats.INamed
import com.unciv.models.translations.Translations
import com.unciv.models.translations.tr
import com.unciv.ui.utils.colorFromRGB

enum class VictoryType{
    Neutral,
    Cultural,
    Domination,
    Scientific
}

class Nation : INamed {
    override lateinit var name: String

    var leaderName=""
    fun getLeaderDisplayName() = if(isCityState()) name
        else "[$leaderName] of [$name]"

    var cityStateType: CityStateType? = null
    var preferredVictoryType:VictoryType = VictoryType.Neutral
    var declaringWar=""
    var attacked=""
    var defeated=""
    var introduction=""
    var tradeRequest=""

    var neutralHello=""
    var hateHello=""

    lateinit var outerColor: List<Int>
    var unique: UniqueAbility? = null
    var innerColor: List<Int>? = null
    var startBias = ArrayList<String>()

    @Transient private lateinit var outerColorObject:Color
    fun getOuterColor(): Color = outerColorObject

    @Transient private lateinit var innerColorObject:Color

    fun getInnerColor(): Color = innerColorObject

    fun isCityState()= cityStateType != null
    fun isMajorCiv() = !isBarbarian() && !isCityState()
    fun isBarbarian() = name=="Barbarians"

    // This is its own transient because we'll need to check this for every tile-to-tile movement which is harsh
    @Transient var forestsAndJunglesAreRoads = false
    // Same for Inca unique
    @Transient var greatAndeanRoad = false

    fun setTransients(){
        outerColorObject = colorFromRGB(outerColor[0], outerColor[1], outerColor[2])

        if(innerColor==null) innerColorObject = Color.BLACK
        else innerColorObject = colorFromRGB(innerColor!![0], innerColor!![1], innerColor!![2])

        if(unique == UniqueAbility.GREAT_WARPATH)
            forestsAndJunglesAreRoads = true
        if(unique == UniqueAbility.GREAT_ANDEAN_ROAD)
            greatAndeanRoad = true
    }

    lateinit var cities: ArrayList<String>




    fun getUniqueString(ruleset: Ruleset, forPickerScreen: Boolean = true): String {
        val textList = ArrayList<String>()

        if (leaderName.isNotEmpty() && !forPickerScreen){
            textList += getLeaderDisplayName().tr()
            textList += ""
        }
        if (unique != null) {
            textList += unique!!.displayName.tr() + ":"
            textList += "  " + unique!!.description.tr()

            textList += ""
        }
        if (startBias.isNotEmpty()) {
            textList += "Start bias:".tr() + startBias.joinToString(", ", " ") { it.tr() }
            textList += ""
        }
        addUniqueBuildingsText(textList,ruleset)
        addUniqueUnitsText(textList,ruleset)
        addUniqueImprovementsText(textList,ruleset)

        return textList.joinToString("\n").tr().trim()
    }

    private fun addUniqueBuildingsText(textList: ArrayList<String>, ruleset: Ruleset) {
        for (building in ruleset.buildings.values
                .filter { it.uniqueTo == name }) {
            val originalBuilding = ruleset.buildings[building.replaces!!]!!

            textList += building.name.tr() + " - "+"Replaces [${originalBuilding.name}]".tr()
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
        }
    }

    private fun addUniqueUnitsText(textList: ArrayList<String>, ruleset: Ruleset) {
        for (unit in ruleset.units.values
                .filter { it.uniqueTo == name }) {
            val originalUnit = ruleset.units[unit.replaces!!]!!

            textList += unit.name.tr() + " - "+"Replaces [${originalUnit.name}]".tr()
            if (unit.cost != originalUnit.cost)
                textList += "  {Cost} " + "[${unit.cost}] vs [${originalUnit.cost}]".tr()
            if (unit.strength != originalUnit.strength)
                textList += "  {Strength} " + "[${unit.strength}] vs [${originalUnit.strength}]".tr()
            if (unit.rangedStrength != originalUnit.rangedStrength)
                textList += "  {Ranged strength} " + "[${unit.rangedStrength}] vs [${originalUnit.rangedStrength}]".tr()
            if (unit.range != originalUnit.range)
                textList += "  {Range} " + unit.range + "[${unit.range}] vs [${originalUnit.range}]".tr()
            if (unit.movement != originalUnit.movement)
                textList += "  {Movement} " + "[${unit.movement}] vs [${originalUnit.movement}]".tr()
            if (originalUnit.requiredResource != null && unit.requiredResource == null)
                textList += "  " + "[${originalUnit.requiredResource}] not required".tr()
            for (unique in unit.uniques.filterNot { it in originalUnit.uniques })
                textList += "  " + Translations.translateBonusOrPenalty(unique)
            for (unique in originalUnit.uniques.filterNot { it in unit.uniques })
                textList += "  " + "Lost ability".tr() + "(vs " + originalUnit.name.tr() + "): " + Translations.translateBonusOrPenalty(unique)
            for (promotion in unit.promotions.filter { it !in originalUnit.promotions })
                textList += "  " + promotion.tr() + " (" + Translations.translateBonusOrPenalty(ruleset.unitPromotions[promotion]!!.effect) + ")"

            textList += ""
        }
    }

    private fun addUniqueImprovementsText(textList: ArrayList<String>, ruleset: Ruleset) {
        for (improvement in ruleset.tileImprovements.values
                .filter { it.uniqueTo == name }) {

            textList += improvement.name.tr()
            textList += "  "+improvement.clone().toString()
            for(unique in improvement.uniques)
                textList += "  "+unique.tr()
        }
    }
}
