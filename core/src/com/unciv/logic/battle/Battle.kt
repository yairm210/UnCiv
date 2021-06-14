package com.unciv.logic.battle

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.city.CityInfo
import com.unciv.logic.civilization.*
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.map.RoadStatus
import com.unciv.logic.map.TileInfo
import com.unciv.models.AttackableTile
import com.unciv.models.ruleset.Unique
import com.unciv.models.ruleset.unit.UnitType
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

        if (attacker is MapUnitCombatant && attacker.unit.hasUnique("Nuclear weapon")) {
            return nuke(attacker, attackableTile.tileToAttack)
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

        // we're a melee unit and we destroyed\captured an enemy unit
        postBattleMoveToAttackedTile(attacker, defender, attackedTile)

        reduceAttackerMovementPointsAndAttacks(attacker, defender)

        if (!isAlreadyDefeatedCity) postBattleAddXp(attacker, defender)

        // Add culture when defeating a barbarian when Honor policy is adopted, gold from enemy killed when honor is complete
        // or any enemy military unit with Sacrificial captives unique (can be either attacker or defender!)
        if (defender.isDefeated() && defender is MapUnitCombatant && !defender.getUnitType().isCivilian()) {
            tryEarnFromKilling(attacker, defender)
            tryHealAfterKilling(attacker)
        } else if (attacker.isDefeated() && attacker is MapUnitCombatant && !attacker.getUnitType().isCivilian()) {
            tryEarnFromKilling(defender, attacker)
            tryHealAfterKilling(defender)
        }

        if (attacker is MapUnitCombatant) {
            if (attacker.unit.hasUnique("Self-destructs when attacking"))
                attacker.unit.destroy()
            else if (attacker.unit.isMoving())
                attacker.unit.action = null
        }
    }

    private fun tryEarnFromKilling(civUnit: ICombatant, defeatedUnit: MapUnitCombatant) {
        val unitStr = max(defeatedUnit.unit.baseUnit.strength, defeatedUnit.unit.baseUnit.rangedStrength)
        val unitCost = defeatedUnit.unit.baseUnit.cost
        val bonusUniquePlaceholderText = "Earn []% of killed [] unit's [] as []"

        var goldReward = 0
        var cultureReward = 0
        val bonusUniques = ArrayList<Unique>()

        bonusUniques.addAll(civUnit.getCivInfo().getMatchingUniques(bonusUniquePlaceholderText))

        if (civUnit is MapUnitCombatant) {
            bonusUniques.addAll(civUnit.unit.getMatchingUniques(bonusUniquePlaceholderText))
        }


        for (unique in bonusUniques) {
            if (!defeatedUnit.matchesCategory(unique.params[1])) continue

            val yieldPercent = unique.params[0].toFloat() / 100
            val defeatedUnitYieldSourceType = unique.params[2]
            val yieldType = unique.params[3]
            val yieldTypeSourceAmount = if (defeatedUnitYieldSourceType == "Cost") unitCost else unitStr
            val yieldAmount = (yieldTypeSourceAmount * yieldPercent).toInt()


            if (yieldType == "Gold")
                goldReward += yieldAmount
            else if (yieldType == "Culture")
                cultureReward += yieldAmount
        }

        civUnit.getCivInfo().policies.addCulture(cultureReward)
        civUnit.getCivInfo().addGold(goldReward)
    }

    private fun takeDamage(attacker: ICombatant, defender: ICombatant) {
        var damageToDefender = BattleDamage.calculateDamageToDefender(attacker, attacker.getTile(), defender)
        var damageToAttacker = BattleDamage.calculateDamageToAttacker(attacker, attacker.getTile(), defender)

        if (defender.getUnitType().isCivilian() && attacker.isMelee()) {
            captureCivilianUnit(attacker, defender as MapUnitCombatant)
        } else if (attacker.isRanged()) {
            defender.takeDamage(damageToDefender) // straight up
        } else {
            //melee attack is complicated, because either side may defeat the other midway
            //so...for each round, we randomize who gets the attack in. Seems to be a good way to work for now.

            while (damageToDefender + damageToAttacker > 0) {
                if (Random().nextInt(damageToDefender + damageToAttacker) < damageToDefender) {
                    damageToDefender--
                    defender.takeDamage(1)
                    if (defender.isDefeated()) break
                } else {
                    damageToAttacker--
                    attacker.takeDamage(1)
                    if (attacker.isDefeated()) break
                }
            }
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
            attacker.unit.movement.moveToTile(attackedTile)
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
            if (unit.hasUnique("Can move after attacking")
                    || (unit.hasUnique("1 additional attack per turn") && unit.attacksThisTurn == 0)) {
                // if it was a melee attack and we won, then the unit ALREADY got movement points deducted,
                // for the movement to the enemy's tile!
                // and if it's an air unit, it only has 1 movement anyway, so...
                if (!attacker.getUnitType().isAirUnit() && !(attacker.getUnitType().isMelee() && defender.isDefeated()))
                    unit.useMovementPoints(1f)
            } else unit.currentMovement = 0f
            unit.attacksThisTurn += 1
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

        if (attackerCiv.isBarbarian()) {
            city.destroyCity()
            return
        }

        if (attackerCiv.isPlayerCivilization()) {
            attackerCiv.popupAlerts.add(PopupAlert(AlertType.CityConquered, city.id))
            UncivGame.Current.settings.addCompletedTutorialTask("Conquer a city")
        } else {
            city.puppetCity(attackerCiv)
            if (city.population.population < 4 && !city.isOriginalCapital) {
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

    const val NUKE_RADIUS = 2

    fun nuke(attacker: ICombatant, targetTile: TileInfo) {
        val attackingCiv = attacker.getCivInfo()
        for (tile in targetTile.getTilesInDistance(NUKE_RADIUS)) {
            val city = tile.getCity()
            if (city != null && city.location == tile.position) {
                city.health = 1
                if (city.population.population <= 5 && !city.isOriginalCapital) {
                    city.destroyCity()
                } else {
                    city.population.population = max(city.population.population - 5, 1)
                    city.population.unassignExtraPopulation()
                    continue
                }
                destroyIfDefeated(city.civInfo, attackingCiv)
            }

            fun declareWar(civSuffered: CivilizationInfo) {
                if (civSuffered != attackingCiv
                        && civSuffered.knows(attackingCiv)
                        && civSuffered.getDiplomacyManager(attackingCiv).canDeclareWar()) {
                    attackingCiv.getDiplomacyManager(civSuffered).declareWar()
                }
            }

            for (unit in tile.getUnits()) {
                unit.destroy()
                postBattleNotifications(attacker, MapUnitCombatant(unit), unit.currentTile)
                declareWar(unit.civInfo)
                destroyIfDefeated(unit.civInfo, attackingCiv)
            }

            // this tile belongs to some civilization who is not happy of nuking it
            if (city != null)
                declareWar(city.civInfo)

            tile.improvement = null
            tile.improvementInProgress = null
            tile.turnsToImprovement = 0
            tile.roadStatus = RoadStatus.None
            if (tile.isLand && !tile.isImpassible() && !tile.terrainFeatures.contains("Fallout"))
                tile.terrainFeatures.add("Fallout")
        }

        for (civ in attacker.getCivInfo().getKnownCivs()) {
            civ.getDiplomacyManager(attackingCiv)
                    .setModifier(DiplomaticModifiers.UsedNuclearWeapons, -50f)
        }

        // Instead of postBattleAction() just destroy the missile, all other functions are not relevant
        if ((attacker as MapUnitCombatant).unit.hasUnique("Self-destructs when attacking")) {
            attacker.unit.destroy()
        }
    }

    private fun tryInterceptAirAttack(attacker: MapUnitCombatant, defender: ICombatant) {
        val attackedTile = defender.getTile()
        for (interceptor in defender.getCivInfo().getCivUnits().filter { it.canIntercept(attackedTile) }) {
            if (Random().nextFloat() > 100f / interceptor.interceptChance()) continue

            var damage = BattleDamage.calculateDamageToDefender(MapUnitCombatant(interceptor), null, attacker)
            damage += damage * interceptor.interceptDamagePercentBonus() / 100
            if (attacker.unit.hasUnique("Reduces damage taken from interception by 50%")) damage /= 2

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
