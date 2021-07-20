package com.unciv.models.ruleset.unit

import com.unciv.Constants
import com.unciv.logic.city.CityConstructions
import com.unciv.logic.city.CityInfo
import com.unciv.logic.city.IConstruction
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.map.MapUnit
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.Unique
import com.unciv.models.translations.tr
import com.unciv.models.stats.INamed
import com.unciv.ui.civilopedia.CivilopediaText
import com.unciv.ui.civilopedia.FormattedLine
import com.unciv.ui.utils.Fonts
import kotlin.math.pow

// This is BaseUnit because Unit is already a base Kotlin class and to avoid mixing the two up

/** This is the basic info of the units, as specified in Units.json,
 in contrast to MapUnit, which is a specific unit of a certain type that appears on the map */
class BaseUnit : INamed, IConstruction, CivilopediaText() {

    override lateinit var name: String
    var cost: Int = 0
    var hurryCostModifier: Int = 0
    var movement: Int = 0
    var strength: Int = 0
    var rangedStrength: Int = 0
    var range: Int = 2
    var interceptRange = 0
    lateinit var unitType: UnitType
    var requiredTech: String? = null
    var requiredResource: String? = null
    var uniques = HashSet<String>()
    val uniqueObjects: List<Unique> by lazy { uniques.map { Unique(it) } }
    var replacementTextForUniques = ""
    var promotions = HashSet<String>()
    var obsoleteTech: String? = null
    var upgradesTo: String? = null
    var replaces: String? = null
    var uniqueTo: String? = null
    var attackSound: String? = null

    fun getShortDescription(): String {
        val infoList = mutableListOf<String>()
        if (strength != 0) infoList += "$strength${Fonts.strength}"
        if (rangedStrength != 0) infoList += "$rangedStrength${Fonts.rangedStrength}"
        if (movement != 2) infoList += "$movement${Fonts.movement}"
        for (promotion in promotions)
            infoList += promotion.tr()
        if (replacementTextForUniques != "") infoList += replacementTextForUniques
        else for (unique in uniques)
            infoList += unique.tr()
        return infoList.joinToString()
    }

    /** Generate description as multi-line string for Nation description addUniqueUnitsText and CityScreen addSelectedConstructionTable */
    fun getDescription(): String {
        val lines = mutableListOf<String>()
        for ((resource, amount) in getResourceRequirements()) {
            lines += if (amount == 1) "Consumes 1 [$resource]".tr()
                     else "Consumes [$amount] [$resource]".tr()
        }
        var strengthLine = ""
        if (strength != 0) {
            strengthLine += "$strength${Fonts.strength}, "
            if (rangedStrength != 0)
                strengthLine += "$rangedStrength${Fonts.rangedStrength}, $range${Fonts.range}, "
        }
        lines += "$strengthLine$movement${Fonts.movement}"

        if (replacementTextForUniques != "") lines += replacementTextForUniques
        else for (unique in uniques)
            lines += unique.tr()

        if (promotions.isNotEmpty()) {
            val prefix = "Free promotion${if (promotions.size == 1) "" else "s"}:".tr() + " "
            lines += promotions.joinToString(", ", prefix) { it.tr() }
        }

        return lines.joinToString("\n")
    }

    override fun getCivilopediaTextHeader() = FormattedLine(name, icon="Unit/$name", header=2)
    override fun replacesCivilopediaDescription() = true
    override fun hasCivilopediaTextLines() = true
    override fun getCivilopediaTextLines(ruleset: Ruleset): List<FormattedLine> {
        val textList = ArrayList<FormattedLine>()

        val stats = ArrayList<String>()
        if (strength != 0) stats += "$strength${Fonts.strength}"
        if (rangedStrength != 0) {
            stats += "$rangedStrength${Fonts.rangedStrength}"
            stats += "$range${Fonts.range}"
        }
        if (movement != 0) stats += "$movement${Fonts.movement}"
        if (cost != 0) stats += "{Cost}: $cost"
        if (stats.isNotEmpty())
            textList += FormattedLine(stats.joinToString(", "))

        if (replacementTextForUniques != "") {
            textList += FormattedLine()
            textList += FormattedLine(replacementTextForUniques)
        } else if (uniques.isNotEmpty()) {
            textList += FormattedLine()
            for (uniqueObject in uniqueObjects.sortedBy { it.text }) {
                if (uniqueObject.placeholderText == "Can construct []") {
                    val improvement = uniqueObject.params[0]
                    textList += FormattedLine(uniqueObject.text, link="Improvement/$improvement")
                } else {
                    textList += FormattedLine(uniqueObject.text)
                }
            }
        }

        val resourceRequirements = getResourceRequirements()
        if (resourceRequirements.isNotEmpty()) {
            textList += FormattedLine()
            for ((resource, amount) in resourceRequirements) {
                textList += FormattedLine(
                    if (amount == 1) "Consumes 1 [$resource]" else "Consumes [$amount] [$resource]",
                    link="Resource/$resource", color="#F42")
            }
        }

        if (uniqueTo != null) {
            textList += FormattedLine()
            textList += FormattedLine("Unique to [$uniqueTo],", link="Nation/$uniqueTo")
            if (replaces != null)
                textList += FormattedLine("replaces [$replaces]", link="Unit/$replaces", indent=1)
        }

        if (requiredTech != null || upgradesTo != null || obsoleteTech != null) textList += FormattedLine()
        if (requiredTech != null) textList += FormattedLine("Required tech: [$requiredTech]", link="Technology/$requiredTech")
        if (upgradesTo != null) textList += FormattedLine("Upgrades to [$upgradesTo]", link="Unit/$upgradesTo")
        if (obsoleteTech != null) textList += FormattedLine("Obsolete with [$obsoleteTech]", link="Technology/$obsoleteTech")

        if (promotions.isNotEmpty()) {
            textList += FormattedLine()
            promotions.withIndex().forEach {
                textList += FormattedLine(
                        when {
                            promotions.size == 1 -> "{Free promotion:} "
                            it.index == 0 -> "{Free promotions:} "
                            else -> ""
                        } + "{${it.value}}" +
                        (if (promotions.size == 1 || it.index == promotions.size - 1) "" else ","),
                        link="Promotions/${it.value}",
                        indent=if(it.index==0) 0 else 1)
            }
        }

        val seeAlso = ArrayList<FormattedLine>()
        for ((other, unit) in ruleset.units) {
            if (unit.replaces == name || uniques.contains("[$name]") ) {
                seeAlso += FormattedLine(other, link="Unit/$other", indent=1)
            }
        }
        if (seeAlso.isNotEmpty()) {
            textList += FormattedLine()
            textList += FormattedLine("{See also}:")
            textList += seeAlso
        }

        return textList
    }

    fun getMapUnit(ruleset: Ruleset): MapUnit {
        val unit = MapUnit()
        unit.name = name

        unit.setTransients(ruleset) // must be after setting name because it sets the baseUnit according to the name

        return unit
    }

    override fun canBePurchased() = "Cannot be purchased" !in uniques
    override fun canBePurchasedWithFaith(cityInfo: CityInfo) =
        "Can be purchased with Faith" in uniques
        || uniqueObjects.filter { it.placeholderText == "Can be purchased with Faith []"}.any { cityInfo.matchesFilter(it.params[0]) }

    override fun getProductionCost(civInfo: CivilizationInfo): Int {
        var productionCost = cost.toFloat()
        if (civInfo.isPlayerCivilization())
            productionCost *= civInfo.getDifficulty().unitCostModifier
        else
            productionCost *= civInfo.gameInfo.getDifficulty().aiUnitCostModifier
        productionCost *= civInfo.gameInfo.gameParameters.gameSpeed.modifier
        return productionCost.toInt()
    }

    fun getBaseGoldCost(civInfo: CivilizationInfo): Double {
        return (30.0 * cost).pow(0.75) * (1 + hurryCostModifier / 100f) * civInfo.gameInfo.gameParameters.gameSpeed.modifier
    }


    override fun getGoldCost(civInfo: CivilizationInfo): Int {
        var cost = getBaseGoldCost(civInfo)
        for (unique in civInfo.getMatchingUniques("Gold cost of purchasing [] units -[]%")) {
            if (matchesFilter(unique.params[0]))
                cost *= 1f - unique.params[1].toFloat() / 100f
        }

        // Deprecated since 3.15
            if (civInfo.hasUnique("Gold cost of purchasing units -33%")) cost *= 0.67f
        //

        for (unique in civInfo.getMatchingUniques("Cost of purchasing items in cities reduced by []%"))
            cost *= 1f - (unique.params[0].toFloat() / 100f)
        return (cost / 10).toInt() * 10 // rounded down to nearest ten
    }

    override fun getFaithCost(civInfo: CivilizationInfo): Int {
        if (uniques.contains("Can be purchased with Faith")
            || uniqueObjects.any { it.placeholderText == "Can be purchased with Faith []" }
        ) 
           return civInfo.gameInfo.ruleSet.eras[civInfo.getEra()]!!.faithUnitCost
        return -1
    }

    fun getDisbandGold(civInfo: CivilizationInfo) = getBaseGoldCost(civInfo).toInt() / 20

    override fun shouldBeDisplayed(construction: CityConstructions): Boolean {
        val rejectionReason = getRejectionReason(construction)
        return rejectionReason == ""
                || rejectionReason.startsWith("Requires")
                || rejectionReason.startsWith("Consumes")
                || rejectionReason == "Can only be purchased with Faith"
    }

    fun getRejectionReason(cityConstructions: CityConstructions): String {
        if (unitType.isWaterUnit() && !cityConstructions.cityInfo.isCoastal())
            return "Can only build water units in coastal cities"
        val civInfo = cityConstructions.cityInfo.civInfo
        for (unique in uniqueObjects.filter { it.placeholderText == "Not displayed as an available construction without []" }) {
            val filter = unique.params[0]
            if (filter in civInfo.gameInfo.ruleSet.tileResources && !civInfo.hasResource(filter)
                    || filter in civInfo.gameInfo.ruleSet.buildings && !cityConstructions.containsBuildingOrEquivalent(filter))
                return "Should not be displayed"
        }
        val civRejectionReason = getRejectionReason(civInfo)
        if (civRejectionReason != "") {
            if (civRejectionReason == "Unbuildable" && canBePurchasedWithFaith(cityConstructions.cityInfo))
                return "Can only be purchased with Faith"
            return civRejectionReason
        }
        for (unique in uniqueObjects.filter { it.placeholderText == "Requires at least [] population" })
            if (unique.params[0].toInt() > cityConstructions.cityInfo.population.population)
                return unique.text
        return ""
    }

    fun getRejectionReason(civInfo: CivilizationInfo): String {
        if (uniques.contains("Unbuildable")) return "Unbuildable"
        if (requiredTech != null && !civInfo.tech.isResearched(requiredTech!!)) return "$requiredTech not researched"
        if (obsoleteTech != null && civInfo.tech.isResearched(obsoleteTech!!)) return "Obsolete by $obsoleteTech"
        if (uniqueTo != null && uniqueTo != civInfo.civName) return "Unique to $uniqueTo"
        if (civInfo.gameInfo.ruleSet.units.values.any { it.uniqueTo == civInfo.civName && it.replaces == name })
            return "Our unique unit replaces this"
        if (!civInfo.gameInfo.gameParameters.nuclearWeaponsEnabled && isNuclearWeapon()
        ) return "Disabled by setting"

        for (unique in uniqueObjects.filter { it.placeholderText == "Unlocked with []" })
            if (civInfo.tech.researchedTechnologies.none { it.era() == unique.params[0] || it.name == unique.params[0] }
                    && !civInfo.policies.isAdopted(unique.params[0]))
                return unique.text

        for (unique in uniqueObjects.filter { it.placeholderText == "Requires []" }) {
            val filter = unique.params[0]
            if (filter in civInfo.gameInfo.ruleSet.buildings) {
                if (civInfo.cities.none { it.cityConstructions.containsBuildingOrEquivalent(filter) }) return unique.text // Wonder is not built
            } else if (!civInfo.policies.adoptedPolicies.contains(filter)) return "Policy is not adopted"
        }

        for ((resource, amount) in getResourceRequirements())
            if (civInfo.getCivResourcesByName()[resource]!! < amount) {
                if (amount == 1) return "Consumes 1 [$resource]" // Again, to preserve existing translations
                else return "Consumes [$amount] [$resource]"
            }

        if (uniques.contains(Constants.settlerUnique) && civInfo.isCityState()) return "No settler for city-states"
        if (uniques.contains(Constants.settlerUnique) && civInfo.isOneCityChallenger()) return "No settler for players in One City Challenge"
        return ""
    }

    fun isBuildable(civInfo: CivilizationInfo) = getRejectionReason(civInfo) == ""

    override fun isBuildable(cityConstructions: CityConstructions): Boolean {
        return getRejectionReason(cityConstructions) == ""
    }

    override fun postBuildEvent(cityConstructions: CityConstructions, wasBought: Boolean): Boolean {
        val civInfo = cityConstructions.cityInfo.civInfo
        val unit = civInfo.placeUnitNearTile(cityConstructions.cityInfo.location, name)
        if (unit == null) return false // couldn't place the unit, so there's actually no unit =(

        //movement penalty
        if (wasBought && !civInfo.gameInfo.gameParameters.godMode && !unit.hasUnique("Can move immediately once bought"))
            unit.currentMovement = 0f

        if (unit.hasUnique("Religious Unit")) {
            unit.religion = cityConstructions.cityInfo.religion.getMajorityReligion()
        }
        
        if (this.unitType.isCivilian()) return true // tiny optimization makes save files a few bytes smaller

        var XP = cityConstructions.getBuiltBuildings().sumBy { it.xpForNewUnits }


        for (unique in 
            cityConstructions.cityInfo.getMatchingUniques("New [] units start with [] Experience") +
            cityConstructions.cityInfo.getMatchingUniques("New [] units start with [] Experience []")
                .filter { cityConstructions.cityInfo.matchesFilter(it.params[2]) } +
            // Deprecated since 3.15.9
                cityConstructions.cityInfo.getLocalMatchingUniques("New [] units start with [] Experience in this city")
            //
        ) {
            if (unit.matchesFilter(unique.params[0]))
                XP += unique.params[1].toInt()
        }
        unit.promotions.XP = XP

        for (unique in 
            cityConstructions.cityInfo.getMatchingUniques("All newly-trained [] units [] receive the [] promotion")
                .filter { cityConstructions.cityInfo.matchesFilter(it.params[1]) } +
            // Deprecated since 3.15.9
                cityConstructions.cityInfo.getLocalMatchingUniques("All newly-trained [] units in this city receive the [] promotion")
            //
        ) {
            val filter = unique.params[0]
            val promotion = unique.params.last()

            if (unit.matchesFilter(filter) || 
                (
                    filter == "relevant" && 
                        civInfo.gameInfo.ruleSet.unitPromotions.values
                        .any {
                            it.name == promotion && unit.type.name in it.unitTypes 
                        }
                )
            ) {
                unit.promotions.addPromotion(promotion, isFree = true)
            }
        }

        return true
    }


    fun getDirectUpgradeUnit(civInfo: CivilizationInfo): BaseUnit {
        return civInfo.getEquivalentUnit(upgradesTo!!)
    }

    override fun toString(): String = name

    fun getReplacedUnit(ruleset: Ruleset): BaseUnit {
        return if (replaces == null) this
        else ruleset.units[replaces!!]!!
    }

    fun matchesFilter(filter: String): Boolean {
        return when (filter) {
            unitType.name -> true
            name -> true
            "All" -> true
            
            "Melee" -> unitType.isMelee()
            "Ranged" -> unitType.isRanged()
            "Land", "land units" -> unitType.isLandUnit()
            "Civilian" -> unitType.isCivilian()
            "Military", "military units" -> unitType.isMilitary()
            "Water", "water units" -> unitType.isWaterUnit()
            "Air", "air units" -> unitType.isAirUnit()
            "non-air" -> !unitType.isAirUnit() && !unitType.isMissile()
            "Missile" -> unitType.isMissile()
            
            "Submarine", "submarine units" -> unitType == UnitType.WaterSubmarine
            "Nuclear Weapon" -> isNuclearWeapon()
            // Deprecated as of 3.15.2
            "military water" -> unitType.isMilitary() && unitType.isWaterUnit()
            else -> {
                if (uniques.contains(filter)) return true
                return false
            }
        }
    }

    fun isGreatPerson() = uniqueObjects.any { it.placeholderText == "Great Person - []" }

    // "Nuclear Weapon" unique deprecated since 3.15.4
    fun isNuclearWeapon() = uniqueObjects.any { it.placeholderText == "Nuclear Weapon" || it.placeholderText == "Nuclear weapon of Strength []" }

    fun movesLikeAirUnits() = unitType.isAirUnit() || unitType.isMissile()

    override fun getResourceRequirements(): HashMap<String, Int> {
        val resourceRequirements = HashMap<String, Int>()
        if (requiredResource != null) resourceRequirements[requiredResource!!] = 1
        for (unique in uniqueObjects)
            if (unique.placeholderText == "Consumes [] []")
                resourceRequirements[unique.params[1]] = unique.params[0].toInt()
        return resourceRequirements
    }
}
