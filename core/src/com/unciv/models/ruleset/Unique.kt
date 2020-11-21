package com.unciv.models.ruleset

import com.unciv.Constants
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.models.stats.Stats
import com.unciv.models.translations.getPlaceholderParameters
import com.unciv.models.translations.getPlaceholderText

class Unique(val text:String){
    val placeholderText = text.getPlaceholderText()
    val params = text.getPlaceholderParameters()
    /** This is so the heavy regex-based parsing is only activated once per unique, instead of every time it's called
     *  - for instance, in the city screen, we call every tile unique for every tile, which can lead to ANRs */
    val stats: Stats by lazy {
        val firstStatParam = params.firstOrNull { Stats.isStats(it) }
        if (firstStatParam == null) Stats() // So badly-defined stats don't crash the entire game
        else Stats.parse(firstStatParam)
    }
}

class UniqueMap:HashMap<String, ArrayList<Unique>>() {
    fun addUnique(unique: Unique) {
        if (!containsKey(unique.placeholderText)) this[unique.placeholderText] = ArrayList()
        this[unique.placeholderText]!!.add(unique)
    }

    fun getUniques(placeholderText: String): List<Unique> {
        val result = this[placeholderText]
        if (result == null) return listOf()
        else return result
    }

    fun getAllUniques() = this.asSequence().flatMap { it.value.asSequence() }
}

// Buildings, techs and policies can have 'triggered' effects
object UniqueTriggerActivation {
    fun triggerCivwideUnique(unique: Unique, civInfo: CivilizationInfo) {
        when (unique.placeholderText) {
            "Free [] appears" -> {
                val unitName = unique.params[0]
                if (civInfo.cities.any { it.isCapital() } && (unitName != Constants.settler || !civInfo.isOneCityChallenger()))
                    civInfo.addUnit(unitName, civInfo.getCapital())
            }
            "[] free [] units appear" -> {
                val unitName = unique.params[1]
                if (civInfo.cities.any { it.isCapital() } && (unitName != Constants.settler || !civInfo.isOneCityChallenger()))
                    for (i in 1..unique.params[0].toInt())
                        civInfo.addUnit(unitName, civInfo.getCapital())
            }
            // spectators get all techs at start of game, and if (in a mod) a tech gives a free policy, the game stucks on the policy picker screen
            "Free Social Policy" -> if(!civInfo.isSpectator()) civInfo.policies.freePolicies++
            "Empire enters golden age" ->
                civInfo.goldenAges.enterGoldenAge()
            "Free Great Person" -> {
                if (civInfo.isPlayerCivilization()) civInfo.greatPeople.freeGreatPeople++
                else {
                    val preferredVictoryType = civInfo.victoryType()
                    val greatPerson = when (preferredVictoryType) {
                        VictoryType.Cultural -> "Great Artist"
                        VictoryType.Scientific -> "Great Scientist"
                        else ->
                            civInfo.gameInfo.ruleSet.units.keys.filter { it.startsWith("Great") }.random()
                    }
                    civInfo.addUnit(greatPerson)
                }
            }
            "+1 population in each city" ->
                for (city in civInfo.cities) {
                    city.population.population += 1
                    city.population.autoAssignPopulation()
                }
            "Free Technology" -> civInfo.tech.freeTechs += 1

            "Quantity of strategic resources produced by the empire increased by 100%" -> civInfo.updateDetailedCivResources()
            "+20% attack bonus to all Military Units for 30 turns" -> civInfo.policies.autocracyCompletedTurns = 30

            "Reveals the entire map" -> civInfo.exploredTiles.addAll(civInfo.gameInfo.tileMap.values.asSequence().map { it.position })
        }
    }

}