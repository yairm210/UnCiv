package com.unciv.models.ruleset.unit

import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.Unique
import com.unciv.models.stats.INamed
import com.unciv.models.translations.tr

class Promotion : INamed{
    override lateinit var name: String
    var prerequisites = listOf<String>()
    var uniques = listOf<String>()
    var unitTypes = listOf<String>() // The json parser wouldn't agree to deserialize this as a list of UnitTypes. =(

    val uniqueObjects: List<Unique> by lazy { uniques.map { Unique(it) } }

    fun getDescription(promotionsForUnitType: Collection<Promotion>, forCivilopedia:Boolean=false, ruleSet:Ruleset? = null):String {
        // we translate it before it goes in to get uniques like "vs units in rough terrain" and after to get "vs city
        val stringBuilder = StringBuilder()

        for (unique in uniques) {
            stringBuilder.appendLine(unique.tr())
        }

        if(prerequisites.isNotEmpty()) {
            val prerequisitesString: ArrayList<String> = arrayListOf()
            for (i in prerequisites.filter { promotionsForUnitType.any { promotion -> promotion.name == it } }) {
                prerequisitesString.add(i.tr())
            }
            stringBuilder.appendLine("{Requires}: ".tr() + prerequisitesString.joinToString(" OR ".tr()))
        }
        if(forCivilopedia){
            if (unitTypes.isNotEmpty()) {
                val unitTypesString = unitTypes.joinToString(", ") { it.tr() }
                stringBuilder.appendLine("Available for [$unitTypesString]".tr())
            }

            if (ruleSet!=null) {
                val freeforUnits = ruleSet.units.filter { it.value.promotions.contains(name) }
                if (freeforUnits.isNotEmpty()) {
                    val freeforString = freeforUnits.map { it.value.name }.joinToString(", ") { it.tr() }
                    stringBuilder.appendLine("Free for [$freeforString]".tr())
                }
            }
        }
        return stringBuilder.toString()
    }
}