package com.unciv.logic.battle

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.city.CityInfo
import com.unciv.logic.civilization.*
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.map.RoadStatus
import com.unciv.logic.map.TileInfo
import com.unciv.models.AttackableTile
import com.unciv.models.ruleset.Unique
import com.unciv.models.ruleset.unit.UnitType
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import java.util.*
import kotlin.math.max

/**
 * Damage calculations according to civ v wiki and https://steamcommunity.com/sharedfiles/filedetails/?id=170194443
 */
object Battle {

    fun moveAndAttack(attacker: ICombatant, attackableTile: AttackableTile) {
        if (attacker is MapUnitCombatant) {
            attacker.unit.movement.moveToTile(attackableTile.tileToAttackFrom)
            if (attacker.unit.hasUnique("Must set up to ranged attack") && attacker.unit.action != Constants.unitActionSetUp) {
                attacker.unit.action = Constants.unitActionSetUp
                attacker.unit.useMovementPoints(1f)
            }
        }

        if (attacker is MapUnitCombatant && attacker.unit.baseUnit.isNuclearWeapon()
        ) {
            return NUKE(attacker, attackableTile.tileToAttack)
        }
        attack(attacker, getMapCombatantOfTile(attackableTile.tileToAttack)!!)
    }

    fun attack(attacker: ICombatant, defender: ICombatant) {
        if (UncivGame.Current.alertBattle) {
            println(attacker.getCivInfo().civName + " " + attacker.getName() + " attacked " +
                    defender.getCivInfo().civName + " " + defender.getName())
        }
        val attackedTile = defender.getTile()

        if (attacker is MapUnitCombatant && attacker.getUnitType().isAirUnit()) {
            tryInterceptAirAttack(attacker, defender)
            if (attacker.isDefeated()) return
        }

        // Withdraw from melee ability
        if (attacker is MapUnitCombatant && attacker.isMelee() && defender is MapUnitCombatant) {
            val withdraw = defender.unit.getMatchingUniques("May withdraw before melee ([]%)")
                .maxByOrNull{ it.params[0] }  // If a mod allows multiple withdraw properties, ensure the best is used
            if (withdraw != null && doWithdrawFromMeleeAbility(attacker, defender, withdraw)) return
        }

        val isAlreadyDefeatedCity = defender is CityCombatant && defender.isDefeated()

        takeDamage(attacker, defender)

        postBattleNotifications(attacker, defender, attackedTile, attacker.getTile())

        postBattleNationUniques(defender, attackedTile, attacker)

        // This needs to come BEFORE the move-to-tile, because if we haven't conquered it we can't move there =)
        if (defender.isDefeated() && defender is CityCombatant && attacker is MapUnitCombatant && attacker.isMelee() && !attacker.unit.hasUnique("Unable to capture cities"))
            conquerCity(defender.city, attacker)

        // Exploring units surviving an attack should "wake up"
        if (!defender.isDefeated() && defender is MapUnitCombatant && defender.unit.action == Constants.unitActionExplore)
            defender.unit.action = null

        // Add culture when defeating a barbarian when Honor policy is adopted, gold from enemy killed when honor is complete
        // or any enemy military unit with Sacrificial captives unique (can be either attacker or defender!)
        // or check if unit is captured by the attacker (prize ships unique)
        if (defender.isDefeated() && defender is MapUnitCombatant && !defender.getUnitType().isCivilian()) {
            tryEarnFromKilling(attacker, defender)
            tryCaptureUnit(attacker, defender)
            tryHealAfterKilling(attacker)
        } else if (attacker.isDefeated() && attacker is MapUnitCombatant && !attacker.getUnitType().isCivilian()) {
            tryEarnFromKilling(defender, attacker)
            tryCaptureUnit(defender, attacker)
            tryHealAfterKilling(defender)
        }

        if (attacker is MapUnitCombatant) {
            if (attacker.unit.hasUnique("Self-destructs when attacking"))
                attacker.unit.destroy()
            else if (attacker.unit.isMoving())
                attacker.unit.action = null
        }

        // we're a melee unit and we destroyed\captured an enemy unit
        // Should be called after tryCaptureUnit(), as that might spawn a unit on the tile we go to
        postBattleMoveToAttackedTile(attacker, defender, attackedTile)

        reduceAttackerMovementPointsAndAttacks(attacker, defender)

        if (!isAlreadyDefeatedCity) postBattleAddXp(attacker, defender)
    }

    private fun tryEarnFromKilling(civUnit: ICombatant, defeatedUnit: MapUnitCombatant) {
        val unitStr = max(defeatedUnit.unit.baseUnit.strength, defeatedUnit.unit.baseUnit.rangedStrength)
        val unitCost = defeatedUnit.unit.baseUnit.cost
        var bonusUniquePlaceholderText = "Earn []% of killed [] unit's [] as []"

        val bonusUniques = ArrayList<Unique>()

        bonusUniques.addAll(civUnit.getCivInfo().getMatchingUniques(bonusUniquePlaceholderText))

        if (civUnit is MapUnitCombatant) {
            bonusUniques.addAll(civUnit.unit.getMatchingUniques(bonusUniquePlaceholderText))
        }
        
        bonusUniquePlaceholderText = "Earn []% of [] unit's [] as [] when killed within 4 tiles of a city following this religion"
        val cityWithReligion =
            civUnit.getTile().getTilesInDistance(4).firstOrNull {
                it.isCityCenter() && it.getCity()!!.getMatchingUniques(bonusUniquePlaceholderText).any()
            }?.getCity()
        if (cityWithReligion != null) {
            bonusUniques.addAll(cityWithReligion.getLocalMatchingUniques(bonusUniquePlaceholderText))
        }

        for (unique in bonusUniques) {
            if (!defeatedUnit.matchesCategory(unique.params[1])) continue

            val yieldPercent = unique.params[0].toFloat() / 100
            val defeatedUnitYieldSourceType = unique.params[2]
            val yieldTypeSourceAmount =
                if (defeatedUnitYieldSourceType == "Cost") unitCost else unitStr
            val yieldAmount = (yieldTypeSourceAmount * yieldPercent).toInt()

            try {
                val stat = Stat.valueOf(unique.params[3])
                civUnit.getCivInfo().addStat(stat, yieldAmount)
            } catch (ex: Exception) {
            } // parameter is not a stat
        }
    }

    private fun tryCaptureUnit(attacker: ICombatant, defender: ICombatant) {
        // https://forums.civfanatics.com/threads/prize-ships-for-land-units.650196/
        // https://civilization.fandom.com/wiki/Module:Data/Civ5/GK/Defines
        if (!defender.isDefeated()) return
        if (attacker !is MapUnitCombatant) return
        if (defender is MapUnitCombatant && !defender.getUnitType().isMilitary()) return
        if (attacker.unit.getMatchingUniques("May capture killed [] units").none { defender.matchesCategory(it.params[0]) }) return

        var captureChance = 10 + attacker.getAttackingStrength().toFloat() / defender.getDefendingStrength().toFloat() * 40
        if (captureChance > 80) captureChance = 80f
        if (100 * Random().nextFloat() > captureChance) return

        val newUnit = attacker.getCivInfo().placeUnitNearTile(defender.getTile().position, defender.getName())
        if (newUnit == null) return // silently fail
        attacker.getCivInfo().addNotification("Your [${attacker.getName()}] captured an enemy [${defender.getName()}]", newUnit.getTile().position, NotificationIcon.War)

        newUnit.currentMovement = 0f
        newUnit.health = 50
    }

    private fun takeDamage(attacker: ICombatant, defender: ICombatant) {
        var potentialDamageToDefender = BattleDamage.calculateDamageToDefender(attacker, attacker.getTile(), defender)
        var potentialDamageToAttacker = BattleDamage.calculateDamageToAttacker(attacker, attacker.getTile(), defender)

        var damageToAttacker = attacker.getHealth() // These variables names don't make any sense as of yet ...
        var damageToDefender = defender.getHealth()

        if (defender.getUnitType().isCivilian() && attacker.isMelee()) {
            captureCivilianUnit(attacker, defender as MapUnitCombatant)
        } else if (attacker.isRanged()) {
            defender.takeDamage(potentialDamageToDefender) // straight up
        } else {
            //melee attack is complicated, because either side may defeat the other midway
            //so...for each round, we randomize who gets the attack in. Seems to be a good way to work for now.

            while (potentialDamageToDefender + potentialDamageToAttacker > 0) {
                if (Random().nextInt(potentialDamageToDefender + potentialDamageToAttacker) < potentialDamageToDefender) {
                    potentialDamageToDefender--
                    defender.takeDamage(1)
                    if (defender.isDefeated()) break
                } else {
                    potentialDamageToAttacker--
                    attacker.takeDamage(1)
                    if (attacker.isDefeated()) break
                }
            }
        }

        damageToAttacker -= attacker.getHealth() // ... but from here on they are accurate
        damageToDefender -= defender.getHealth()

        plunderFromDamage(attacker, defender, damageToDefender)
        plunderFromDamage(defender, attacker, damageToAttacker)
    }

    private fun plunderFromDamage(plunderingUnit: ICombatant, plunderedUnit: ICombatant, damageDealt: Int) {
        val plunderedGoods = Stats()
        if (plunderingUnit !is MapUnitCombatant) return
        
        for (unique in plunderingUnit.unit.getMatchingUniques("Earn []% of the damage done to [] units as []")) {
            if (plunderedUnit.matchesCategory(unique.params[1])) {
                val resourcesPlundered =
                    unique.params[0].toFloat() / 100f * damageDealt
                plunderedGoods.add(Stat.valueOf(unique.params[2]), resourcesPlundered)
            }
        }
        
        val plunderableStats = listOf("Gold", "Science", "Culture", "Faith").map { Stat.valueOf(it) }
        for (stat in plunderableStats) {
            val resourcesPlundered = plunderedGoods.get(stat)
            if (resourcesPlundered == 0f) continue
            plunderingUnit.getCivInfo().addStat(stat, resourcesPlundered.toInt())
            plunderingUnit.getCivInfo()
                .addNotification(
                    "Your [${plunderingUnit.getName()}] plundered [${resourcesPlundered}] [${stat.name}] from [${plunderedUnit.getName()}]",
                        plunderedUnit.getTile().position,
                        NotificationIcon.War
                )
            
            
        }
    }

    private fun postBattleNotifications(
        attacker: ICombatant,
        defender: ICombatant,
        attackedTile: TileInfo,
        attackerTile: TileInfo? = null
    ) {
        if (attacker.getCivInfo() != defender.getCivInfo()) { // If what happened was that a civilian unit was captures, that's dealt with in the captureCivilianUnit function
            val whatHappenedString =
                    if (attacker !is CityCombatant && attacker.isDefeated()) " was destroyed while attacking"
                    else " has " + (
                            if (defender.isDefeated())
                                if (defender.getUnitType() == UnitType.City && attacker.isMelee())
                                    "captured"
                                else "destroyed"
                            else "attacked")
            val attackerString =
                    if (attacker.getUnitType() == UnitType.City) "Enemy city [" + attacker.getName() + "]"
                    else "An enemy [" + attacker.getName() + "]"
            val defenderString =
                    if (defender.getUnitType() == UnitType.City)
                        if (defender.isDefeated() && attacker.isRanged()) " the defence of [" + defender.getName() + "]"
                        else " [" + defender.getName() + "]"
                    else " our [" + defender.getName() + "]"
            val notificationString = attackerString + whatHappenedString + defenderString
            val cityIcon = "ImprovementIcons/Citadel"
            val attackerIcon = if (attacker is CityCombatant) cityIcon else attacker.getName()
            val defenderIcon = if (defender is CityCombatant) cityIcon else defender.getName()
            val locations = LocationAction (
                if (attackerTile != null && attackerTile.position != attackedTile.position)
                        listOf(attackedTile.position, attackerTile.position)
                else listOf(attackedTile.position)
            )
            defender.getCivInfo().addNotification(notificationString, locations, attackerIcon, NotificationIcon.War, defenderIcon)
        }
    }

    private fun tryHealAfterKilling(attacker: ICombatant) {
        if (attacker is MapUnitCombatant)
            for (unique in attacker.unit.getMatchingUniques("Heals [] damage if it kills a unit")) {
                val amountToHeal = unique.params[0].toInt()
                attacker.unit.healBy(amountToHeal)
            }
    }

    private fun postBattleNationUniques(defender: ICombatant, attackedTile: TileInfo, attacker: ICombatant) {
        // German unique - needs to be checked before we try to move to the enemy tile, since the encampment disappears after we move in
        if (defender.isDefeated() && defender.getCivInfo().isBarbarian()
                && attackedTile.improvement == Constants.barbarianEncampment
                && attacker.getCivInfo().hasUnique("67% chance to earn 25 Gold and recruit a Barbarian unit from a conquered encampment")
                && Random().nextDouble() < 0.67) {
            attacker.getCivInfo().placeUnitNearTile(attackedTile.position, defender.getName())
            attacker.getCivInfo().addGold(25)
            attacker.getCivInfo().addNotification("A barbarian [${defender.getName()}] has joined us!", attackedTile.position, defender.getName())
        }

        // Similarly, Ottoman unique
        if (defender.isDefeated() && defender.getUnitType().isWaterUnit() && defender.getCivInfo().isBarbarian()
                && attacker.isMelee() && attacker.getUnitType().isWaterUnit()
                && attacker.getCivInfo().hasUnique("50% chance of capturing defeated Barbarian naval units and earning 25 Gold")
                && Random().nextDouble() > 0.5) {
            attacker.getCivInfo().placeUnitNearTile(attackedTile.position, defender.getName())
            attacker.getCivInfo().addGold(25)
        }
    }

    private fun postBattleMoveToAttackedTile(attacker: ICombatant, defender: ICombatant, attackedTile: TileInfo) {
        if (attacker.isMelee()
                && (defender.isDefeated() || defender.getCivInfo() == attacker.getCivInfo())
                // This is so that if we attack e.g. a barbarian in enemy territory that we can't enter, we won't enter it
                && (attacker as MapUnitCombatant).unit.movement.canMoveTo(attackedTile)) {
            // we destroyed an enemy military unit and there was a civilian unit in the same tile as well
            if (attackedTile.civilianUnit != null && attackedTile.civilianUnit!!.civInfo != attacker.getCivInfo())
                captureCivilianUnit(attacker, MapUnitCombatant(attackedTile.civilianUnit!!))
            // Units that can move after attacking are not affected by zone of control if the
            // movement is caused by killing a unit. Effectively, this means that attack movements
            // are exempt from zone of control, since units that cannot move after attacking already
            // lose all remaining movement points anyway.
            attacker.unit.movement.moveToTile(attackedTile, considerZoneOfControl = false)
        }
    }

    private fun postBattleAddXp(attacker: ICombatant, defender: ICombatant) {
        if (attacker.isMelee()) {
            if (!defender.getUnitType().isCivilian()) // unit was not captured but actually attacked
            {
                addXp(attacker, 5, defender)
                addXp(defender, 4, attacker)
            }
        } else { // ranged attack
            addXp(attacker, 2, defender)
            addXp(defender, 2, attacker)
        }
    }

    private fun reduceAttackerMovementPointsAndAttacks(attacker: ICombatant, defender: ICombatant) {
        if (attacker is MapUnitCombatant) {
            val unit = attacker.unit
            unit.attacksThisTurn += 1
            if (unit.hasUnique("Can move after attacking") || unit.maxAttacksPerTurn() > unit.attacksThisTurn) {
                // if it was a melee attack and we won, then the unit ALREADY got movement points deducted,
                // for the movement to the enemy's tile!
                // and if it's an air unit, it only has 1 movement anyway, so...
                if (!attacker.unit.baseUnit.movesLikeAirUnits() && !(attacker.getUnitType().isMelee() && defender.isDefeated()))
                    unit.useMovementPoints(1f)
            } else unit.currentMovement = 0f
            if (unit.isFortified() || unit.isSleeping())
                attacker.unit.action = null // but not, for instance, if it's Set Up - then it should definitely keep the action!
        } else if (attacker is CityCombatant) {
            attacker.city.attackedThisTurn = true
        }
    }

    // XP!
    private fun addXp(thisCombatant: ICombatant, amount: Int, otherCombatant: ICombatant) {
        if (thisCombatant !is MapUnitCombatant) return
        if (thisCombatant.unit.promotions.totalXpProduced() >= thisCombatant.unit.civInfo.gameInfo.ruleSet.modOptions.maxXPfromBarbarians
                && otherCombatant.getCivInfo().isBarbarian())
            return

        var xpModifier = 1f
        for (unique in thisCombatant.getCivInfo().getMatchingUniques("[] units gain []% more Experience from combat")) {
            if (thisCombatant.unit.matchesFilter(unique.params[0]))
                xpModifier += unique.params[1].toFloat() / 100
        }
        for (unique in thisCombatant.unit.getMatchingUniques("[]% Bonus XP gain"))
            xpModifier += unique.params[0].toFloat() / 100

        val xpGained = (amount * xpModifier).toInt()
        thisCombatant.unit.promotions.XP += xpGained


        if (thisCombatant.getCivInfo().isMajorCiv()) {
            var greatGeneralPointsModifier = 1f
            val unitUniques = thisCombatant.unit.getMatchingUniques("[] is earned []% faster")
            val civUniques = thisCombatant.getCivInfo().getMatchingUniques("[] is earned []% faster")
            for (unique in unitUniques + civUniques) {
                val unitName = unique.params[0]
                val unit = thisCombatant.getCivInfo().gameInfo.ruleSet.units[unitName]
                if (unit != null && unit.uniques.contains("Great Person - [War]"))
                    greatGeneralPointsModifier += unique.params[1].toFloat() / 100
            }

            val greatGeneralPointsGained = (xpGained * greatGeneralPointsModifier).toInt()
            thisCombatant.getCivInfo().greatPeople.greatGeneralPoints += greatGeneralPointsGained
        }
    }

    private fun conquerCity(city: CityInfo, attacker: ICombatant) {
        val attackerCiv = attacker.getCivInfo()

        attackerCiv.addNotification("We have conquered the city of [${city.name}]!", city.location, NotificationIcon.War)

        city.getCenterTile().apply {
            if (militaryUnit != null) militaryUnit!!.destroy()
            if (civilianUnit != null) captureCivilianUnit(attacker, MapUnitCombatant(civilianUnit!!))
            for (airUnit in airUnits.toList()) airUnit.destroy()
        }
        city.hasJustBeenConquered = true

        for (unique in attackerCiv.getMatchingUniques("Upon capturing a city, receive [] times its [] production as [] immediately")) {
            attackerCiv.addStat(
                Stat.valueOf(unique.params[2]),
                unique.params[0].toInt() * city.cityStats.currentCityStats.get(Stat.valueOf(unique.params[1])).toInt()
            )
        }

        if (attackerCiv.isBarbarian() || attackerCiv.isOneCityChallenger()) {
            city.destroyCity(true)
            return
        }

        if (attackerCiv.isPlayerCivilization()) {
            attackerCiv.popupAlerts.add(PopupAlert(AlertType.CityConquered, city.id))
            UncivGame.Current.settings.addCompletedTutorialTask("Conquer a city")
        } else {
            city.puppetCity(attackerCiv)
            if (city.population.population < 4 && city.canBeDestroyed()) {
                city.annexCity()
                city.isBeingRazed = true
            }
        }
    }

    fun getMapCombatantOfTile(tile: TileInfo): ICombatant? {
        if (tile.isCityCenter()) return CityCombatant(tile.getCity()!!)
        if (tile.militaryUnit != null) return MapUnitCombatant(tile.militaryUnit!!)
        if (tile.civilianUnit != null) return MapUnitCombatant(tile.civilianUnit!!)
        return null
    }

    private fun captureCivilianUnit(attacker: ICombatant, defender: MapUnitCombatant) {
        // barbarians don't capture civilians
        if (attacker.getCivInfo().isBarbarian()
                || defender.unit.hasUnique("Uncapturable")) {
            defender.takeDamage(100)
            return
        }

        // need to save this because if the unit is captured its owner wil be overwritten
        val defenderCiv = defender.getCivInfo()

        val capturedUnit = defender.unit
        capturedUnit.civInfo.addNotification("An enemy [" + attacker.getName() + "] has captured our [" + defender.getName() + "]",
                defender.getTile().position, attacker.getName(), NotificationIcon.War, defender.getName())

        // Apparently in Civ V, captured settlers are converted to workers.
        if (capturedUnit.name == Constants.settler) {
            val tile = capturedUnit.getTile()
            capturedUnit.destroy()
            // This is so that future checks which check if a unit has been captured are caught give the right answer
            //  For example, in postBattleMoveToAttackedTile
            capturedUnit.civInfo = attacker.getCivInfo()
            attacker.getCivInfo().placeUnitNearTile(tile.position, Constants.worker)
        } else {
            capturedUnit.civInfo.removeUnit(capturedUnit)
            capturedUnit.assignOwner(attacker.getCivInfo())
            capturedUnit.currentMovement = 0f
        }

        destroyIfDefeated(defenderCiv, attacker.getCivInfo())
        capturedUnit.updateVisibleTiles()
    }

    fun destroyIfDefeated(attackedCiv: CivilizationInfo, attacker: CivilizationInfo) {
        if (attackedCiv.isDefeated()) {
            attackedCiv.destroy()
            attacker.popupAlerts.add(PopupAlert(AlertType.Defeated, attackedCiv.civName))
        }
    }

    fun NUKE(attacker: MapUnitCombatant, targetTile: TileInfo) {
        val attackingCiv = attacker.getCivInfo()
        fun tryDeclareWar(civSuffered: CivilizationInfo) {
            if (civSuffered != attackingCiv
                && civSuffered.knows(attackingCiv)
                && civSuffered.getDiplomacyManager(attackingCiv).diplomaticStatus != DiplomaticStatus.War
            ) {
                attackingCiv.getDiplomacyManager(civSuffered).declareWar()
                attackingCiv.addNotification("After being hit by our [${attacker.getName()}], [${civSuffered}] has declared war on us!", targetTile.position, NotificationIcon.War)
            }
        }

        val blastRadius =
            if (!attacker.unit.hasUnique("Blast radius []")) 2
            else attacker.unit.getMatchingUniques("Blast radius []").first().params[0].toInt()

        val strength = when {
            (attacker.unit.hasUnique("Nuclear weapon of Strength []")) ->
                attacker.unit.getMatchingUniques("Nuclear weapon of Strength []").first().params[0].toInt()
            else -> return
        }

        // Calculate the tiles that are hit
        val hitTiles = targetTile.getTilesInDistance(blastRadius)

        // Declare war on the owners of all hit tiles
        for (hitCiv in hitTiles.mapNotNull { it.getOwner() }.distinct()) {
            hitCiv.addNotification("A(n) [${attacker.getName()}] exploded in our territory!", targetTile.position, NotificationIcon.War)
            tryDeclareWar(hitCiv)
        }

        // Declare war on all potentially hit units. They'll try to intercept the nuke before it drops
        for (hitUnit in hitTiles.map { it.getUnits() }.flatten()) {
            tryDeclareWar(hitUnit.civInfo)
            if (attacker.getUnitType().isAirUnit() && !attacker.isDefeated()) {
                tryInterceptAirAttack(attacker, MapUnitCombatant(hitUnit))
            }
        }
        if (attacker.isDefeated()) return

        // Destroy units on the target tile
        // Needs the toList() because if we're destroying the units, they're no longer part of the sequence
        for (defender in targetTile.getUnits().filter { it != attacker.unit }.toList()) {
            defender.destroy()
            postBattleNotifications(attacker, MapUnitCombatant(defender), defender.getTile())
            destroyIfDefeated(defender.civInfo, attacker.getCivInfo())
        }

        for (tile in hitTiles) {
            // Handle complicated effects
            when (strength) {
                1 -> nukeStrength1Effect(attacker, tile)
                2 -> nukeStrength2Effect(attacker, tile)
                else -> nukeStrength1Effect(attacker, tile)
            }
        }

        // Instead of postBattleAction() just destroy the unit, all other functions are not relevant
        if (attacker.unit.hasUnique("Self-destructs when attacking")) {
            attacker.unit.destroy()
        }

        // It's unclear whether using nukes results in a penalty with all civs, or only affected civs.
        // For now I'll make it give a diplomatic penalty to all known civs, but some testing for this would be appreciated
        for (civ in attackingCiv.getKnownCivs()) {
            civ.getDiplomacyManager(attackingCiv).setModifier(DiplomaticModifiers.UsedNuclearWeapons, -50f)
        }
    }

    private fun nukeStrength1Effect(attacker: MapUnitCombatant, tile: TileInfo) {
        // https://forums.civfanatics.com/resources/unit-guide-modern-future-units-g-k.25628/
        // https://www.carlsguides.com/strategy/civilization5/units/aircraft-nukes.php
        // Testing done by Ravignir
        var damageModifierFromMissingResource = 1f
        val civResources = attacker.getCivInfo().getCivResourcesByName()
        for (resource in attacker.unit.baseUnit.getResourceRequirements().keys) {
            if (civResources[resource]!! < 0 && !attacker.getCivInfo().isBarbarian())
                damageModifierFromMissingResource *= 0.5f // I could not find a source for this number, but this felt about right
        }

        // Decrease health & population of a hit city
        val city = tile.getCity()
        if (city != null && tile.position == city.location) {
            var populationLoss = city.population.population * (0.3 + Random().nextFloat() * 0.4)
            var populationLossReduced = false
            for (unique in city.civInfo.getMatchingUniques("Population loss from nuclear attacks -[]%")) {
                populationLoss *= 1 - unique.params[0].toFloat() / 100f
                populationLossReduced = true
            }
            if (city.population.population < 5 && !populationLossReduced) {
                city.population.setPopulation(1) // For cities that cannot be destroyed, such as original capitals
                city.destroyCity()
            } else {
                city.population.addPopulation(-populationLoss.toInt())
                if (city.population.population < 1) city.population.setPopulation(1)
                city.population.unassignExtraPopulation()
                city.health -= ((0.5 + 0.25 * Random().nextFloat()) * city.health * damageModifierFromMissingResource).toInt()
                if (city.health < 1) city.health = 1
            }
            postBattleNotifications(attacker, CityCombatant(city), city.getCenterTile())
        }

        // Damage and/or destroy units on the tile
        for (unit in tile.getUnits().toList()) { // tolist so if it's destroyed there's no concurrent modification
            val defender = MapUnitCombatant(unit)
            if (defender.unit.baseUnit.unitType.isCivilian()) {
                unit.destroy() // destroy the unit
            } else {
                defender.takeDamage(((40 + Random().nextInt(60)) * damageModifierFromMissingResource).toInt())
            }
            postBattleNotifications(attacker, defender, defender.getTile())
            destroyIfDefeated(defender.getCivInfo(), attacker.getCivInfo())
        }

        // Remove improvements, add fallout
        if (tile.improvement != null && !tile.getTileImprovement()!!.hasUnique("Indestructible")) {
            tile.improvement = null
        }
        tile.improvementInProgress = null
        tile.turnsToImprovement = 0
        tile.roadStatus = RoadStatus.None
        if (tile.isLand && !tile.isImpassible() && !tile.terrainFeatures.contains("Fallout")) {
            if (tile.terrainFeatures.any { attacker.getCivInfo().gameInfo.ruleSet.terrains[it]!!.uniques.contains("Resistant to nukes") }) {
                if (Random().nextFloat() < 0.25f) {
                    tile.terrainFeatures.removeAll { attacker.getCivInfo().gameInfo.ruleSet.terrains[it]!!.uniques.contains("Can be destroyed by nukes") }
                    tile.terrainFeatures.add("Fallout")
                }
            } else if (Random().nextFloat() < 0.5f) {
                tile.terrainFeatures.removeAll { attacker.getCivInfo().gameInfo.ruleSet.terrains[it]!!.uniques.contains("Can be destroyed by nukes") }
                tile.terrainFeatures.add("Fallout")
            }
        }
    }

    private fun nukeStrength2Effect(attacker: MapUnitCombatant, tile: TileInfo) {
        // https://forums.civfanatics.com/threads/unit-guide-modern-future-units-g-k.429987/#2
        // https://www.carlsguides.com/strategy/civilization5/units/aircraft-nukes.php
        // Testing done by Ravignir
        var damageModifierFromMissingResource = 1f
        val civResources = attacker.getCivInfo().getCivResourcesByName()
        for (resource in attacker.unit.baseUnit.getResourceRequirements().keys) {
            if (civResources[resource]!! < 0 && !attacker.getCivInfo().isBarbarian())
                damageModifierFromMissingResource *= 0.5f // I could not find a source for this number, but this felt about right
        }

        // Damage and/or destroy cities
        val city = tile.getCity()
        if (city != null && city.location == tile.position) {
            if (city.population.population < 5) {
                city.population.setPopulation(1) // For cities that cannot be destroyed, such as original capitals
                city.destroyCity()
            } else {
                var populationLoss = city.population.population * (0.6 + Random().nextFloat() * 0.2);
                var populationLossReduced = false
                for (unique in city.civInfo.getMatchingUniques("Population loss from nuclear attacks -[]%")) {
                    populationLoss *= 1 - unique.params[0].toFloat() / 100f
                    populationLossReduced = true
                }
                city.population.addPopulation(-populationLoss.toInt())
                if (city.population.population < 5 && populationLossReduced) city.population.setPopulation(5)
                if (city.population.population < 1) city.population.setPopulation(1)
                city.population.unassignExtraPopulation()
                city.health -= (0.5 * city.getMaxHealth() * damageModifierFromMissingResource).toInt()
                if (city.health < 1) city.health = 1
            }
            postBattleNotifications(attacker, CityCombatant(city), city.getCenterTile())
            destroyIfDefeated(city.civInfo, attacker.getCivInfo())
        }

        // Destroy all hit units
        for (defender in tile.getUnits().toList()) { // toList to avoid concurent modification exceptions
            defender.destroy()
            postBattleNotifications(attacker, MapUnitCombatant(defender), defender.currentTile)
            destroyIfDefeated(defender.civInfo, attacker.getCivInfo())
        }

        // Remove improvements
        if (tile.improvement != null && !tile.getTileImprovement()!!.hasUnique("Indestructible")) {
            tile.improvement = null
        }
        tile.improvementInProgress = null
        tile.turnsToImprovement = 0
        tile.roadStatus = RoadStatus.None
        if (tile.isLand && !tile.isImpassible() && !tile.terrainFeatures.contains("Fallout")) {
            if (tile.terrainFeatures.any { attacker.getCivInfo().gameInfo.ruleSet.terrains[it]!!.uniques.contains("Resistant to nukes") }) {
                if (Random().nextFloat() < 0.25f) {
                    tile.terrainFeatures.removeAll { attacker.getCivInfo().gameInfo.ruleSet.terrains[it]!!.uniques.contains("Can be destroyed by nukes") }
                    tile.terrainFeatures.add("Fallout")
                }
            } else if (Random().nextFloat() < 0.5f) {
                tile.terrainFeatures.removeAll { attacker.getCivInfo().gameInfo.ruleSet.terrains[it]!!.uniques.contains("Can be destroyed by nukes") }
                tile.terrainFeatures.add("Fallout")
            }
        }
    }

    private fun tryInterceptAirAttack(attacker: MapUnitCombatant, defender: ICombatant) {
        if (attacker.unit.hasUnique("Cannot be intercepted")) return
        // Deprecated since 3.15.6
            if (attacker.unit.hasUnique("Can not be intercepted")) return
        // End deprecation
        val attackedTile = defender.getTile()
        for (interceptor in defender.getCivInfo().getCivUnits().filter { it.canIntercept(attackedTile) }) {
            if (Random().nextFloat() > 100f / interceptor.interceptChance()) continue

            var damage = BattleDamage.calculateDamageToDefender(MapUnitCombatant(interceptor), null, attacker)

            var damageFactor = 1f + interceptor.interceptDamagePercentBonus().toFloat() / 100f
            damageFactor *= attacker.unit.receivedInterceptDamageFactor()

            damage = (damage.toFloat() * damageFactor).toInt()

            attacker.takeDamage(damage)
            interceptor.attacksThisTurn++

            val attackerName = attacker.getName()
            val interceptorName = interceptor.name
            val locations = LocationAction(listOf(interceptor.currentTile.position, attacker.unit.currentTile.position))

            if (attacker.isDefeated()) {
                attacker.getCivInfo()
                        .addNotification("Our [$attackerName] was destroyed by an intercepting [$interceptorName]",
                            interceptor.currentTile.position, attackerName, NotificationIcon.War, interceptorName)
                defender.getCivInfo()
                        .addNotification("Our [$interceptorName] intercepted and destroyed an enemy [$attackerName]",
                            locations, interceptorName, NotificationIcon.War, attackerName)
            } else {
                attacker.getCivInfo()
                        .addNotification("Our [$attackerName] was attacked by an intercepting [$interceptorName]",
                            interceptor.currentTile.position, attackerName, NotificationIcon.War, interceptorName)
                defender.getCivInfo()
                        .addNotification("Our [$interceptorName] intercepted and attacked an enemy [$attackerName]",
                            locations, interceptorName, NotificationIcon.War, attackerName)
            }
            return
        }
    }

    private fun doWithdrawFromMeleeAbility(attacker: ICombatant, defender: ICombatant, withdrawUnique: Unique): Boolean {
        // Some notes...
        // unit.getUniques() is a union of BaseUnit uniques and Promotion effects.
        // according to some strategy guide the Slinger's withdraw ability is inherited on upgrade,
        // according to the Ironclad entry of the wiki the Caravel's is lost on upgrade.
        // therefore: Implement the flag as unique for the Caravel and Destroyer, as promotion for the Slinger.
        if (attacker !is MapUnitCombatant) return false         // allow simple access to unit property
        if (defender !is MapUnitCombatant) return false
        if (defender.unit.isEmbarked()) return false
        // Promotions have no effect as per what I could find in available documentation
        val attackBaseUnit = attacker.unit.baseUnit
        val defendBaseUnit = defender.unit.baseUnit
        val fromTile = defender.getTile()
        val attTile = attacker.getTile()
        fun canNotWithdrawTo(tile: TileInfo): Boolean { // if the tile is what the defender can't withdraw to, this fun will return true
           return !defender.unit.movement.canMoveTo(tile)
                   || defendBaseUnit.unitType.isLandUnit() && !tile.isLand // forbid retreat from land to sea - embarked already excluded
                   || tile.isCityCenter() && tile.getOwner() != defender.getCivInfo() // forbid retreat into the city which doesn't belong to the defender
        }
        // base chance for all units is set to 80%
        val baseChance = withdrawUnique.params[0].toFloat()
        /* Calculate success chance: Base chance from json, calculation method from https://www.bilibili.com/read/cv2216728
        In general, except attacker's tile, 5 tiles neighbors the defender :
        2 of which are also attacker's neighbors ( we call them 2-Tiles) and the other 3 aren't (we call them 3-Tiles).
        Withdraw chance depends on 2 factors : attacker's movement and how many tiles in 3-Tiles the defender can't withdraw to.
        If the defender can withdraw, at first we choose a tile as toTile from 3-Tiles the defender can withdraw to.
        If 3-Tiles the defender can withdraw to is null, we choose this from 2-Tiles the defender can withdraw to.
        If 2-Tiles the defender can withdraw to is also null, we return false.
        */
        val percentChance = baseChance - max(0, (attackBaseUnit.movement-2)) * 20 -
                fromTile.neighbors.filterNot { it == attTile || it in attTile.neighbors }.count { canNotWithdrawTo(it) } * 20
        // Get a random number in [0,100) : if the number <= percentChance, defender will withdraw from melee
        if (Random().nextInt(100) > percentChance) return false
        val firstCandidateTiles = fromTile.neighbors.filterNot { it == attTile || it in attTile.neighbors }
                .filterNot { canNotWithdrawTo(it) }
        val secondCandidateTiles = fromTile.neighbors.filter { it in attTile.neighbors }
                .filterNot { canNotWithdrawTo(it) }
        val toTile: TileInfo = when {
            firstCandidateTiles.any() -> firstCandidateTiles.toList().random()
            secondCandidateTiles.any() -> secondCandidateTiles.toList().random()
            else -> return false
        }
        // Withdraw success: Do it - move defender to toTile for no cost
        // NOT defender.unit.movement.moveToTile(toTile) - we want a free teleport
        defender.unit.removeFromTile()
        defender.unit.putInTile(toTile)
        // and count 1 attack for attacker but leave it in place
        reduceAttackerMovementPointsAndAttacks(attacker, defender)

        val attackingUnit = attackBaseUnit.name; val defendingUnit = defendBaseUnit.name
        val notificationString = "[$defendingUnit] withdrew from a [$attackingUnit]"
        val locations = LocationAction(listOf(toTile.position, attacker.getTile().position))
        defender.getCivInfo().addNotification(notificationString, locations, defendingUnit, NotificationIcon.War, attackingUnit)
        attacker.getCivInfo().addNotification(notificationString, locations, defendingUnit, NotificationIcon.War, attackingUnit)
        return true
    }

}
