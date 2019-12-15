package com.unciv.logic.map

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector2
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.automation.UnitAutomation
import com.unciv.logic.automation.WorkerAutomation
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.map.action.MapUnitAction
import com.unciv.logic.map.action.StringAction
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.tech.TechEra
import com.unciv.models.ruleset.tile.TerrainType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.ruleset.unit.UnitType
import java.text.DecimalFormat

class MapUnit {

    @Transient lateinit var civInfo: CivilizationInfo
    @Transient lateinit var baseUnit: BaseUnit
    @Transient internal lateinit var currentTile :TileInfo

    @Transient val movement = UnitMovementAlgorithms(this)

    // This is saved per each unit because if we need to recalculate viewable tiles every time a unit moves,
    //  and we need to go over ALL the units, that's a lot of time spent on updating information we should already know!
    // About 10% of total NextTurn performance time, at the time of this change!
    @Transient var viewableTiles = listOf<TileInfo>()

    // These are for performance improvements to getMovementCostBetweenAdjacentTiles,
    // a major component of getDistanceToTilesWithinTurn,
    // which in turn is a component of getShortestPath and canReach
    @Transient var ignoresTerrainCost = false
    @Transient var roughTerrainPenalty = false
    @Transient var doubleMovementInCoast = false
    @Transient var doubleMovementInForestAndJungle = false

    lateinit var owner: String
    lateinit var name: String
    var currentMovement: Float = 0f
    var health:Int = 100

    var mapUnitAction : MapUnitAction? = null

    var action: String? // work, automation, fortifying, I dunno what.
        // getter and setter for compatibility: make sure string-based actions still work
        get() {
            val mapUnitActionVal=mapUnitAction
            if(mapUnitActionVal is StringAction)
                return mapUnitActionVal.action
            // any other unit action does count as a unit action, thus is not null. The actual logic is not based on an action string, but realized by extending MapUnitAction
            if(mapUnitActionVal!=null)
                return ""

            return null // unit has no action
        }
        set(value) { mapUnitAction = if (value == null) null else StringAction(this, value) } // wrap traditional string-encoded actions into StringAction


    var attacksThisTurn = 0
    var promotions = UnitPromotions()
    var due: Boolean = true

    //region pure functions
    fun clone(): MapUnit {
        val toReturn = MapUnit()
        toReturn.owner=owner
        toReturn.name=name
        toReturn.currentMovement=currentMovement
        toReturn.health=health
        toReturn.action=action
        toReturn.attacksThisTurn=attacksThisTurn
        toReturn.promotions=promotions.clone()
        return toReturn
    }

    val type:UnitType
        get()=baseUnit.unitType

    fun baseUnit(): BaseUnit = baseUnit
    fun getMovementString(): String = DecimalFormat("0.#").format(currentMovement.toDouble()) + "/" + getMaxMovement()
    fun getTile(): TileInfo =  currentTile
    fun getMaxMovement(): Int {
        if (isEmbarked()) return getEmbarkedMovement()

        var movement = baseUnit.movement
        movement += getUniques().count { it == "+1 Movement" }

        if (type.isWaterUnit() && !type.isCivilian()
                && civInfo.containsBuildingUnique("All military naval units receive +1 movement and +1 sight"))
            movement += 1

        if (type.isWaterUnit() && civInfo.nation.unique == "+2 movement for all naval units")
            movement += 2

        if(civInfo.goldenAges.isGoldenAge() &&
                civInfo.nation.unique=="Golden Ages last 50% longer. During a Golden Age, units receive +1 Movement and +10% Strength")
            movement+=1

        return movement
    }

    // This SHOULD NOT be a hashset, because if it is, thenn promotions with the same text (e.g. barrage I, barrage II)
    //  will not get counted twice!
    @Transient var tempUniques= ArrayList<String>()

    fun getUniques(): ArrayList<String> {
        return tempUniques
    }

    fun updateUniques(){
        val uniques = ArrayList<String>()
        val baseUnit = baseUnit()
        uniques.addAll(baseUnit.uniques)
        uniques.addAll(promotions.promotions.map { currentTile.tileMap.gameInfo.ruleSet.UnitPromotions[it]!!.effect })
        tempUniques = uniques

        if("Ignores terrain cost" in uniques) ignoresTerrainCost=true
        if("Rough terrain penalty" in uniques) roughTerrainPenalty=true
        if("Double movement in coast" in uniques) doubleMovementInCoast=true
        if("Double movement rate through Forest and Jungle" in uniques) doubleMovementInForestAndJungle=true
    }

    fun hasUnique(unique:String): Boolean {
        return getUniques().contains(unique)
    }

    fun updateVisibleTiles() {
        if(type.isAirUnit()){
            if(hasUnique("6 tiles in every direction always visible"))
                viewableTiles = getTile().getTilesInDistance(6)  // it's that simple
            else viewableTiles = listOf() // bomber units don't do recon
        }
        else {
            var visibilityRange = 2
            visibilityRange += getUniques().count { it == "+1 Visibility Range" }
            if (hasUnique("Limited Visibility")) visibilityRange -= 1
            if (civInfo.nation.unique == "All land military units have +1 sight, 50% discount when purchasing tiles")
                visibilityRange += 1
            if (type.isWaterUnit() && !type.isCivilian()
                    && civInfo.containsBuildingUnique("All military naval units receive +1 movement and +1 sight"))
                visibilityRange += 1
            if (isEmbarked() && civInfo.nation.unique == "Can embark and move over Coasts and Oceans immediately. +1 Sight when embarked. +10% Combat Strength bonus if within 2 tiles of a Moai.")
                visibilityRange += 1
            val tile = getTile()
            if (tile.baseTerrain == Constants.hill && type.isLandUnit()) visibilityRange += 1

            viewableTiles = tile.getViewableTiles(visibilityRange, type.isWaterUnit())
        }
        civInfo.updateViewableTiles() // for the civ
    }

    fun isFortified(): Boolean {
        return action?.startsWith("Fortify") == true
    }

    fun getFortificationTurns(): Int {
        if(!isFortified()) return 0
        return action!!.split(" ")[1].toInt()
    }

    override fun toString(): String {
        return "$name - $owner"
    }


    fun isIdle(): Boolean {
        if (currentMovement == 0f) return false
        if (name == Constants.worker && getTile().improvementInProgress != null) return false
        if (hasUnique("Can construct roads") && currentTile.improvementInProgress=="Road") return false
        if (isFortified()) return false
        if (action==Constants.unitActionExplore || action==Constants.unitActionSleep
                || action == Constants.unitActionAutomation) return false
        return true
    }

    fun canAttack(): Boolean {
        if(currentMovement==0f) return false
        if(attacksThisTurn>0 && !hasUnique("1 additional attack per turn")) return false
        if(attacksThisTurn>1) return false
        return true
    }

    fun getRange(): Int {
        if(type.isMelee()) return 1
        var range = baseUnit().range
        if(hasUnique("+1 Range")) range++
        if(hasUnique("+2 Range")) range+=2
        return range
    }


    fun isEmbarked(): Boolean {
        if(!type.isLandUnit()) return false
        return currentTile.getBaseTerrain().type==TerrainType.Water
    }

    fun isInvisible(): Boolean {
        if(hasUnique("Invisible to others"))
            return true
        return false
    }

    fun getEmbarkedMovement(): Int {
        var movement=2
        movement += civInfo.tech.getTechUniques().count { it == "Increases embarked movement +1" }
        return movement
    }

    fun getUnitToUpgradeTo(): BaseUnit {
        var unit = baseUnit()

        // Go up the upgrade tree until you find the last one which is buildable
        while (unit.upgradesTo!=null && civInfo.tech.isResearched(unit.getDirectUpgradeUnit(civInfo).requiredTech!!))
            unit = unit.getDirectUpgradeUnit(civInfo)
        return unit
    }

    fun canUpgrade(): Boolean {
        // We need to remove the unit from the civ for this check,
        // because if the unit requires, say, horses, and so does its upgrade,
        // and the civ currently has 0 horses,
        // if we don't remove the unit before the check it's return false!

        val unitToUpgradeTo = getUnitToUpgradeTo()
        if (name == unitToUpgradeTo.name) return false
        civInfo.removeUnit(this)
        val canUpgrade = unitToUpgradeTo.isBuildable(civInfo)
        civInfo.addUnit(this)
        return canUpgrade
    }

    fun getCostOfUpgrade(): Int {
        val unitToUpgradeTo = getUnitToUpgradeTo()
        var goldCostOfUpgrade = (unitToUpgradeTo.cost - baseUnit().cost) * 2 + 10
        if (civInfo.policies.isAdopted("Professional Army"))
            goldCostOfUpgrade = (goldCostOfUpgrade * 0.66f).toInt()
        if(civInfo.containsBuildingUnique("Gold cost of upgrading military units reduced by 33%"))
            goldCostOfUpgrade = (goldCostOfUpgrade * 0.66f).toInt()
        if(goldCostOfUpgrade<0) return 0 // For instance, Landsknecht costs less than Spearman, so upgrading would cost negative gold
        return goldCostOfUpgrade
    }


    fun canFortify(): Boolean {
        if(type.isWaterUnit()) return false
        if(type.isCivilian()) return false
        if(type.isAirUnit()) return false
        if(isEmbarked()) return false
        if(hasUnique("No defensive terrain bonus")) return false
        if(isFortified()) return false
        return true
    }

    fun fortify(){ action = "Fortify 0"}

    fun adjacentHealingBonus():Int{
        var healingBonus = 0
        if(hasUnique("This unit and all others in adjacent tiles heal 5 additional HP per turn")) healingBonus +=5
        if(hasUnique("This unit and all others in adjacent tiles heal 5 additional HP. This unit heals 5 additional HP outside of friendly territory.")) healingBonus +=5
        return healingBonus
    }

    //endregion

    //region state-changing functions
    fun setTransients(ruleset: Ruleset) {
        promotions.unit=this
        mapUnitAction?.unit = this
        baseUnit=ruleset.Units[name]!!
        updateUniques()
    }

    fun useMovementPoints(amount:Float){
        currentMovement -= amount
        if(currentMovement<0) currentMovement = 0f
    }

    fun doPreTurnAction() {
        if(action==null) return
        val currentTile = getTile()
        if (currentMovement == 0f) return  // We've already done stuff this turn, and can't do any more stuff

        val enemyUnitsInWalkingDistance = movement.getDistanceToTiles().keys
                .filter { it.militaryUnit!=null && civInfo.isAtWarWith(it.militaryUnit!!.civInfo)}
        if(enemyUnitsInWalkingDistance.isNotEmpty()) {
            if (mapUnitAction?.shouldStopOnEnemyInSight()==true)
                mapUnitAction=null
            return  // Don't you dare move.
        }

        mapUnitAction?.doPreTurnAction()

        if (action != null && action!!.startsWith("moveTo")) {
            val destination = action!!.replace("moveTo ", "").split(",").dropLastWhile { it.isEmpty() }.toTypedArray()
            val destinationVector = Vector2(Integer.parseInt(destination[0]).toFloat(), Integer.parseInt(destination[1]).toFloat())
            val destinationTile = currentTile.tileMap[destinationVector]
            if(!movement.canReach(destinationTile)) return // That tile that we were moving towards is now unreachable
            val gotTo = movement.headTowards(destinationTile)
            if(gotTo==currentTile) // We didn't move at all
                return
            if (gotTo.position == destinationVector) action = null
            if (currentMovement >0) doPreTurnAction()
            return
        }

        if (action == Constants.unitActionAutomation) WorkerAutomation(this).automateWorkerAction()

        if(action == Constants.unitActionExplore) UnitAutomation().automatedExplore(this)
    }

    private fun doPostTurnAction() {
        if (name == Constants.worker && getTile().improvementInProgress != null) workOnImprovement()
        if(hasUnique("Can construct roads") && currentTile.improvementInProgress=="Road") workOnImprovement()
        if(currentMovement== getMaxMovement().toFloat()
                && isFortified()){
            val currentTurnsFortified = getFortificationTurns()
            if(currentTurnsFortified<2) action = "Fortify ${currentTurnsFortified+1}"
        }
    }

    private fun workOnImprovement() {
        val tile=getTile()
        tile.turnsToImprovement -= 1
        if (tile.turnsToImprovement != 0) return

        if(civInfo.isCurrentPlayer())
            UncivGame.Current.settings.addCompletedTutorialTask("Construct an improvement")
        when {
            tile.improvementInProgress!!.startsWith("Remove") -> {
                val tileImprovement = tile.getTileImprovement()
                if(tileImprovement!=null
                        && tileImprovement.terrainsCanBeBuiltOn.contains(tile.terrainFeature)
                        && !tileImprovement.terrainsCanBeBuiltOn.contains(tile.baseTerrain)) {
                    tile.improvement = null // We removed a terrain (e.g. Forest) and the improvement (e.g. Lumber mill) requires it!
                }
                if(tile.improvementInProgress=="Remove Road" || tile.improvementInProgress=="Remove Railroad")
                    tile.roadStatus = RoadStatus.None
                else tile.terrainFeature = null
            }
            tile.improvementInProgress == "Road" -> tile.roadStatus = RoadStatus.Road
            tile.improvementInProgress == "Railroad" -> tile.roadStatus = RoadStatus.Railroad
            else -> tile.improvement = tile.improvementInProgress
        }
        tile.improvementInProgress = null
    }

    private fun heal() {
        if (isEmbarked()) return // embarked units can't heal
        var amountToHealBy = rankTileForHealing(getTile())
        if (amountToHealBy == 0) return

        if (hasUnique("+10 HP when healing")) amountToHealBy += 10
        val adjacentUnits = currentTile.getTilesInDistance(1).flatMap { it.getUnits() }
        if (adjacentUnits.isNotEmpty())
            amountToHealBy += adjacentUnits.map { it.adjacentHealingBonus() }.max()!!
        if (hasUnique("All healing effects doubled"))
            amountToHealBy *= 2
        healBy(amountToHealBy)
    }

    fun healBy(amount:Int){
        health += amount
        if(health>100) health=100
    }

    fun rankTileForHealing(tileInfo:TileInfo): Int {
        return when{
            tileInfo.isWater && type.isLandUnit() -> 0 // Can't heal in water!
            tileInfo.getOwner() == null -> 10 // no man's land (neutral)
            tileInfo.isCityCenter() -> 20
            !civInfo.isAtWarWith(tileInfo.getOwner()!!) -> 15 // home or allied territory
            else -> {  // enemy territory
                if(hasUnique("This unit and all others in adjacent tiles heal 5 additional HP. This unit heals 5 additional HP outside of friendly territory.")) 10
                else 5
            }
        }
    }


    fun endTurn() {
        doPostTurnAction()
        if(currentMovement== getMaxMovement().toFloat() // didn't move this turn
                || getUniques().contains("Unit will heal every turn, even if it performs an action")){
            heal()
        }
    }

    fun startTurn(){
        currentMovement = getMaxMovement().toFloat()
        attacksThisTurn=0
        due = true

        // Wake sleeping units if there's an enemy nearby
        if(action==Constants.unitActionSleep && currentTile.getTilesInDistance(2).any {
                    it.militaryUnit!=null && it.militaryUnit!!.civInfo.isAtWarWith(civInfo)
                })
            action=null

        val tileOwner = getTile().getOwner()
        if(tileOwner!=null && !civInfo.canEnterTiles(tileOwner) && !tileOwner.isCityState()) // if an enemy city expanded onto this tile while I was in it
            movement.teleportToClosestMoveableTile()
        doPreTurnAction()
    }

    fun destroy(){
        removeFromTile()
        civInfo.removeUnit(this)
    }

    fun removeFromTile(){
        when {
            type.isAirUnit() -> currentTile.airUnits.remove(this)
            type.isCivilian() -> getTile().civilianUnit=null
            else -> getTile().militaryUnit=null
        }
    }

    fun moveThroughTile(tile: TileInfo){
        if(tile.improvement==Constants.ancientRuins && civInfo.isMajorCiv())
            getAncientRuinBonus(tile)
        if(tile.improvement==Constants.barbarianEncampment && !civInfo.isBarbarian())
            clearEncampment(tile)

        // addPromotion requires currentTile to be valid because it accesses ruleset through it
        currentTile = tile

        if(!hasUnique("All healing effects doubled") && type.isLandUnit() && type.isMilitary())
        {
            val gainDoubleHealPromotion = tile.neighbors.filter{it.naturalWonder == "Fountain of Youth"}.any()
            if (gainDoubleHealPromotion)
                promotions.addPromotion("Rejuvenation", true)
        }

        updateVisibleTiles()
    }

    fun putInTile(tile:TileInfo){
        when {
            !movement.canMoveTo(tile) -> throw Exception("I can't go there!")
            type.isAirUnit() -> tile.airUnits.add(this)
            type.isCivilian() -> tile.civilianUnit=this
            else -> tile.militaryUnit=this
        }
        moveThroughTile(tile)
    }

    private fun clearEncampment(tile: TileInfo) {
        tile.improvement=null
        val goldToAdd = 25 // game-speed-dependant
        civInfo.gold+=goldToAdd
        civInfo.addNotification("We have captured a barbarian encampment and recovered [$goldToAdd] gold!", tile.position, Color.RED)
    }

    fun disband(){
        destroy()
        if(currentTile.getOwner()==civInfo)
            civInfo.gold += baseUnit.getDisbandGold()
    }

    private fun getAncientRuinBonus(tile: TileInfo) {
        tile.improvement=null
        val actions: ArrayList<() -> Unit> = ArrayList()
        if(civInfo.cities.isNotEmpty()) actions.add {
            val city = civInfo.cities.random()
            city.population.population++
            city.population.autoAssignPopulation()
            civInfo.addNotification("We have found survivors in the ruins - population added to ["+city.name+"]",tile.position, Color.GREEN)
        }
        val researchableAncientEraTechs = tile.tileMap.gameInfo.ruleSet.Technologies.values
                .filter {
                    !civInfo.tech.isResearched(it.name)
                            && civInfo.tech.canBeResearched(it.name)
                            && it.era() == TechEra.Ancient
                }
        if(researchableAncientEraTechs.isNotEmpty())
            actions.add {
                val tech = researchableAncientEraTechs.random().name
                civInfo.tech.addTechnology(tech)
                civInfo.addNotification("We have discovered the lost technology of [$tech] in the ruins!",tile.position, Color.BLUE)
            }

        actions.add {
            val chosenUnit = listOf(Constants.settler, Constants.worker,"Warrior").random()
            if (!(civInfo.isCityState() || civInfo.isOneCityChallenger()) || chosenUnit != Constants.settler) { //City states and OCC don't get settler from ruins
                civInfo.placeUnitNearTile(tile.position, chosenUnit)
                civInfo.addNotification("A [$chosenUnit] has joined us!", tile.position, Color.BROWN)
            }
        }

        if(!type.isCivilian())
            actions.add {
                promotions.XP+=10
                civInfo.addNotification("An ancient tribe trains our [$name] in their ways of combat!",tile.position, Color.RED)
            }

        actions.add {
            val amount = listOf(25,60,100).random()
            civInfo.gold+=amount
            civInfo.addNotification("We have found a stash of [$amount] gold in the ruins!",tile.position, Color.GOLD)
        }

        (actions.random())()
    }

    fun assignOwner(civInfo:CivilizationInfo, updateCivInfo:Boolean=true){
        owner=civInfo.civName
        this.civInfo=civInfo
        civInfo.addUnit(this,updateCivInfo)
    }

    fun canIntercept(attackedTile: TileInfo): Boolean {
        return interceptChance()!=0
                && (attacksThisTurn==0 || hasUnique("1 extra Interception may be made per turn") && attacksThisTurn<2)
                && currentTile.arialDistanceTo(attackedTile) <= getRange()
    }

    fun interceptChance():Int{
        val interceptUnique = getUniques()
                .firstOrNull { it.endsWith(" chance to intercept air attacks") }
        if(interceptUnique==null) return 0
        val percent = Regex("\\d+").find(interceptUnique)!!.value.toInt()
        return percent
    }

    fun interceptDamagePercentBonus():Int{
        var sum=0
        for(unique in getUniques().filter { it.startsWith("Bonus when intercepting") }){
            val percent = Regex("\\d+").find(unique)!!.value.toInt()
            sum += percent
        }
        return sum
    }

    //endregion
}