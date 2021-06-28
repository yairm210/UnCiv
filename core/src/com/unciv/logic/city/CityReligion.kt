package com.unciv.logic.city

import com.unciv.models.Counter
import kotlin.math.roundToInt

class CityInfoReligionManager: Counter<String>() {
    @Transient
    lateinit var cityInfo: CityInfo

    fun getNumberOfFollowers(): Counter<String> {
        val totalInfluence = values.sum()
        val population = cityInfo.population.population
        if (totalInfluence > 100 * population) {
            val toReturn = Counter<String>()
            for ((key, value) in this)
                if (value > 100)
                    toReturn.add(key, value / 100)
            return toReturn
        }

        val toReturn = Counter<String>()

        for ((key, value) in this) {
            val percentage = value.toFloat() / totalInfluence
            val relativePopulation = (percentage * population).roundToInt()
            toReturn.add(key, relativePopulation)
        }
        return toReturn
    }

    fun getMajorityReligion(): String? {
        val followersPerReligion = getNumberOfFollowers()
        if (followersPerReligion.isEmpty()) return null
        val religionWithMaxFollowers = followersPerReligion.maxByOrNull { it.value }!!
        if (religionWithMaxFollowers.value >= cityInfo.population.population) return religionWithMaxFollowers.key
        else return null
    }

    fun getAffectedBySurroundingCities() {
        val allCitiesWithin10Tiles =
            cityInfo.civInfo.gameInfo.civilizations.asSequence().flatMap { it.cities }
                .filter {
                    it != cityInfo && it.getCenterTile()
                        .aerialDistanceTo(cityInfo.getCenterTile()) <= 10
                }
        for (city in allCitiesWithin10Tiles) {
            val majorityReligionOfCity = city.religion.getMajorityReligion()
            if (majorityReligionOfCity == null) continue
            else add(majorityReligionOfCity, 6) // todo - when holy cities are implemented, *5
        }
    }
}