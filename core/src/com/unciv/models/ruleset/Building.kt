package com.unciv.models.ruleset

import com.unciv.logic.city.CityConstructions
import com.unciv.logic.city.CityInfo
import com.unciv.logic.city.IConstruction
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.civilization.GreatPersonManager
import com.unciv.models.Counter
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.stats.NamedStats
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.models.translations.fillPlaceholders
import com.unciv.models.translations.tr
import com.unciv.ui.civilopedia.FormattedLine
import com.unciv.ui.civilopedia.ICivilopediaText
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.math.pow


class Building : NamedStats(), IConstruction, ICivilopediaText {

    var requiredTech: String? = null

    var cost: Int = 0
    var maintenance = 0
    private var percentStatBonus: Stats? = null
    var specialistSlots: Counter<String>? = null
    fun newSpecialists(): Counter<String> {
        if (specialistSlots == null) return Counter<String>()
        // Could have old specialist values of "gold", "science" etc - change them to the new specialist names
        val counter = Counter<String>()
        for ((entry, amount) in specialistSlots!!) {
            val equivalentStat = Stat.values().firstOrNull { it.name.toLowerCase(Locale.ENGLISH) == entry }

            if (equivalentStat != null)
                counter[Specialist.specialistNameByStat(equivalentStat)] = amount
            else counter[entry] = amount
        }
        return counter
    }

    var greatPersonPoints: Stats? = null

    /** Extra cost percentage when purchasing */
    private var hurryCostModifier = 0
    var isWonder = false
    var isNationalWonder = false
    fun isAnyWonder() = isWonder || isNationalWonder
    var requiredBuilding: String? = null
    var requiredBuildingInAllCities: String? = null

    /** A strategic resource that will be consumed by this building */
    private var requiredResource: String? = null

    /** City can only be built if one of these resources is nearby - it must be improved! */
    private var requiredNearbyImprovedResources: List<String>? = null
    private var cannotBeBuiltWith: String? = null
    var cityStrength = 0
    var cityHealth = 0
    var xpForNewUnits = 0
    var replaces: String? = null
    var uniqueTo: String? = null
    var quote: String = ""
    var providesFreeBuilding: String? = null
    var uniques = ArrayList<String>()
    var replacementTextForUniques = ""
    val uniqueObjects: List<Unique> by lazy { uniques.map { Unique(it) } }

    override var civilopediaText = listOf<FormattedLine>()

    fun getShortDescription(ruleset: Ruleset): String { // should fit in one line
        val infoList = mutableListOf<String>()
        val str = getStats(null).toString()
        if (str.isNotEmpty()) infoList += str
        for (stat in getStatPercentageBonuses(null).toHashMap())
            if (stat.value != 0f) infoList += "+${stat.value.toInt()}% ${stat.key.name.tr()}"

        if (requiredNearbyImprovedResources != null)
            infoList += "Requires worked [" + requiredNearbyImprovedResources!!.joinToString("/") { it.tr() } + "] near city"
        if (uniques.isNotEmpty()) {
            if (replacementTextForUniques != "") infoList += replacementTextForUniques
            else infoList += getUniquesStrings()
        }
        if (cityStrength != 0) infoList += "{City strength} +$cityStrength"
        if (cityHealth != 0) infoList += "{City health} +$cityHealth"
        if (xpForNewUnits != 0) infoList += "+$xpForNewUnits {XP for new units}"
        return infoList.joinToString("; ") { it.tr() }
    }

    fun getUniquesStrings(): ArrayList<String> {
        val tileBonusHashmap = HashMap<String, ArrayList<String>>()
        val finalUniques = ArrayList<String>()
        for (unique in uniqueObjects)
            if (unique.placeholderText == "[] from [] tiles []" && unique.params[2] == "in this city") {
                val stats = unique.params[0]
                if (!tileBonusHashmap.containsKey(stats)) tileBonusHashmap[stats] = ArrayList()
                tileBonusHashmap[stats]!!.add(unique.params[1])
            } else if (unique.placeholderText != "Consumes [] []") finalUniques += unique.text
        for ((key, value) in tileBonusHashmap)
            finalUniques += "[stats] from [tileFilter] tiles in this city".fillPlaceholders(key, value.joinToString { it.tr() })
        return finalUniques
    }

    fun getDescription(cityInfo: CityInfo?, ruleset: Ruleset): String {
        val stats = getStats(cityInfo)
        val stringBuilder = StringBuilder()
        if (uniqueTo != null) stringBuilder.appendLine("Unique to [$uniqueTo], replaces [$replaces]".tr())
        if (isWonder) stringBuilder.appendLine("Wonder".tr())
        if (isNationalWonder) stringBuilder.appendLine("National Wonder".tr())
        for ((resource, amount) in getResourceRequirements()) {
            if (amount == 1) stringBuilder.appendLine("Consumes 1 [$resource]".tr()) // For now, to keep the existing translations
            else stringBuilder.appendLine("Consumes [$amount] [$resource]".tr())
        }
        if (providesFreeBuilding != null)
            stringBuilder.appendLine("Provides a free [$providesFreeBuilding] in the city".tr())
        if (uniques.isNotEmpty()) {
            if (replacementTextForUniques != "") stringBuilder.appendLine(replacementTextForUniques)
            else stringBuilder.appendLine(getUniquesStrings().asSequence().map { it.tr() }.joinToString("\n"))
        }
        if (!stats.isEmpty())
            stringBuilder.appendLine(stats.toString())

        val percentStats = getStatPercentageBonuses(cityInfo)
        if (percentStats.production != 0f) stringBuilder.append("+" + percentStats.production.toInt() + "% {Production}\n".tr())
        if (percentStats.gold != 0f) stringBuilder.append("+" + percentStats.gold.toInt() + "% {Gold}\n".tr())
        if (percentStats.science != 0f) stringBuilder.append("+" + percentStats.science.toInt() + "% {Science}\r\n".tr())
        if (percentStats.food != 0f) stringBuilder.append("+" + percentStats.food.toInt() + "% {Food}\n".tr())
        if (percentStats.culture != 0f) stringBuilder.append("+" + percentStats.culture.toInt() + "% {Culture}\r\n".tr())

        if (this.greatPersonPoints != null) {
            val gpp = this.greatPersonPoints!!
            if (gpp.production != 0f) stringBuilder.appendLine("+" + gpp.production.toInt() + " " + "[Great Engineer] points".tr())
            if (gpp.gold != 0f) stringBuilder.appendLine("+" + gpp.gold.toInt() + " " + "[Great Merchant] points".tr())
            if (gpp.science != 0f) stringBuilder.appendLine("+" + gpp.science.toInt() + " " + "[Great Scientist] points".tr())
            if (gpp.culture != 0f) stringBuilder.appendLine("+" + gpp.culture.toInt() + " " + "[Great Artist] points".tr())
        }

        for ((specialistName, amount) in newSpecialists())
            stringBuilder.appendLine("+$amount " + "[$specialistName] slots".tr())

        if (requiredNearbyImprovedResources != null)
            stringBuilder.appendLine(("Requires worked [" + requiredNearbyImprovedResources!!.joinToString("/") { it.tr() } + "] near city").tr())

        if (cityStrength != 0) stringBuilder.appendLine("{City strength} +".tr() + cityStrength)
        if (cityHealth != 0) stringBuilder.appendLine("{City health} +".tr() + cityHealth)
        if (xpForNewUnits != 0) stringBuilder.appendLine("+$xpForNewUnits {XP for new units}".tr())
        if (maintenance != 0)
            stringBuilder.appendLine("{Maintenance cost}: $maintenance {Gold}".tr())
        return stringBuilder.toString().trim()
    }

    fun getStats(city: CityInfo?): Stats {
        val stats = this.clone()
        if (city == null) return stats
        val civInfo = city.civInfo

        for (unique in city.getMatchingUniques("[] from every []")) {
            if (!matchesFilter(unique.params[1])) continue
            stats.add(unique.stats)
        }

        for (unique in uniqueObjects)
            if (unique.placeholderText == "[] with []" && civInfo.hasResource(unique.params[1])
                    && Stats.isStats(unique.params[0]))
                stats.add(unique.stats)

        if (!isWonder)
            for (unique in city.getMatchingUniques("[] from all [] buildings")) {
                if (matchesFilter(unique.params[1]))
                    stats.add(unique.stats)
            }
        else
            for (unique in city.getMatchingUniques("[] from every Wonder"))
                stats.add(unique.stats)
        return stats
    }

    fun getStatPercentageBonuses(cityInfo: CityInfo?): Stats {
        val stats = if (percentStatBonus == null) Stats() else percentStatBonus!!.clone()
        val civInfo = cityInfo?.civInfo
        if (civInfo == null) return stats // initial stats

        val baseBuildingName = getBaseBuilding(civInfo.gameInfo.ruleSet).name

        for (unique in civInfo.getMatchingUniques("+[]% [] from every []")) {
            if (unique.params[2] == baseBuildingName)
                stats.add(Stat.valueOf(unique.params[1]), unique.params[0].toFloat())
        }

        if (uniques.contains("+5% Production for every Trade Route with a City-State in the empire"))
            stats.production += 5 * civInfo.citiesConnectedToCapitalToMediums.count { it.key.civInfo.isCityState() }

        return stats
    }

    fun makeLink() = if (isAnyWonder()) "Wonder/$name" else "Building/$name"
    override fun getCivilopediaTextHeader() = FormattedLine(name, header=2, icon=makeLink())
    override fun hasCivilopediaTextLines() = true
    override fun replacesCivilopediaDescription() = true
    override fun getCivilopediaTextLines(ruleset: Ruleset): List<FormattedLine> {
        fun Float.formatSignedInt() = (if (this > 0f) "+" else "") + this.toInt().toString()
        
        val textList = ArrayList<FormattedLine>()

        if (isAnyWonder()) {
            textList += FormattedLine( if (isWonder) "Wonder" else "National Wonder", color="#CA4", header=3 )
        }

        if (uniqueTo != null) {
            textList += FormattedLine()
            textList += FormattedLine("Unique to [$uniqueTo]", link="Nation/$uniqueTo")
            if (replaces != null) {
                val replacesBuilding = ruleset.buildings[replaces]
                textList += FormattedLine("Replaces [$replaces]", link=replacesBuilding?.makeLink() ?: "", indent=1)
            }
        }

        if (cost > 0) {
            textList += FormattedLine()
            textList += FormattedLine("{Cost}: $cost")
        }

        if (requiredTech != null || requiredBuilding != null || requiredBuildingInAllCities != null)
            textList += FormattedLine()
        if (requiredTech != null)
            textList += FormattedLine("Required tech: [$requiredTech]",
                link="Technology/$requiredTech")
        if (requiredBuilding != null)
            textList += FormattedLine("Requires [$requiredBuilding] to be built in the city",
                link="Building/$requiredBuilding")
        if (requiredBuildingInAllCities != null)
            textList += FormattedLine("Requires [$requiredBuildingInAllCities] to be built in all cities",
                link="Building/$requiredBuildingInAllCities")

        val resourceRequirements = getResourceRequirements()
        if (resourceRequirements.isNotEmpty()) {
            textList += FormattedLine()
            for ((resource, amount) in resourceRequirements) {
                textList += FormattedLine(
                    // the 1 variant should deprecate some time
                    if (amount == 1) "Consumes 1 [$resource]" else "Consumes [$amount] [$resource]",
                    link="Resources/$resource", color="#F42" )
            }
        }

        if (providesFreeBuilding != null) {
            textList += FormattedLine()
            textList += FormattedLine("Provides a free [$providesFreeBuilding] in the city",
                link="Building/$providesFreeBuilding")
        }

        val stats = this.clone()
        val percentStats = getStatPercentageBonuses(null)
        val specialists = newSpecialists()
        if (uniques.isNotEmpty() || !stats.isEmpty() || !percentStats.isEmpty() || this.greatPersonPoints != null || specialists.isNotEmpty())
            textList += FormattedLine()

        if (uniques.isNotEmpty()) {
            if (replacementTextForUniques.isNotEmpty())
                textList += FormattedLine(replacementTextForUniques)
            else
                for (unique in getUniquesStrings()) textList += FormattedLine(unique)
        }

        if (!stats.isEmpty()) {
            textList += FormattedLine(stats.toString())
        }

        if (!percentStats.isEmpty()) {
            for ( (key, value) in percentStats.toHashMap()) {
                if (value == 0f) continue
                textList += FormattedLine(value.formatSignedInt() + "% {$key}")
            }
        }

        if (greatPersonPoints != null) {
            for ( (key, value) in greatPersonPoints!!.toHashMap()) {
                if (value == 0f) continue
                val gppName = GreatPersonManager.statToGreatPersonMapping[key]
                    ?: continue
                textList += FormattedLine(value.formatSignedInt() + " " + "[$gppName] points".tr(),
                    link = "Unit/$gppName")
            }
        }

        if (specialists.isNotEmpty()) {
            for ((specialistName, amount) in specialists)
                textList += FormattedLine("+$amount " + "[$specialistName] slots".tr())
        }

        if (requiredNearbyImprovedResources != null) {
            textList += FormattedLine()
            textList += FormattedLine("Requires at least one of the following resources worked near the city:")
            requiredNearbyImprovedResources!!.forEach {
                textList += FormattedLine(it, indent = 1, link = "Resource/$it")
            }
        }

        if (cityStrength != 0 || cityHealth != 0 || xpForNewUnits != 0 || maintenance != 0) textList += FormattedLine()
        if (cityStrength != 0) textList +=  FormattedLine("{City strength} +$cityStrength")
        if (cityHealth != 0) textList +=  FormattedLine("{City health} +$cityHealth")
        if (xpForNewUnits != 0) textList +=  FormattedLine("+$xpForNewUnits {XP for new units}")
        if (maintenance != 0) textList +=  FormattedLine("{Maintenance cost}: $maintenance {Gold}")

        val seeAlso = ArrayList<FormattedLine>()
        for ((other, building) in ruleset.buildings) {
            if (building.replaces == name || building.providesFreeBuilding == name || uniques.contains("[$name]") ) {
                seeAlso += FormattedLine(other, link=building.makeLink(), indent=1)
            }
        }
        if (seeAlso.isNotEmpty()) {
            textList += FormattedLine()
            textList += FormattedLine("{See also}:")
            textList += seeAlso
        }

        return textList
    }


    override fun canBePurchased(): Boolean {
        return !isAnyWonder() && "Cannot be purchased" !in uniques
    }

    override fun canBePurchasedWithFaith(cityInfo: CityInfo) =
        "Can be purchased with Faith" in uniques
        || uniqueObjects.filter { it.placeholderText == "Can be purchased with Faith []"}.any { cityInfo.matchesFilter(it.params[0]) }

    override fun getProductionCost(civInfo: CivilizationInfo): Int {
        var productionCost = cost.toFloat()

        for (unique in uniqueObjects.filter { it.placeholderText == "Cost increases by [] per owned city" })
            productionCost += civInfo.cities.count() * unique.params[0].toInt()

        if (civInfo.isPlayerCivilization()) {
            if (!isWonder)
                productionCost *= civInfo.getDifficulty().buildingCostModifier
        } else {
            productionCost *= if (isWonder)
                civInfo.gameInfo.getDifficulty().aiWonderCostModifier
            else
                civInfo.gameInfo.getDifficulty().aiBuildingCostModifier
        }

        productionCost *= civInfo.gameInfo.gameParameters.gameSpeed.modifier
        return productionCost.toInt()
    }

    override fun getGoldCost(civInfo: CivilizationInfo): Int {
        // https://forums.civfanatics.com/threads/rush-buying-formula.393892/
        var cost = (30 * getProductionCost(civInfo)).toDouble().pow(0.75) * (1 + hurryCostModifier / 100f)

        for (unique in civInfo.getMatchingUniques("Cost of purchasing items in cities reduced by []%"))
            cost *= 1 - (unique.params[0].toFloat() / 100)

        for (unique in civInfo.getMatchingUniques("Cost of purchasing [] buildings reduced by []%")) {
            if (matchesFilter(unique.params[0]))
                cost *= 1 - (unique.params[1].toFloat() / 100)
        }

        return (cost / 10).toInt() * 10
    }

    override fun getFaithCost(civInfo: CivilizationInfo): Int {
        if (uniques.contains("Can be purchased with Faith")) return civInfo.gameInfo.ruleSet.eras[civInfo.getEra()]!!.faithUnitCost
        return -1
    }

    override fun shouldBeDisplayed(cityConstructions: CityConstructions): Boolean {
        if (cityConstructions.isBeingConstructedOrEnqueued(name))
            return false
        val rejectionReason = getRejectionReason(cityConstructions)
        return rejectionReason == ""
                || rejectionReason.startsWith("Requires")
                || rejectionReason.startsWith("Consumes")
                || rejectionReason.endsWith("Wonder is being built elsewhere")
                || rejectionReason == "Can only be purchased with Faith"
    }

    fun getRejectionReason(construction: CityConstructions): String {
        if (construction.isBuilt(name)) return "Already built"
        // for buildings that are created as side effects of other things, and not directly built
        // unless they can be bought with faith
        if (uniques.contains("Unbuildable")) {
            if (canBePurchasedWithFaith(construction.cityInfo))
                return "Can only be purchased with Faith"
            return "Unbuildable"
        }

        val cityCenter = construction.cityInfo.getCenterTile()
        val civInfo = construction.cityInfo.civInfo

        // This overrides the others
        if (uniqueObjects.any {
                    it.placeholderText == "Not displayed as an available construction unless [] is built"
                            && !construction.containsBuildingOrEquivalent(it.params[0])
                })
            return "Should not be displayed"

        for (unique in uniqueObjects.filter { it.placeholderText == "Not displayed as an available construction without []" }) {
            val filter = unique.params[0]
            if (filter in civInfo.gameInfo.ruleSet.tileResources && !construction.cityInfo.civInfo.hasResource(filter)
                    || filter in civInfo.gameInfo.ruleSet.buildings && !construction.containsBuildingOrEquivalent(filter))
                return "Should not be displayed"
        }

        for (unique in uniqueObjects) when (unique.placeholderText) {
            "Enables nuclear weapon" -> if(!construction.cityInfo.civInfo.gameInfo.gameParameters.nuclearWeaponsEnabled) return "Disabled by setting"
            "Must be on []" -> if (!cityCenter.matchesTerrainFilter(unique.params[0], civInfo)) return unique.text
            "Must not be on []" -> if (cityCenter.matchesTerrainFilter(unique.params[0], civInfo)) return unique.text
            "Must be next to []" -> if (!(unique.params[0] == "Fresh water" && cityCenter.isAdjacentToRiver()) // Fresh water is special, in that rivers are not tiles themselves but also fit the filter.
                    && cityCenter.getTilesInDistance(1).none { it.matchesFilter(unique.params[0], civInfo) }) return unique.text
            "Must not be next to []" -> if (cityCenter.getTilesInDistance(1).any { it.matchesFilter(unique.params[0], civInfo) }) return unique.text
            "Must have an owned [] within [] tiles" -> if (cityCenter.getTilesInDistance(unique.params[1].toInt()).none {
                        it.matchesFilter(unique.params[0], civInfo) && it.getOwner() == construction.cityInfo.civInfo
                    }) return unique.text
            "Can only be built in annexed cities" -> if (construction.cityInfo.isPuppet || construction.cityInfo.foundingCiv == ""
                    || construction.cityInfo.civInfo.civName == construction.cityInfo.foundingCiv) return unique.text
            "Obsolete with []" -> if (civInfo.tech.isResearched(unique.params[0])) return unique.text
            "Hidden when religion is disabled" -> if (!civInfo.gameInfo.hasReligionEnabled()) return unique.text
        }

        if (uniqueTo != null && uniqueTo != civInfo.civName) return "Unique to $uniqueTo"
        if (civInfo.gameInfo.ruleSet.buildings.values.any { it.uniqueTo == civInfo.civName && it.replaces == name })
            return "Our unique building replaces this"
        if (requiredTech != null && !civInfo.tech.isResearched(requiredTech!!)) return "$requiredTech not researched"

        for (unique in uniqueObjects.filter { it.placeholderText == "Unlocked with []" })
            if (civInfo.tech.researchedTechnologies.none { it.era() == unique.params[0] || it.name == unique.params[0] }
                    && !civInfo.policies.isAdopted(unique.params[0]))
                return unique.text

        // Regular wonders
        if (isWonder) {
            if (civInfo.gameInfo.getCities().any { it.cityConstructions.isBuilt(name) })
                return "Wonder is already built"

            if (civInfo.cities.any { it != construction.cityInfo && it.cityConstructions.isBeingConstructedOrEnqueued(name) })
                return "Wonder is being built elsewhere"

            if (civInfo.isCityState())
                return "No world wonders for city-states"
            
            val ruleSet = civInfo.gameInfo.ruleSet
            val startingEra = civInfo.gameInfo.gameParameters.startingEra
            if (startingEra in ruleSet.eras && name in ruleSet.eras[startingEra]!!.startingObsoleteWonders)
                return "Wonder is disabled when starting in this era"
        }


        // National wonders
        if (isNationalWonder) {
            if (civInfo.cities.any { it.cityConstructions.isBuilt(name) })
                return "National Wonder is already built"
            if (requiredBuildingInAllCities != null && civInfo.gameInfo.ruleSet.buildings[requiredBuildingInAllCities!!] == null)
                return "Required building in all cities does not exist in the ruleset!"
            if (requiredBuildingInAllCities != null
                    && civInfo.cities.any {
                        !it.isPuppet && !it.cityConstructions
                                .containsBuildingOrEquivalent(requiredBuildingInAllCities!!)
                    })
                return "Requires a [${civInfo.getEquivalentBuilding(requiredBuildingInAllCities!!)}] in all cities"
            if (civInfo.cities.any { it != construction.cityInfo && it.cityConstructions.isBeingConstructedOrEnqueued(name) })
                return "National Wonder is being built elsewhere"
            if (civInfo.isCityState())
                return "No national wonders for city-states"
        }

        if ("Spaceship part" in uniques) {
            if (!civInfo.hasUnique("Enables construction of Spaceship parts")) return "Apollo project not built!"
            if (civInfo.victoryManager.unconstructedSpaceshipParts()[name] == 0) return "Don't need to build any more of these!"
        }

        for (unique in uniqueObjects) when (unique.placeholderText) {
            "Requires []" -> {
                val filter = unique.params[0]
                if (filter in civInfo.gameInfo.ruleSet.buildings) {
                    if (civInfo.cities.none { it.cityConstructions.containsBuildingOrEquivalent(filter) }) return unique.text // Wonder is not built
                } else if (!civInfo.policies.adoptedPolicies.contains(filter)) return "Policy is not adopted" // this reason should not be displayed
            }

            "Requires a [] in this city" -> {
                val filter = unique.params[0]
                if (civInfo.gameInfo.ruleSet.buildings.containsKey(filter)
                        && !construction.containsBuildingOrEquivalent(filter))
                    return "Requires a [${civInfo.getEquivalentBuilding(filter)}] in this city" // replace with civ-specific building for user
            }

            "Requires a [] in all cities" -> {
                val filter = unique.params[0]
                if (civInfo.gameInfo.ruleSet.buildings.containsKey(filter)
                        && civInfo.cities.any { !it.isPuppet && !it.cityConstructions.containsBuildingOrEquivalent(unique.params[0]) })
                    return "Requires a [${civInfo.getEquivalentBuilding(unique.params[0])}] in all cities"  // replace with civ-specific building for user
            }
            "Hidden until [] social policy branches have been completed" -> {
                if (construction.cityInfo.civInfo.getCompletedPolicyBranchesCount() < unique.params[0].toInt()) {
                    return "Should not be displayed"
                }
            }
            "Hidden when cultural victory is disabled" -> {
                if ( !civInfo.gameInfo.gameParameters.victoryTypes.contains(VictoryType.Cultural)) {
                    return "Hidden when cultural victory is disabled"
                }
            }
        }

        if (requiredBuilding != null && !construction.containsBuildingOrEquivalent(requiredBuilding!!)) {
            if (!civInfo.gameInfo.ruleSet.buildings.containsKey(requiredBuilding!!))
                return "Requires a [${requiredBuilding}] in this city, which doesn't seem to exist in this ruleset!"
            return "Requires a [${civInfo.getEquivalentBuilding(requiredBuilding!!)}] in this city"
        }
        if (cannotBeBuiltWith != null && construction.isBuilt(cannotBeBuiltWith!!))
            return "Cannot be built with $cannotBeBuiltWith"

        for ((resource, amount) in getResourceRequirements())
            if (civInfo.getCivResourcesByName()[resource]!! < amount) {
                if (amount == 1) return "Consumes 1 [$resource]" // Again, to preserve existing translations
                else return "Consumes [$amount] [$resource]"
            }

        if (requiredNearbyImprovedResources != null) {
            val containsResourceWithImprovement = construction.cityInfo.getWorkableTiles()
                    .any {
                        it.resource != null
                                && requiredNearbyImprovedResources!!.contains(it.resource!!)
                                && it.getOwner() == civInfo
                                && (it.getTileResource().improvement == it.improvement || it.getTileImprovement()?.isGreatImprovement() == true || it.isCityCenter())
                    }
            if (!containsResourceWithImprovement) return "Nearby $requiredNearbyImprovedResources required"
        }


        if (!civInfo.gameInfo.gameParameters.victoryTypes.contains(VictoryType.Scientific)
                && "Enables construction of Spaceship parts" in uniques)
            return "Can't construct spaceship parts if scientific victory is not enabled!"

        return ""
    }

    override fun isBuildable(cityConstructions: CityConstructions): Boolean =
            getRejectionReason(cityConstructions) == ""

    override fun postBuildEvent(cityConstructions: CityConstructions, wasBought: Boolean): Boolean {
        val civInfo = cityConstructions.cityInfo.civInfo

        if ("Spaceship part" in uniques) {
            civInfo.victoryManager.currentsSpaceshipParts.add(name, 1)
            return true
        }

        cityConstructions.addBuilding(name)

        val improvement = getImprovement(civInfo.gameInfo.ruleSet)
        if (improvement != null) {
            val tileWithImprovement = cityConstructions.cityInfo.getTiles().firstOrNull { it.improvementInProgress == improvement.name }
            if (tileWithImprovement != null) {
                tileWithImprovement.turnsToImprovement = 0
                tileWithImprovement.improvementInProgress = null
                tileWithImprovement.improvement = improvement.name
            }
        }

        if (providesFreeBuilding != null && !cityConstructions.containsBuildingOrEquivalent(providesFreeBuilding!!)) {
            var buildingToAdd = providesFreeBuilding!!

            for (building in civInfo.gameInfo.ruleSet.buildings.values)
                if (building.replaces == buildingToAdd && building.uniqueTo == civInfo.civName)
                    buildingToAdd = building.name

            cityConstructions.addBuilding(buildingToAdd)
        }

        for (unique in uniqueObjects)
            UniqueTriggerActivation.triggerCivwideUnique(unique, civInfo, cityConstructions.cityInfo)

        if ("Enemy land units must spend 1 extra movement point when inside your territory (obsolete upon Dynamite)" in uniques)
            civInfo.updateHasActiveGreatWall()

        // Korean unique - apparently gives the same as the research agreement
        if (science > 0 && civInfo.hasUnique("Receive a tech boost when scientific buildings/wonders are built in capital"))
            civInfo.tech.addScience(civInfo.tech.scienceOfLast8Turns.sum() / 8)

        cityConstructions.cityInfo.cityStats.update() // new building, new stats
        civInfo.updateDetailedCivResources() // this building/unit could be a resource-requiring one
        civInfo.transients().updateCitiesConnectedToCapital(false) // could be a connecting building, like a harbor

        return true
    }
    
    fun matchesFilter(filter: String): Boolean {
        return when (filter) {
            "All" -> true
            name -> true
            "Building", "Buildings" -> !isAnyWonder()
            "Wonder", "Wonders" -> isAnyWonder()
            replaces -> true
            else -> {
                if (uniques.contains(filter)) return true
                if (isStats(filter) && isStatRelated(Stat.valueOf(filter))) return true
                return false
            }
        }
    }

    fun isStatRelated(stat: Stat): Boolean {
        if (get(stat) > 0) return true
        if (getStatPercentageBonuses(null).get(stat) > 0) return true
        if (uniqueObjects.any { it.placeholderText == "[] per [] population []" && it.stats.get(stat) > 0 }) return true
        return false
    }

    fun getBaseBuilding(ruleset: Ruleset): Building {
        if (replaces == null) return this
        else return ruleset.buildings[replaces!!]!!
    }

    fun getImprovement(ruleset: Ruleset): TileImprovement? {
        val improvementUnique = uniqueObjects
                .firstOrNull { it.placeholderText == "Creates a [] improvement on a specific tile" }
        if (improvementUnique == null) return null
        return ruleset.tileImprovements[improvementUnique.params[0]]
    }

    fun isSellable() = !isAnyWonder() && !uniques.contains("Unsellable")

    override fun getResourceRequirements(): HashMap<String, Int> {
        val resourceRequirements = HashMap<String, Int>()
        if (requiredResource != null) resourceRequirements[requiredResource!!] = 1
        for (unique in uniqueObjects)
            if (unique.placeholderText == "Consumes [] []")
                resourceRequirements[unique.params[1]] = unique.params[0].toInt()
        return resourceRequirements
    }
}
