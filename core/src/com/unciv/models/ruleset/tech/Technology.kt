package com.unciv.models.ruleset.tech

import com.unciv.UncivGame
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.Unique
import com.unciv.models.translations.tr
import com.unciv.models.ruleset.unit.BaseUnit
import java.util.*

class Technology {

    lateinit var name: String

    var cost: Int = 0
    var prerequisites = HashSet<String>()
    var uniques = ArrayList<String>()
    val uniqueObjects: List<Unique> by lazy { uniques.map { Unique(it) } }

    var column: TechColumn? = null // The column that this tech is in the tech tree
    var row: Int = 0
    var quote = ""

    fun getDescription(ruleset: Ruleset): String {
        val lineList = ArrayList<String>() // more readable than StringBuilder, with same performance for our use-case
        for (unique in uniques) lineList += unique.tr()

        for (improvement in ruleset.tileImprovements.values)
            for (unique in improvement.uniqueObjects) {
                if (unique.placeholderText == "[] once [] is discovered" && unique.params.last() == name)
                    lineList += "[${unique.params[0]}] from every [${improvement.name}]"
                else if (unique.placeholderText == "[] on [] tiles once [] is discovered" && unique.params.last() == name)
                    lineList += "[${unique.params[0]}] from every [${improvement.name}] on [${unique.params[1]}] tiles"
            }

        val viewingCiv = UncivGame.Current.worldScreen.viewingCiv
        val enabledUnits = getEnabledUnits(viewingCiv).filter { "Will not be displayed in Civilopedia" !in it.uniques }
        if (enabledUnits.isNotEmpty()) {
            lineList += "{Units enabled}: "
            for (unit in enabledUnits)
                lineList += " * " + unit.name.tr() + " (" + unit.getShortDescription() + ")"
        }

        val enabledBuildings = getEnabledBuildings(viewingCiv)

        val regularBuildings = enabledBuildings.filter {
            !it.isWonder && !it.isNationalWonder
                    && "Will not be displayed in Civilopedia" !in it.uniques
                    &&  !(!viewingCiv.gameInfo.hasReligionEnabled() && it.uniques.contains("Hidden when religion is disabled"))
        }
        if (regularBuildings.isNotEmpty()) {
            lineList += "{Buildings enabled}: "
            for (building in regularBuildings)
                lineList += "* " + building.name.tr() + " (" + building.getShortDescription(ruleset) + ")"
        }

        val wonders = enabledBuildings.filter {
            (it.isWonder || it.isNationalWonder)
                    && "Will not be displayed in Civilopedia" !in it.uniques
        }
        if (wonders.isNotEmpty()) {
            lineList += "{Wonders enabled}: "
            for (wonder in wonders)
                lineList += " * " + wonder.name.tr() + " (" + wonder.getShortDescription(ruleset) + ")"
        }

        for (building in getObsoletedBuildings(viewingCiv)
                .filter { "Will not be displayed in Civilopedia" !in it.uniques })
            lineList += "[${building.name}] obsoleted"


        for (resource in ruleset.tileResources.values.asSequence().filter { it.revealedBy == name }
                .map { it.name })
            lineList += "Reveals [$resource] on the map"

        val tileImprovements = ruleset.tileImprovements.values.filter { it.techRequired == name }
        if (tileImprovements.isNotEmpty())
            lineList += "{Tile improvements enabled}: " + tileImprovements.joinToString { it.name.tr() }

        return lineList.joinToString("\n") { it.tr() }
    }

    fun getEnabledBuildings(civInfo: CivilizationInfo): List<Building> {
        var enabledBuildings = civInfo.gameInfo.ruleSet.buildings.values.filter {
                    it.uniqueTo == null || it.uniqueTo == civInfo.civName
        }
        val replacedBuildings = enabledBuildings.mapNotNull { it.replaces }
        enabledBuildings = enabledBuildings.filter { it.name !in replacedBuildings && it.requiredTech == name }

        if (!civInfo.gameInfo.gameParameters.nuclearWeaponsEnabled)
            enabledBuildings = enabledBuildings.filterNot { it.name == "Manhattan Project" }
        
        if (!civInfo.gameInfo.hasReligionEnabled())
            enabledBuildings = enabledBuildings.filterNot { it.uniques.contains("Hidden when religion is disabled") }

        return enabledBuildings
    }

    fun getObsoletedBuildings(civInfo: CivilizationInfo): Sequence<Building> {
        val obsoletedBuildings = civInfo.gameInfo.ruleSet.buildings.values.asSequence()
                .filter { it.uniqueObjects.any { it.placeholderText=="Obsolete with []" && it.params[0]==name } }
        return obsoletedBuildings.filter { civInfo.getEquivalentBuilding(it.name)==it }
    }

    fun getEnabledUnits(civInfo: CivilizationInfo): List<BaseUnit> {
        var enabledUnits = civInfo.gameInfo.ruleSet.units.values.filter {
            it.requiredTech == name &&
                    (it.uniqueTo == null || it.uniqueTo == civInfo.civName)
        }
        val replacedUnits = civInfo.gameInfo.ruleSet.units.values.filter { it.uniqueTo == civInfo.civName }
                .mapNotNull { it.replaces }
        enabledUnits = enabledUnits.filter { it.name !in replacedUnits }

        if (!civInfo.gameInfo.gameParameters.nuclearWeaponsEnabled)
            enabledUnits = enabledUnits.filterNot { it.uniques.contains("Requires Manhattan Project") }

        return enabledUnits
    }

    override fun toString() = name

    fun era(): String = column!!.era

    fun isContinuallyResearchable() = uniques.contains("Can be continually researched")
}
