package com.unciv.logic.civilization

import com.unciv.Constants
import com.unciv.models.gamebasics.GameBasics
import com.unciv.models.gamebasics.Policy
import com.unciv.models.gamebasics.VictoryType


class PolicyManager {

    @Transient
    lateinit var civInfo: CivilizationInfo

    var freePolicies = 0
    var storedCulture = 0
    internal val adoptedPolicies = HashSet<String>()
    var numberOfAdoptedPolicies = 0
    var shouldOpenPolicyPicker = false
            get() = field && canAdoptPolicy()

    // from https://forums.civfanatics.com/threads/the-number-crunching-thread.389702/
    // round down to nearest 5
    fun getCultureNeededForNextPolicy(): Int {
        var baseCost = 25 + Math.pow((numberOfAdoptedPolicies * 6).toDouble(), 1.7)
        var cityModifier = 0.3 * (civInfo.cities.size - 1)
        if (isAdopted("Representation")) cityModifier *= (2 / 3f).toDouble()
        if (isAdopted("Piety Complete")) baseCost *= 0.9
        if (civInfo.getBuildingUniques().contains("Culture cost of adopting new Policies reduced by 10%")) baseCost *= 0.9
        val cost: Int = Math.round(baseCost * (1 + cityModifier)).toInt()
        return cost - (cost % 5)
    }

    fun getAdoptedPolicies(): HashSet<String> = adoptedPolicies

    fun isAdopted(policyName: String): Boolean = adoptedPolicies.contains(policyName)

    fun isAdoptable(policy: Policy): Boolean {
        if(isAdopted(policy.name)) return false
        if (policy.name.endsWith("Complete")) return false
        if (!getAdoptedPolicies().containsAll(policy.requires!!)) return false
        if (policy.getBranch().era > civInfo.getEra()) return false
        return true
    }

    fun canAdoptPolicy(): Boolean = freePolicies > 0 || storedCulture >= getCultureNeededForNextPolicy()

    fun adopt(policy: Policy, branchCompletion: Boolean = false) {

        if(!branchCompletion) {
            if (freePolicies > 0) freePolicies--
            else  {
                val cultureNeededForNextPolicy = getCultureNeededForNextPolicy()
                if(cultureNeededForNextPolicy > storedCulture)
                    throw Exception("How is this possible??????")
                storedCulture -= cultureNeededForNextPolicy
                numberOfAdoptedPolicies++
            }
        }

        adoptedPolicies.add(policy.name)

        if (!branchCompletion) {
            val branch = policy.getBranch()
            if (branch.policies.count { isAdopted(it.name) } == branch.policies.size - 1) { // All done apart from branch completion
                adopt(branch.policies.last(), true) // add branch completion!
            }
        }

        val hasCapital = civInfo.cities.any{it.isCapital()}
        when (policy.name) {
            "Collective Rule" -> if(hasCapital) civInfo.placeUnitNearTile(civInfo.getCapital().location, Constants.settler)
            "Citizenship" -> if(hasCapital) civInfo.placeUnitNearTile(civInfo.getCapital().location, Constants.worker)
            "Representation", "Reformation" -> civInfo.goldenAges.enterGoldenAge()
            "Scientific Revolution" -> civInfo.tech.freeTechs += 2
            "Legalism" ->
                for (city in civInfo.cities.subList(0, Math.min(4, civInfo.cities.size)))
                    city.cityConstructions.addCultureBuilding()
            "Free Religion" -> freePolicies++
            "Liberty Complete" -> {
                if (civInfo.isPlayerCivilization()) civInfo.greatPeople.freeGreatPeople++
                else {
                    val preferredVictoryType = civInfo.victoryType()
                    val greatPerson = when(preferredVictoryType) {
                        VictoryType.Cultural -> "Great Artist"
                        VictoryType.Scientific -> "Great Scientist"
                        VictoryType.Domination,VictoryType.Neutral ->
                            GameBasics.Units.keys.filter { it.startsWith("Great") }.random()
                    }
                    civInfo.addGreatPerson(greatPerson)
                }
            }
        }

        for (cityInfo in civInfo.cities)
            cityInfo.cityStats.update() // This ALSO has the side-effect of updating the CivInfo startForNextTurn so we don't need to call it explicitly

        if(!canAdoptPolicy()) shouldOpenPolicyPicker=false
    }

    fun endTurn(culture: Int) {
        val couldAdoptPolicyBefore = canAdoptPolicy()
        storedCulture += culture
        if (!couldAdoptPolicyBefore && canAdoptPolicy())
            shouldOpenPolicyPicker = true
    }

    fun clone(): PolicyManager {
        val toReturn = PolicyManager()
        toReturn.numberOfAdoptedPolicies=numberOfAdoptedPolicies
        toReturn.adoptedPolicies.addAll(adoptedPolicies)
        toReturn.freePolicies=freePolicies
        toReturn.shouldOpenPolicyPicker=shouldOpenPolicyPicker
        toReturn.storedCulture=storedCulture
        return toReturn
    }

}