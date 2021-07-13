package com.unciv.models.ruleset

import com.unciv.logic.city.CityInfo
import com.unciv.logic.civilization.CivFlags
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
        return if (result == null) listOf()
        else result
    }

    fun getAllUniques() = this.asSequence().flatMap { it.value.asSequence() }
}

// Buildings, techs and policies can have 'triggered' effects
object UniqueTriggerActivation {
    fun triggerCivwideUnique(unique: Unique, civInfo: CivilizationInfo, cityInfo: CityInfo? = null) {
        val chosenCity = if (cityInfo != null) cityInfo else civInfo.cities.firstOrNull { it.isCapital() }
        when (unique.placeholderText) {
            "Free [] appears" -> {
                val unitName = unique.params[0]
                val unit = civInfo.gameInfo.ruleSet.units[unitName]
                if (chosenCity != null && unit != null && (!unit.uniques.contains("Founds a new city") || !civInfo.isOneCityChallenger()))
                    civInfo.addUnit(unitName, chosenCity)
            }
            "[] free [] units appear" -> {
                val unitName = unique.params[1]
                val unit = civInfo.gameInfo.ruleSet.units[unitName]
                if (chosenCity != null && unit != null && (!unit.uniques.contains("Founds a new city") || !civInfo.isOneCityChallenger()))
                    for (i in 1..unique.params[0].toInt())
                        civInfo.addUnit(unitName, chosenCity)
            }
            // spectators get all techs at start of game, and if (in a mod) a tech gives a free policy, the game stucks on the policy picker screen
            "Free Social Policy" -> if (!civInfo.isSpectator()) civInfo.policies.freePolicies++
            "[] Free Social Policies" -> if (!civInfo.isSpectator()) civInfo.policies.freePolicies += unique.params[0].toInt()
            "Empire enters golden age" -> civInfo.goldenAges.enterGoldenAge()
            "Free Great Person" -> {
                if (civInfo.isSpectator()) return
                if (civInfo.isPlayerCivilization()) civInfo.greatPeople.freeGreatPeople++
                else {
                    val greatPeople = civInfo.getGreatPeople()
                    if (greatPeople.isEmpty()) return
                    var greatPerson = civInfo.getGreatPeople().random()

                    val preferredVictoryType = civInfo.victoryType()
                    if (preferredVictoryType == VictoryType.Cultural) {
                        val culturalGP = greatPeople.firstOrNull { it.uniques.contains("Great Person - [Culture]") }
                        if (culturalGP != null) greatPerson = culturalGP
                    }
                    if (preferredVictoryType == VictoryType.Scientific) {
                        val scientificGP = greatPeople.firstOrNull { it.uniques.contains("Great Person - [Science]") }
                        if (scientificGP != null) greatPerson = scientificGP
                    }

                    civInfo.addUnit(greatPerson.name, chosenCity)
                }
            }
            // Deprecated since 3.15.4
                "+1 population in each city" ->
                    for (city in civInfo.cities) {
                        city.population.addPopulation(1)
                    }
            // 
            "[] population []" ->
                for (city in civInfo.cities) {
                    if (city.matchesFilter(unique.params[1])) {
                        city.population.addPopulation(unique.params[0].toInt())
                    }
                }
            "Free Technology" -> if (!civInfo.isSpectator()) civInfo.tech.freeTechs += 1
            "[] Free Technologies" -> if (!civInfo.isSpectator()) civInfo.tech.freeTechs += unique.params[0].toInt() 

            "Quantity of strategic resources produced by the empire increased by 100%" -> civInfo.updateDetailedCivResources()
            // Deprecated since 3.15
                "+20% attack bonus to all Military Units for 30 turns" -> civInfo.temporaryUniques.add(Pair(unique, 30))
            //
            "+[]% attack strength to all [] Units for [] turns" -> civInfo.temporaryUniques.add(Pair(unique, unique.params[2].toInt()))

            "Reveals the entire map" -> civInfo.exploredTiles.addAll(civInfo.gameInfo.tileMap.values.asSequence().map { it.position })

            "[] units gain the [] promotion" -> {
                val filter = unique.params[0]
                val promotion = unique.params[1]
                for (unit in civInfo.getCivUnits())
                    if (unit.matchesFilter(filter)
                        || civInfo.gameInfo.ruleSet.unitPromotions.values.any {
                            it.name == promotion && unit.type.name in it.unitTypes
                        }
                    ) {
                        unit.promotions.addPromotion(promotion, isFree = true)
                    }
            }
            "Allied City-States will occasionally gift Great People" -> 
                civInfo.addFlag(CivFlags.cityStateGreatPersonGift.name, civInfo.turnsForGreatPersonFromCityState() / 2)
            // The mechanics for granting great people are wonky, but basically the following happens:
            // Based on the game speed, a timer with some amount of turns is set, 40 on regular speed
            // Every turn, 1 is subtracted from this timer, as long as you have at least 1 city state ally
            // So no, the number of city-state allies does not matter for this. You have a global timer for all of them combined.
            // If the timer reaches the amount of city-state allies you have (or 10, whichever is lower), it is reset.
            // You will then receive a random great person from a random city-state you are allied to
            // The very first time after acquiring this policy, the timer is set to half of its normal value
            // This is the basics, and apart from this, there is some randomness in the exact turn count, but I don't know how much

            // There is surprisingly little information findable online about this policy, and the civ 5 source files are
            // also quite though to search through, so this might all be incorrect.
            // For now this mechanic seems decent enough that this is fine.

            // Note that the way this is implemented now, this unique does NOT stack
            // I could parametrize the [Allied], but eh.
        }
    }
}