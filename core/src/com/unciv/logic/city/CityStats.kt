package com.unciv.logic.city

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.civilization.CityStateType
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.logic.map.RoadStatus
import com.unciv.models.Counter
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.Unique
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.ruleset.unit.UnitType
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats


class CityStats {

    @Transient
    var baseStatList = LinkedHashMap<String, Stats>()

    @Transient
    var statPercentBonusList = LinkedHashMap<String, Stats>()

    // Computed from baseStatList and statPercentBonusList - this is so the players can see a breakdown
    @Transient
    var finalStatList = LinkedHashMap<String, Stats>()

    @Transient
    var happinessList = LinkedHashMap<String, Float>()

    @Transient
    var foodEaten = 0f

    @Transient
    var currentCityStats: Stats = Stats()  // This is so we won't have to calculate this multiple times - takes a lot of time, especially on phones

    @Transient
    lateinit var cityInfo: CityInfo

    //region pure fuctions
    private fun getStatsFromTiles(): Stats {
        val stats = Stats()
        for (cell in cityInfo.tilesInRange
                .filter { cityInfo.location == it.position || cityInfo.isWorked(it) })
            stats.add(cell.getTileStats(cityInfo, cityInfo.civInfo))
        return stats
    }

    private fun getStatsFromTradeRoute(): Stats {
        val stats = Stats()
        if (!cityInfo.isCapital() && cityInfo.isConnectedToCapital()) {
            val civInfo = cityInfo.civInfo
            stats.gold = civInfo.getCapital().population.population * 0.15f + cityInfo.population.population * 1.1f - 1 // Calculated by http://civilization.wikia.com/wiki/Trade_route_(Civ5)
            for (unique in civInfo.getMatchingUniques("[] from each Trade Route"))
                stats.add(unique.stats)
            if (civInfo.hasUnique("Gold from all trade routes +25%")) stats.gold *= 1.25f // Machu Pichu speciality
        }
        return stats
    }

    private fun getStatsFromProduction(production: Float): Stats {
        val stats = Stats()

        when (cityInfo.cityConstructions.currentConstructionFromQueue) {
            "Gold" -> stats.gold += production / 4
            "Science" -> stats.science += production * getScienceConversionRate()
        }
        return stats
    }

    fun getScienceConversionRate(): Float {
        var conversionRate = 1 / 4f
        if (cityInfo.civInfo.hasUnique("Production to science conversion in cities increased by 33%"))
            conversionRate *= 1.33f
        return conversionRate
    }

    private fun getStatPercentBonusesFromRailroad(): Stats {
        val stats = Stats()
        val railroadImprovement = cityInfo.getRuleset().tileImprovements["Railroad"]
        if (railroadImprovement == null) return stats // for mods
        val techEnablingRailroad = railroadImprovement.techRequired!!
        // If we conquered enemy cities connected by railroad, but we don't yet have that tech,
        // we shouldn't get bonuses, it's as if the tracks aare layed out but we can't operate them.
        if (cityInfo.civInfo.tech.isResearched(techEnablingRailroad)
                && (cityInfo.isCapital() || isConnectedToCapital(RoadStatus.Railroad)))
            stats.production += 25f
        return stats
    }

    private fun getStatPercentBonusesFromMarble(construction: IConstruction): Stats {
        val stats = Stats()

        if (construction is Building
                && construction.isWonder
                && cityInfo.civInfo.getCivResources()
                        .any { it.amount > 0 && it.resource.unique == "+15% production towards Wonder construction" })
            stats.production += 15f

        return stats
    }

    private fun getStatPercentBonusesFromComputers(): Stats {
        val stats = Stats()

        if (cityInfo.civInfo.hasUnique("+10% science and production in all cities")) {
            stats.production += 10f
            stats.science += 10f
        }

        return stats
    }

    private fun getStatsFromNationUnique(): Stats {
        val stats = Stats()

        stats.add(getStatsFromUniques(cityInfo.civInfo.nation.uniqueObjects.asSequence()))

        if (cityInfo.civInfo.hasUnique("+2 Culture per turn from cities before discovering Steam Power")
                && !cityInfo.civInfo.tech.isResearched("Steam Power"))
            stats.culture += 2

        for (unique in cityInfo.civInfo.getMatchingUniques("[] per turn from cities before []")) {
            if (!cityInfo.civInfo.tech.isResearched(unique.params[1])
                    && !cityInfo.civInfo.policies.adoptedPolicies.contains(unique.params[1]))
                stats.add(unique.stats)
        }
        return stats
    }

    private fun getStatsFromCityStates(): Stats {
        val stats = Stats()

        for (otherCiv in cityInfo.civInfo.getKnownCivs()) {
            if (otherCiv.isCityState() && otherCiv.cityStateType == CityStateType.Maritime
                    && otherCiv.getDiplomacyManager(cityInfo.civInfo).relationshipLevel() >= RelationshipLevel.Friend) {
                if (cityInfo.isCapital()) stats.food += 3
                else stats.food += 1

                if (cityInfo.civInfo.hasUnique("Food and Culture from Friendly City-States are increased by 50%"))
                    stats.food *= 1.5f
            }
        }

        return stats
    }

    private fun getStatPercentBonusesFromNationUnique(currentConstruction: IConstruction): Stats {
        val stats = Stats()

        stats.add(getStatPercentBonusesFromUniques(currentConstruction, cityInfo.civInfo.nation.uniqueObjects.asSequence()))

        if (currentConstruction is Building
                && cityInfo.civInfo.getCapital().cityConstructions.builtBuildings.contains(currentConstruction.name)
                && cityInfo.civInfo.hasUnique("+25% Production towards any buildings that already exist in the Capital"))
            stats.production += 25f

        return stats
    }

    private fun getStatPercentBonusesFromPuppetCity(): Stats {
        val stats = Stats()
        if (cityInfo.isPuppet) {
            stats.science -= 25f
            stats.culture -= 25f
        }
        return stats
    }

    fun getGrowthBonusFromPoliciesAndWonders(): Float {
        var bonus = 0f

        // This is to be deprecated and converted to "+[]% growth in all cities" and "+[]% growth in capital" - keeping it here to that mods with this can still work for now
        if (cityInfo.civInfo.hasUnique("+10% food growth in capital") && cityInfo.isCapital())
            bonus += 10f
        if (cityInfo.civInfo.hasUnique("+15% growth in all cities"))
            bonus += 15f

        for (unique in cityInfo.civInfo.getMatchingUniques("+[]% growth in all cities"))
            bonus += unique.params[0].toFloat()
        if (cityInfo.isCapital()) for (unique in cityInfo.civInfo.getMatchingUniques("+[]% growth in capital"))
            bonus += unique.params[0].toFloat()
        return bonus / 100
    }

    // needs to be a separate function because we need to know the global happiness state
    // in order to determine how much food is produced in a city!
    fun updateCityHappiness() {
        val civInfo = cityInfo.civInfo
        val newHappinessList = LinkedHashMap<String, Float>()
        var unhappinessModifier = civInfo.getDifficulty().unhappinessModifier
        if (!civInfo.isPlayerCivilization())
            unhappinessModifier *= civInfo.gameInfo.getDifficulty().aiUnhappinessModifier

        var unhappinessFromCity = -3f     // -3 happiness per city
        if (civInfo.hasUnique("Unhappiness from number of Cities doubled"))
            unhappinessFromCity *= 2f//doubled for the Indian

        newHappinessList["Cities"] = unhappinessFromCity * unhappinessModifier

        var unhappinessFromCitizens = cityInfo.population.population.toFloat()
        if (civInfo.hasUnique("Specialists produce half normal unhappiness"))
            unhappinessFromCitizens -= cityInfo.population.getNumberOfSpecialists() * 0.5f

        if (cityInfo.isPuppet)
            unhappinessFromCitizens *= 1.5f
        else if (hasExtraAnnexUnhappiness())
            unhappinessFromCitizens *= 2f

        for (unique in civInfo.getMatchingUniques("Unhappiness from population decreased by []%"))
            unhappinessFromCitizens *= (1 - unique.params[0].toFloat() / 100)

        newHappinessList["Population"] = -unhappinessFromCitizens * unhappinessModifier

        var happinessFromPolicies = 0f
        if (civInfo.hasUnique("+1 happiness for every city connected to capital")
                && cityInfo.isConnectedToCapital())
            happinessFromPolicies += 1f
        happinessFromPolicies += getStatsFromUniques(civInfo.policies.policyUniques.getAllUniques()).happiness

        newHappinessList["Policies"] = happinessFromPolicies

        if (hasExtraAnnexUnhappiness()) newHappinessList["Occupied City"] = -2f //annexed city

        val happinessFromSpecialists = getStatsFromSpecialists(cityInfo.population.getNewSpecialists()).happiness.toInt().toFloat()
        if (happinessFromSpecialists > 0) newHappinessList["Specialists"] = happinessFromSpecialists

        val happinessFromBuildings = cityInfo.cityConstructions.getStats().happiness.toInt().toFloat()
        newHappinessList["Buildings"] = happinessFromBuildings

        newHappinessList["National ability"] = getStatsFromUniques(cityInfo.civInfo.nation.uniqueObjects.asSequence()).happiness

        newHappinessList["Wonders"] = getStatsFromUniques(civInfo.getBuildingUniques()).happiness

        newHappinessList["Tile yields"] = getStatsFromTiles().happiness

        // we don't want to modify the existing happiness list because that leads
        // to concurrency problems if we iterate on it while changing
        happinessList = newHappinessList
    }


    private fun hasExtraAnnexUnhappiness(): Boolean {
        if (cityInfo.civInfo.civName == cityInfo.foundingCiv || cityInfo.foundingCiv == "" || cityInfo.isPuppet) return false
        return !cityInfo.containsBuildingUnique("Remove extra unhappiness from annexed cities")
    }

    fun getStatsOfSpecialist(specialistName: String): Stats {
        val specialist = cityInfo.getRuleset().specialists[specialistName]
        if (specialist == null) return Stats()
        val stats = specialist.clone()
        for (unique in cityInfo.civInfo.getMatchingUniques("[] from every specialist"))
            stats.add(unique.stats)
        return stats
    }

    private fun getStatsFromSpecialists(specialists: Counter<String>): Stats {
        val stats = Stats()
        for (entry in specialists.filter { it.value > 0 })
            stats.add(getStatsOfSpecialist(entry.key) * entry.value)
        return stats
    }

    private fun getStatsFromUniques(uniques: Sequence<Unique>): Stats {
        val stats = Stats()

        for (unique in uniques) {
            if ((unique.placeholderText == "[] in capital" && cityInfo.isCapital())
                    || unique.placeholderText == "[] in all cities"
                    || (unique.placeholderText == "[] in all cities with a garrison" && cityInfo.getCenterTile().militaryUnit != null))
                stats.add(unique.stats)
            if (unique.placeholderText == "[] per [] population in all cities") {
                val amountOfEffects = (cityInfo.population.population / unique.params[1].toInt()).toFloat()
                stats.add(unique.stats.times(amountOfEffects))
            }
            if (unique.text == "+1 gold and -1 unhappiness for every 2 citizens in capital" && cityInfo.isCapital()) {
                stats.gold += (cityInfo.population.population / 2).toFloat()
                stats.happiness += (cityInfo.population.population / 2).toFloat()
            }
        }

        return stats
    }


    private fun getStatPercentBonusesFromGoldenAge(isGoldenAge: Boolean): Stats {
        val stats = Stats()
        if (isGoldenAge) {
            stats.production += 20f
            stats.culture += 20f
        }
        return stats
    }

    private fun getStatPercentBonusesFromBuildings(currentConstruction: IConstruction): Stats {
        val stats = cityInfo.cityConstructions.getStatPercentBonuses()

        // This is to be deprecated and converted to "+[]% production when building [] in this city" - keeping it here to that mods with this can still work for now
        if (currentConstruction is BaseUnit) {
            if (currentConstruction.unitType == UnitType.Mounted
                    && cityInfo.containsBuildingUnique("+15% Production when building Mounted Units in this city"))
                stats.production += 15
            if (currentConstruction.unitType.isLandUnit()
                    && cityInfo.containsBuildingUnique("+15% production of land units"))
                stats.production += 15
            if (currentConstruction.unitType.isWaterUnit()
                    && cityInfo.containsBuildingUnique("+15% production of naval units"))
                stats.production += 15
        }

        for (unique in cityInfo.cityConstructions.builtBuildingUniqueMap.getUniques("+[]% production when building [] in this city")) {
            if (constructionMatchesFilter(currentConstruction, unique.params[1]))
                stats.production += unique.params[0].toInt()
        }

        return stats
    }

    private fun getStatPercentBonusesFromUniques(currentConstruction: IConstruction, uniques: Sequence<Unique>): Stats {
        val stats = Stats()

        if (currentConstruction.name == Constants.settler && cityInfo.isCapital()
                && uniques.any { it.text == "Training of settlers increased +50% in capital" })
            stats.production += 50f

        if (currentConstruction is Building && !currentConstruction.isWonder && !currentConstruction.isNationalWonder)
            for (unique in uniques.filter { it.placeholderText == "+[]% Production when constructing [] buildings" }) {
                val stat = Stat.valueOf(unique.params[1])
                if (currentConstruction.isStatRelated(stat))
                    stats.production += unique.params[0].toInt()
            }


        for (unique in uniques.filter { it.placeholderText == "+[]% Production when constructing []" }) {
            if (constructionMatchesFilter(currentConstruction, unique.params[1]))
                stats.production += unique.params[0].toInt()
        }

        for (unique in uniques.filter { it.placeholderText == "+[]% Production when constructing [] units" }) {
            if (currentConstruction is BaseUnit && currentConstruction.matchesFilter(unique.params[1]))
                stats.production += unique.params[0].toInt()
        }


        if (cityInfo.cityConstructions.getBuiltBuildings().any { it.isWonder }
                && uniques.any { it.text == "+33% culture in all cities with a world wonder" })
            stats.culture += 33f
        if (uniques.any { it.text == "+25% gold in capital" } && cityInfo.isCapital())
            stats.gold += 25f
        if (cityInfo.civInfo.getHappiness() >= 0 && uniques.any { it.text == "+15% science while empire is happy" })
            stats.science += 15f

        if (uniques.any { it.text == "Culture in all cities increased by 25%" })
            stats.culture += 25f

        return stats
    }

    fun constructionMatchesFilter(construction: IConstruction, filter: String): Boolean {
        return construction.name == filter
                // All of these are deprecated as of 3.11.20 in favor of "+[]% Production when constructing [] units"
                || filter == "land units" && construction is BaseUnit && construction.unitType.isLandUnit()
                || filter == "naval units" && construction is BaseUnit && construction.unitType.isWaterUnit()
                || filter == "ranged units" && construction is BaseUnit && construction.unitType == UnitType.Ranged
                || filter == "mounted units" && construction is BaseUnit && construction.unitType == UnitType.Mounted
                || filter == "military units" && construction is BaseUnit && !construction.unitType.isCivilian()
                || filter == "melee units" && construction is BaseUnit && construction.unitType.isMelee()

                || filter == "Buildings" && construction is Building && !(construction.isWonder || construction.isNationalWonder)
                || filter == "Wonders" && construction is Building && (construction.isWonder || construction.isNationalWonder)
                || construction is Building && construction.uniques.contains(filter)
    }

    fun isConnectedToCapital(roadType: RoadStatus): Boolean {
        if (cityInfo.civInfo.cities.count() < 2) return false// first city!

        // Railroad, or harbor from railroad
        if (roadType == RoadStatus.Railroad) return cityInfo.isConnectedToCapital { it.any { it.contains("Railroad") } }
        else return cityInfo.isConnectedToCapital()
    }
    //endregion

    fun updateBaseStatList() {
        val newBaseStatList = LinkedHashMap<String, Stats>() // we don't edit the existing baseStatList directly, in order to avoid concurrency exceptions
        val civInfo = cityInfo.civInfo

        newBaseStatList["Population"] = Stats().apply {
            science = cityInfo.population.population.toFloat()
            production = cityInfo.population.getFreePopulation().toFloat()
        }
        newBaseStatList["Tile yields"] = getStatsFromTiles()
        newBaseStatList["Specialists"] = getStatsFromSpecialists(cityInfo.population.getNewSpecialists())
        newBaseStatList["Trade routes"] = getStatsFromTradeRoute()
        newBaseStatList["Buildings"] = cityInfo.cityConstructions.getStats()
        newBaseStatList["Policies"] = getStatsFromUniques(civInfo.policies.policyUniques.getAllUniques())
        newBaseStatList["National ability"] = getStatsFromNationUnique()
        newBaseStatList["Wonders"] = getStatsFromUniques(civInfo.cities.asSequence().flatMap { it.cityConstructions.builtBuildingUniqueMap.getAllUniques() })
        newBaseStatList["City-States"] = getStatsFromCityStates()

        baseStatList = newBaseStatList
    }


    fun updateStatPercentBonusList(currentConstruction:IConstruction) {
        val newStatPercentBonusList = LinkedHashMap<String, Stats>()
        newStatPercentBonusList["Golden Age"] = getStatPercentBonusesFromGoldenAge(cityInfo.civInfo.goldenAges.isGoldenAge())
        newStatPercentBonusList["Policies"] = getStatPercentBonusesFromUniques(currentConstruction, cityInfo.civInfo.policies.policyUniques.getAllUniques())
        newStatPercentBonusList["Buildings"] = getStatPercentBonusesFromBuildings(currentConstruction)
        newStatPercentBonusList["Wonders"] = getStatPercentBonusesFromUniques(currentConstruction, cityInfo.civInfo.getBuildingUniques())
        newStatPercentBonusList["Railroad"] = getStatPercentBonusesFromRailroad()
        newStatPercentBonusList["Marble"] = getStatPercentBonusesFromMarble(currentConstruction)
        newStatPercentBonusList["Computers"] = getStatPercentBonusesFromComputers()
        newStatPercentBonusList["National ability"] = getStatPercentBonusesFromNationUnique(currentConstruction)
        newStatPercentBonusList["Puppet City"] = getStatPercentBonusesFromPuppetCity()

        if (UncivGame.Current.superchargedForDebug) {
            val stats = Stats()
            for (stat in Stat.values()) stats.add(stat, 10000f)
            newStatPercentBonusList["Supercharged"] = stats
        }

        statPercentBonusList = newStatPercentBonusList
    }

    fun update(currentConstruction: IConstruction = cityInfo.cityConstructions.getCurrentConstruction()) {
        // We need to compute Tile yields before happiness
        updateBaseStatList()
        updateCityHappiness()
        updateStatPercentBonusList(currentConstruction)

        updateFinalStatList(currentConstruction) // again, we don't edit the existing currentCityStats directly, in order to avoid concurrency exceptions

        val newCurrentCityStats = Stats()
        for (stat in finalStatList.values) newCurrentCityStats.add(stat)
        currentCityStats = newCurrentCityStats

        cityInfo.civInfo.updateStatsForNextTurn()
    }

    private fun updateFinalStatList(currentConstruction: IConstruction) {
        val newFinalStatList = LinkedHashMap<String, Stats>() // again, we don't edit the existing currentCityStats directly, in order to avoid concurrency exceptions

        for (entry in baseStatList)
            newFinalStatList[entry.key] = entry.value.clone()

        val statPercentBonusesSum = Stats()
        for (bonus in statPercentBonusList.values) statPercentBonusesSum.add(bonus)

        for (entry in newFinalStatList.values)
            entry.production *= 1 + statPercentBonusesSum.production / 100

        val statsFromProduction = getStatsFromProduction(newFinalStatList.values.map { it.production }.sum())
        baseStatList = LinkedHashMap(baseStatList).apply { put("Construction", statsFromProduction) } // concurrency-safe addition
        newFinalStatList["Construction"] = statsFromProduction

        val isUnhappy = cityInfo.civInfo.getHappiness() < 0
        for (entry in newFinalStatList.values) {
            entry.gold *= 1 + statPercentBonusesSum.gold / 100
            entry.culture *= 1 + statPercentBonusesSum.culture / 100
            if (!isUnhappy) entry.food *= 1 + statPercentBonusesSum.food / 100 // Regular food bonus revoked when unhappy per https://forums.civfanatics.com/resources/complete-guide-to-happiness-vanilla.25584/
        }

        // AFTER we've gotten all the gold stats figured out, only THEN do we plonk that gold into Science
        if (cityInfo.getRuleset().modOptions.uniques.contains("Can convert gold to science with sliders")) {
            val amountConverted = (newFinalStatList.values.sumByDouble { it.gold.toDouble() }
                    * cityInfo.civInfo.tech.goldPercentConvertedToScience).toInt().toFloat()
            if (amountConverted > 0) // Don't want you converting negative gold to negative science yaknow
                newFinalStatList["Gold -> Science"] = Stats().apply { science = amountConverted; gold = -amountConverted }
        }
        for (entry in newFinalStatList.values) {
            entry.science *= 1 + statPercentBonusesSum.science / 100
        }


        //
        /* Okay, food calculation is complicated.
        First we see how much food we generate. Then we apply production bonuses to it.
        Up till here, business as usual.
        Then, we deduct food eaten (from the total produced).
        Now we have the excess food, whih has its own things. If we're unhappy, cut it by 1/4.
        Some policies have bonuses for excess food only, not general food production.
         */

        updateFoodEaten()
        newFinalStatList["Population"]!!.food -= foodEaten

        var totalFood = newFinalStatList.values.map { it.food }.sum()

        if (isUnhappy && totalFood > 0) { // Reduce excess food to 1/4 per the same
            val foodReducedByUnhappiness = Stats().apply { food = totalFood * (-3 / 4f) }
            baseStatList = LinkedHashMap(baseStatList).apply { put("Unhappiness", foodReducedByUnhappiness) } // concurrency-safe addition
            newFinalStatList["Unhappiness"] = foodReducedByUnhappiness
        }

        totalFood = newFinalStatList.values.map { it.food }.sum() // recalculate because of previous change

        // Since growth bonuses are special, (applied afterwards) they will be displayed separately in the user interface as well.
        if (totalFood > 0) {
            val foodFromGrowthBonuses = getGrowthBonusFromPoliciesAndWonders() * totalFood
            newFinalStatList["Policies"]!!.food += foodFromGrowthBonuses
            totalFood = newFinalStatList.values.map { it.food }.sum() // recalculate again
        }


        // Same here - will have a different UI display.
        var buildingsMaintenance = cityInfo.cityConstructions.getMaintenanceCosts().toFloat() // this is AFTER the bonus calculation!
        if (!cityInfo.civInfo.isPlayerCivilization()) {
            buildingsMaintenance *= cityInfo.civInfo.gameInfo.getDifficulty().aiBuildingMaintenanceModifier
        }
        newFinalStatList["Maintenance"] = Stats().apply { gold -= buildingsMaintenance.toInt() }


        if (totalFood > 0 && currentConstruction is BaseUnit
                && currentConstruction.uniques.contains("Excess Food converted to Production when under construction")) {
            newFinalStatList["Excess food to production"] =
                    Stats().apply { production = totalFood; food = -totalFood }
        }

        if (cityInfo.isInResistance())
            newFinalStatList.clear()  // NOPE

        if (newFinalStatList.values.map { it.production }.sum() < 1)  // Minimum production for things to progress
            newFinalStatList["Production"] = Stats().apply { production = 1F }
        finalStatList = newFinalStatList
    }

    private fun updateFoodEaten() {
        foodEaten = cityInfo.population.population.toFloat() * 2
        if (cityInfo.civInfo.hasUnique("-50% food consumption by specialists"))
            foodEaten -= cityInfo.population.getNumberOfSpecialists()
    }

}