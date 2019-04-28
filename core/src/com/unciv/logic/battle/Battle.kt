package com.unciv.logic.battle

import com.badlogic.gdx.graphics.Color
import com.unciv.logic.GameInfo
import com.unciv.logic.automation.UnitAutomation
import com.unciv.logic.city.CityInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.map.TileInfo
import com.unciv.models.gamebasics.unit.UnitType
import java.util.*
import kotlin.math.max

/**
 * Damage calculations according to civ v wiki and https://steamcommunity.com/sharedfiles/filedetails/?id=170194443
 */
class Battle(val gameInfo:GameInfo) {

    fun moveAndAttack(attacker: ICombatant, attackableTile: UnitAutomation.AttackableTile){
        if (attacker is MapUnitCombatant) {
            attacker.unit.moveToTile(attackableTile.tileToAttackFrom)
            if (attacker.unit.hasUnique("Must set up to ranged attack") && attacker.unit.action != "Set Up") {
                attacker.unit.action = "Set Up"
                attacker.unit.useMovementPoints(1f)
            }
        }
        attack(attacker,getMapCombatantOfTile(attackableTile.tileToAttack)!!)
    }

    fun attack(attacker: ICombatant, defender: ICombatant) {
        println(attacker.getCivInfo().civName+" "+attacker.getName()+" attacked "+defender.getCivInfo().civName+" "+defender.getName())
        val attackedTile = defender.getTile()

        var damageToDefender = BattleDamage().calculateDamageToDefender(attacker,defender)
        var damageToAttacker = BattleDamage().calculateDamageToAttacker(attacker,defender)

        if(defender.getUnitType().isCivilian() && attacker.isMelee()){
            captureCivilianUnit(attacker,defender)
        }
        else if (attacker.isRanged()) {
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

        postBattleAction(attacker,defender,attackedTile)
    }

    private fun postBattleAction(attacker: ICombatant, defender: ICombatant, attackedTile:TileInfo){

        if(attacker.getCivInfo()!=defender.getCivInfo()) { // If what happened was that a civilian unit was captures, that's dealt with in the CaptureCilvilianUnit function
            val whatHappenedString =
                    if (attacker !is CityCombatant && attacker.isDefeated()) " {was destroyed while attacking}"
                    else " has " + (if (defender.isDefeated()) "destroyed" else "attacked")
            val attackerString =
                    if (attacker.getUnitType() == UnitType.City) "Enemy city [" + attacker.getName() + "]"
                    else "An enemy [" + attacker.getName() + "]"
            val defenderString =
                    if (defender.getUnitType() == UnitType.City) " [" + defender.getName()+"]"
                    else " our [" + defender.getName()+"]"
            val notificationString = attackerString + whatHappenedString + defenderString
            defender.getCivInfo().addNotification(notificationString, attackedTile.position, Color.RED)
        }


        if(defender.isDefeated()
                && defender is CityCombatant
                && attacker.isMelee()
                && attacker.getUnitType().isLandUnit()){
            conquerCity(defender.city, attacker)
        }

        // we're a melee unit and we destroyed\captured an enemy unit
        else if (attacker.isMelee()
                && (defender.isDefeated() || defender.getCivInfo()==attacker.getCivInfo())
                // This is so that if we attack e.g. a barbarian in enemy territory that we can't enter, we won't enter it
                && (attacker as MapUnitCombatant).unit.canMoveTo(attackedTile)) {
            // we destroyed an enemy military unit and there was a civilian unit in the same tile as well
            if(attackedTile.civilianUnit!=null && attackedTile.civilianUnit!!.civInfo != attacker.getCivInfo())
                captureCivilianUnit(attacker,MapUnitCombatant(attackedTile.civilianUnit!!))
            attacker.unit.moveToTile(attackedTile)
        }

        if(attacker is MapUnitCombatant) {
            val unit = attacker.unit
            if (unit.hasUnique("Can move after attacking")
                    || (unit.hasUnique("1 additional attack per turn") && unit.attacksThisTurn==0)){
                if(!attacker.getUnitType().isMelee() || !defender.isDefeated()) // if it was a melee attack and we won, then the unit ALREADY got movement points deducted, for the movement to the enemie's tile!
                    unit.useMovementPoints(1f)
            }
            else unit.currentMovement = 0f
            unit.attacksThisTurn+=1
            if(unit.isFortified() || unit.action=="Sleep")
                attacker.unit.action=null // but not, for instance, if it's Set Up - then it should definitely keep the action!
        } else if (attacker is CityCombatant) {
            attacker.city.attackedThisTurn = true
        }

        // XP!
        fun addXp(thisCombatant:ICombatant, amount:Int, otherCombatant:ICombatant){
            if(thisCombatant !is MapUnitCombatant) return
            if(thisCombatant.unit.promotions.totalXpProduced() >= 30 && otherCombatant.getCivInfo().isBarbarianCivilization())
                return
            var amountToAdd = amount
            if(thisCombatant.getCivInfo().policies.isAdopted("Military Tradition")) amountToAdd = (amountToAdd * 1.5f).toInt()
            thisCombatant.unit.promotions.XP += amountToAdd

            if(thisCombatant.getCivInfo().getNation().unique
                    == "Great general provides double combat bonus, and spawns 50% faster")
                amountToAdd = (amountToAdd * 1.5f).toInt()
            if(thisCombatant.unit.hasUnique("Combat very likely to create Great Generals"))
                amountToAdd *= 2

            thisCombatant.getCivInfo().greatPeople.greatGeneralPoints += amountToAdd
        }

        if(attacker.isMelee()){
            if(!defender.getUnitType().isCivilian()) // unit was not captured but actually attacked
            {
                addXp(attacker,5,defender)
                addXp(defender,4,attacker)
            }
        }
        else{ // ranged attack
            addXp(attacker,2,defender)
            addXp(defender,2,attacker)
        }

        // Add culture when defeating a barbarian when Honor policy is adopted (can be either attacker or defender!)
        fun tryGetCultureFromHonor(civUnit:ICombatant, barbarianUnit:ICombatant){
            if(barbarianUnit.isDefeated() && barbarianUnit is MapUnitCombatant
                    && barbarianUnit.getCivInfo().isBarbarianCivilization()
                    && civUnit.getCivInfo().policies.isAdopted("Honor"))
                civUnit.getCivInfo().policies.storedCulture +=
                        max(barbarianUnit.unit.baseUnit.strength,barbarianUnit.unit.baseUnit.rangedStrength)
        }

        tryGetCultureFromHonor(attacker,defender)
        tryGetCultureFromHonor(defender,attacker)

        if(defender.isDefeated() && defender is MapUnitCombatant && !defender.getUnitType().isCivilian()
                && attacker.getCivInfo().policies.isAdopted("Honor Complete"))
            attacker.getCivInfo().gold += defender.unit.baseUnit.getGoldCost(attacker.getCivInfo(), true) / 10

        if(attacker is MapUnitCombatant && attacker.unit.action!=null && attacker.unit.action!!.startsWith("moveTo"))
            attacker.unit.action=null
    }

    private fun conquerCity(city: CityInfo, attacker: ICombatant) {
        val enemyCiv = city.civInfo
        attacker.getCivInfo().addNotification("We have conquered the city of [${city.name}]!",city.location, Color.RED)
        attacker.getCivInfo().popupAlerts.add(PopupAlert(AlertType.CityConquered,city.name))

        city.getCenterTile().apply {
            if(militaryUnit!=null) militaryUnit!!.destroy()
            if(civilianUnit!=null) captureCivilianUnit(attacker,MapUnitCombatant(civilianUnit!!))
        }

        if (attacker.getCivInfo().isBarbarianCivilization() || attacker.getCivInfo().isCityState()){
            city.destroyCity()
        }
        else {
            val currentPopulation = city.population.population
            if(currentPopulation>1) city.population.population -= 1 + currentPopulation/4 // so from 2-4 population, remove 1, from 5-8, remove 2, etc.
            city.population.unassignExtraPopulation()

            city.health = city.getMaxHealth() / 2 // I think that cities recover to half health when conquered?

            if(!attacker.getCivInfo().policies.isAdopted("Police State")) {
                city.expansion.cultureStored = 0
                city.expansion.reset()
            }

            city.moveToCiv(attacker.getCivInfo())
            city.resistanceCounter = city.population.population
            city.workedTiles = hashSetOf() //reassign 1st working tile
            city.population.specialists.clear()
            for (i in 0..city.population.population)
                city.population.autoAssignPopulation()
            city.cityStats.update()
        }

        if(city.cityConstructions.isBuilt("Palace")){
            city.cityConstructions.removeBuilding("Palace")
            if(enemyCiv.isDefeated()) {
                enemyCiv.destroy()
                attacker.getCivInfo().popupAlerts.add(PopupAlert(AlertType.Defeated,enemyCiv.civName))
            }
            else if(enemyCiv.cities.isNotEmpty()){
                enemyCiv.cities.first().cityConstructions.addBuilding("Palace") // relocate palace
            }
        }

        (attacker as MapUnitCombatant).unit.moveToTile(city.getCenterTile())
    }

    fun getMapCombatantOfTile(tile:TileInfo): ICombatant? {
        if(tile.isCityCenter()) return CityCombatant(tile.getCity()!!)
        if(tile.militaryUnit!=null) return MapUnitCombatant(tile.militaryUnit!!)
        if(tile.civilianUnit!=null) return MapUnitCombatant(tile.civilianUnit!!)
        return null
    }

    fun captureCivilianUnit(attacker: ICombatant, defender: ICombatant){
        if(attacker.getCivInfo().isBarbarianCivilization()){
            defender.takeDamage(100)
            return
        } // barbarians don't capture civilians!

        if (attacker.getCivInfo().isCityState() && defender.getName() == "Settler") {
            defender.takeDamage(100)
            return
        }

        if (defender.getCivInfo().isDefeated()) {//Last settler captured
            defender.getCivInfo().destroy()
            attacker.getCivInfo().popupAlerts.add(PopupAlert(AlertType.Defeated,defender.getCivInfo().civName))
        }
        
        val capturedUnit = (defender as MapUnitCombatant).unit
        capturedUnit.civInfo.addNotification("An enemy ["+attacker.getName()+"] has captured our ["+defender.getName()+"]",
                defender.getTile().position, Color.RED)

        capturedUnit.civInfo.removeUnit(capturedUnit)
        capturedUnit.assignOwner(attacker.getCivInfo())
    }
}
