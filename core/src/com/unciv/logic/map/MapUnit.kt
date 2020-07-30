package com.unciv.logic.map

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector2
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.UniqueAbility
import com.unciv.logic.automation.UnitAutomation
import com.unciv.logic.automation.WorkerAutomation
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.map.action.MapUnitAction
import com.unciv.logic.map.action.StringAction
import com.unciv.logic.replay.Action
import com.unciv.logic.replay.ActionType
import com.unciv.logic.replay.UnitId
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.ruleset.unit.UnitType
import java.text.DecimalFormat
import kotlin.random.Random

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
    @Transient var doubleMovementInSnowTundraAndHills = false
    @Transient var canEnterIceTiles = false
    @Transient var cannotEnterOceanTiles = false
    @Transient var cannotEnterOceanTilesUntilAstronomy = false

    lateinit var owner: String
    lateinit var name: String
    var currentMovement: Float = 0f
    var health:Int = 100

    var mapUnitAction : MapUnitAction? = null

    var action: String? // work, automation, fortifying, I dunno what.
        // getter and setter for compatibility: make sure string-based actions still work
        get() {
            val mapUnitActionVal = mapUnitAction
            if (mapUnitActionVal is StringAction)
                return mapUnitActionVal.action
            // any other unit action does count as a unit action, thus is not null. The actual logic is not based on an action string, but realized by extending MapUnitAction
            if (mapUnitActionVal != null)
                return ""

            return null // unit has no action
        }
        set(value) { mapUnitAction = if (value == null) null else StringAction(this, value) } // wrap traditional string-encoded actions into StringAction


    var attacksThisTurn = 0
    var promotions = UnitPromotions()
    var due: Boolean = true
    var isTransported: Boolean = false

    companion object {
        private const val ANCIENT_RUIN_MAP_REVEAL_OFFSET = 4
        private const val ANCIENT_RUIN_MAP_REVEAL_RANGE = 4
        private const val ANCIENT_RUIN_MAP_REVEAL_CHANCE = 0.8f
        const val BONUS_WHEN_INTERCEPTING = "Bonus when intercepting"
        const val CHANCE_TO_INTERCEPT_AIR_ATTACKS = " chance to intercept air attacks"
    }

    //region pure functions
    fun clone(): MapUnit {
        val toReturn = MapUnit()
        toReturn.owner = owner
        toReturn.name = name
        toReturn.currentMovement = currentMovement
        toReturn.health = health
        toReturn.action = action
        toReturn.attacksThisTurn = attacksThisTurn
        toReturn.promotions = promotions.clone()
        toReturn.isTransported = isTransported
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
                && civInfo.hasUnique("All military naval units receive +1 movement and +1 sight"))
            movement += 1

        if (type.isWaterUnit() && civInfo.nation.unique == UniqueAbility.SUN_NEVER_SETS)
            movement += 2

        if (type == UnitType.Mounted &&
                civInfo.nation.unique == UniqueAbility.MONGOL_TERROR)
            movement += 1

        if (civInfo.goldenAges.isGoldenAge() &&
                civInfo.nation.unique == UniqueAbility.ACHAEMENID_LEGACY)
            movement += 1

        return movement
    }
    fun getUnitId(): UnitId {
        return if (!type.isAirUnit()) UnitId(type, getTile().position)
        else UnitId(type, getTile().position, airUnitIndex = getTile().airUnits.indexOf(this))

    }

    // This SHOULD NOT be a hashset, because if it is, then promotions with the same text (e.g. barrage I, barrage II)
    //  will not get counted twice!
    @Transient var tempUniques= ArrayList<String>()

    fun getUniques(): ArrayList<String> {
        return tempUniques
    }

    fun updateUniques(){
        val uniques = ArrayList<String>()
        val baseUnit = baseUnit()
        uniques.addAll(baseUnit.uniques)
        uniques.addAll(promotions.promotions.map { currentTile.tileMap.gameInfo.ruleSet.unitPromotions[it]!!.effect })
        tempUniques = uniques

        ignoresTerrainCost = ("Ignores terrain cost" in uniques)
        roughTerrainPenalty = ("Rough terrain penalty" in uniques)
        doubleMovementInCoast = ("Double movement in coast" in uniques)
        doubleMovementInForestAndJungle = ("Double movement rate through Forest and Jungle" in uniques)
        doubleMovementInSnowTundraAndHills = ("Double movement in Snow, Tundra and Hills" in uniques)
        canEnterIceTiles = ("Can enter ice tiles" in uniques)
        cannotEnterOceanTiles = ("Cannot enter ocean tiles" in uniques)
        cannotEnterOceanTilesUntilAstronomy = ("Cannot enter ocean tiles until Astronomy" in uniques)
    }

    fun hasUnique(unique:String): Boolean {
        return getUniques().contains(unique)
    }

    fun updateVisibleTiles() {
        if(type.isAirUnit()) {
            viewableTiles = if (hasUnique("6 tiles in every direction always visible"))
                getTile().getTilesInDistance(6).toList()  // it's that simple
            else listOf() // bomber units don't do recon
        }
        else {
            var visibilityRange = 2
            visibilityRange += getUniques().count { it == "+1 Visibility Range" }
            if (hasUnique("+2 Visibility Range")) visibilityRange += 2 // This shouldn't be stackable
            if (hasUnique("Limited Visibility")) visibilityRange -= 1
            if (civInfo.nation.unique == UniqueAbility.MANIFEST_DESTINY)
                visibilityRange += 1
            if (type.isWaterUnit() && !type.isCivilian()
                    && civInfo.hasUnique("All military naval units receive +1 movement and +1 sight"))
                visibilityRange += 1
            if (isEmbarked() && civInfo.nation.unique == UniqueAbility.WAYFINDING)
                visibilityRange += 1
            val tile = getTile()
            if (tile.baseTerrain == Constants.hill && type.isLandUnit()) visibilityRange += 1

            viewableTiles = tile.getViewableTilesList(visibilityRange)
        }
        civInfo.updateViewableTiles() // for the civ
    }

    fun isFortified(): Boolean {
        return action?.startsWith("Fortify") == true
    }

    fun isSleeping(): Boolean {
        return action?.startsWith("Sleep") == true
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
        if (hasUnique(Constants.workerUnique) && getTile().improvementInProgress != null) return false
        if (hasUnique("Can construct roads") && currentTile.improvementInProgress=="Road") return false
        if (isFortified()) return false
        if (action==Constants.unitActionExplore || isSleeping()
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
        return currentTile.isWater
    }

    fun isInvisible(): Boolean {
        if (hasUnique("Invisible to others"))
            return true
        return false
    }

    fun getEmbarkedMovement(): Int {
        var movement=2
        movement += civInfo.tech.getTechUniques().count { it == "Increases embarked movement +1" }
        if (civInfo.nation.unique == UniqueAbility.VIKING_FURY) movement +=1
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
        for(unique in civInfo.getMatchingUniques("Gold cost of upgrading military units reduced by 33%"))
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

    fun fortify() {
        action = "Fortify 0"
    }

    fun fortifyUntilHealed() {
        action = "Fortify 0 until healed"
    }

    fun fortifyIfCan() {
        if (canFortify()) {
            fortify()
        }
    }

    private fun adjacentHealingBonus():Int{
        var healingBonus = 0
        if(hasUnique("This unit and all others in adjacent tiles heal 5 additional HP per turn")) healingBonus +=5
        if(hasUnique("This unit and all others in adjacent tiles heal 5 additional HP. This unit heals 5 additional HP outside of friendly territory.")) healingBonus +=5
        return healingBonus
    }

    fun canGarrison() = type.isMilitary() && type.isLandUnit()

    //endregion

    //region state-changing functions
    fun setTransients(ruleset: Ruleset) {
        promotions.unit=this
        mapUnitAction?.unit = this
        baseUnit=ruleset.units[name]
                ?: throw java.lang.Exception("Unit $name is not found!")
        updateUniques()
    }

    fun useMovementPoints(amount:Float){
        currentMovement -= amount
        if(currentMovement<0) currentMovement = 0f
    }

    fun doPreTurnAction() {
        if (action == null) return
        val currentTile = getTile()
        if (currentMovement == 0f) return  // We've already done stuff this turn, and can't do any more stuff

        val enemyUnitsInWalkingDistance = movement.getDistanceToTiles().keys
                .filter { it.militaryUnit != null && civInfo.isAtWarWith(it.militaryUnit!!.civInfo) }
        if (enemyUnitsInWalkingDistance.isNotEmpty()) {
            if (mapUnitAction?.shouldStopOnEnemyInSight() == true)
                mapUnitAction = null
            return  // Don't you dare move.
        }

        mapUnitAction?.doPreTurnAction()

        if (action != null && action!!.startsWith("moveTo")) {
            val destination = action!!.replace("moveTo ", "").split(",").dropLastWhile { it.isEmpty() }.toTypedArray()
            val destinationVector = Vector2(Integer.parseInt(destination[0]).toFloat(), Integer.parseInt(destination[1]).toFloat())
            val destinationTile = currentTile.tileMap[destinationVector]
            if (!movement.canReach(destinationTile)) return // That tile that we were moving towards is now unreachable
            val gotTo = movement.headTowards(destinationTile)
            if (gotTo == currentTile) // We didn't move at all
                return
            if (gotTo.position == destinationVector) action = null
            if (currentMovement > 0) doPreTurnAction()
            return
        }

        if (action == Constants.unitActionAutomation) WorkerAutomation(this).automateWorkerAction()

        if (action == Constants.unitActionExplore) UnitAutomation.automatedExplore(this)
    }

    private fun doPostTurnAction() {
        if (hasUnique(Constants.workerUnique) && getTile().improvementInProgress != null) workOnImprovement()
        if(hasUnique("Can construct roads") && currentTile.improvementInProgress=="Road") workOnImprovement()
        if(currentMovement == getMaxMovement().toFloat()
                && isFortified()){
            val currentTurnsFortified = getFortificationTurns()
            if(currentTurnsFortified<2)
                action = action!!.replace(currentTurnsFortified.toString(),(currentTurnsFortified+1).toString(), true)
        }
        if (hasUnique("Heal adjacent units for an additional 15 HP per turn"))
            currentTile.neighbors.flatMap{ it.getUnits() }.forEach{ it.healBy(15) }
    }

    private fun workOnImprovement() {
        val tile = getTile()
        tile.turnsToImprovement -= 1
        if (tile.turnsToImprovement != 0) return

        if (civInfo.isCurrentPlayer())
            UncivGame.Current.settings.addCompletedTutorialTask("Construct an improvement")
        when {
            tile.improvementInProgress!!.startsWith("Remove") -> {
                val tileImprovement = tile.getTileImprovement()
                if (tileImprovement != null
                        && tileImprovement.terrainsCanBeBuiltOn.contains(tile.terrainFeature)
                        && !tileImprovement.terrainsCanBeBuiltOn.contains(tile.baseTerrain)) {
                    tile.improvement = null // We removed a terrain (e.g. Forest) and the improvement (e.g. Lumber mill) requires it!
                    if (tile.resource != null) civInfo.updateDetailedCivResources()        // unlikely, but maybe a mod makes a resource improvement dependent on a terrain feature
                }
                if (tile.improvementInProgress == "Remove Road" || tile.improvementInProgress == "Remove Railroad")
                    tile.roadStatus = RoadStatus.None
                else tile.terrainFeature = null
            }
            tile.improvementInProgress == "Road" -> tile.roadStatus = RoadStatus.Road
            tile.improvementInProgress == "Railroad" -> tile.roadStatus = RoadStatus.Railroad
            else -> {
                tile.improvement = tile.improvementInProgress
                if (tile.resource != null) civInfo.updateDetailedCivResources()
            }
        }
        tile.improvementInProgress = null
    }

    private fun heal() {
        if (isEmbarked()) return // embarked units can't heal
        var amountToHealBy = rankTileForHealing(getTile())
        if (amountToHealBy == 0) return

        if (hasUnique("+10 HP when healing")) amountToHealBy += 10
        val maxAdjacentHealingBonus = currentTile.getTilesInDistance(1)
                .flatMap { it.getUnits().asSequence() }.map { it.adjacentHealingBonus() }.max()
        if (maxAdjacentHealingBonus != null)
            amountToHealBy += maxAdjacentHealingBonus
        if (hasUnique("All healing effects doubled"))
            amountToHealBy *= 2
        healBy(amountToHealBy)
    }

    fun healBy(amount:Int){
        health += amount
        if(health>100) health=100
    }

    /** Returns the health points [MapUnit] will receive if healing on [tileInfo] */
    fun rankTileForHealing(tileInfo: TileInfo): Int {
        val isFriendlyTerritory = tileInfo.isFriendlyTerritory(civInfo)

        var healing =  when {
            tileInfo.isCityCenter() -> 20
            tileInfo.isWater && isFriendlyTerritory && type.isWaterUnit() -> 15 // Water unit on friendly water
            tileInfo.isWater -> 0 // All other water cases
            tileInfo.getOwner() == null -> 10 // Neutral territory
            isFriendlyTerritory -> 15 // Allied territory
            else -> 5 // Enemy territory
        }

        if (hasUnique("This unit and all others in adjacent tiles heal 5 additional HP. This unit heals 5 additional HP outside of friendly territory.")
            && !isFriendlyTerritory
            // Additional healing from medic is only applied when the unit is able to heal
            && healing > 0)
            healing += 5

        return healing
    }

    fun endTurn() {
        doPostTurnAction()
        if (currentMovement == getMaxMovement().toFloat() // didn't move this turn
                || getUniques().contains("Unit will heal every turn, even if it performs an action")){
            heal()
        }
        if(action != null && health > 99)
            if (action!!.endsWith(" until healed")) {
                action = null // wake up when healed
            }

        getCitadelDamage()
    }

    fun startTurn() {
        currentMovement = getMaxMovement().toFloat()
        attacksThisTurn = 0
        due = true

        // Wake sleeping units if there's an enemy nearby and civilian is not protected
        if (isSleeping() && (!type.isCivilian() || currentTile.militaryUnit == null) &&
                currentTile.getTilesInDistance(2).any {
                    it.militaryUnit != null && it.militaryUnit!!.civInfo.isAtWarWith(civInfo)
                })
            action = null

        val tileOwner = getTile().getOwner()
        if (tileOwner != null && !civInfo.canEnterTiles(tileOwner) && !tileOwner.isCityState()) // if an enemy city expanded onto this tile while I was in it
            movement.teleportToClosestMoveableTile()
        doPreTurnAction()
    }

    fun destroy(){
        removeFromTile()
        civInfo.removeUnit(this)
        civInfo.updateViewableTiles()
        // all transported units should be destroyed as well
        currentTile.getUnits().filter { it.isTransported && isTransportTypeOf(it) }.toList() // because we're changing the list
                .forEach { unit -> unit.destroy() }
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

        if(!hasUnique("All healing effects doubled") && type.isLandUnit() && type.isMilitary()) {
            val gainDoubleHealPromotion = tile.neighbors
                    .any { it.containsUnique("Grants Rejuvenation (all healing effects doubled) to adjacent military land units for the rest of the game") }
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
        // this check is here in order to not load the fresh built unit into carrier right after the build
        isTransported = !tile.isCityCenter() &&
                         type.isAirUnit() // not moving civilians
        moveThroughTile(tile)
    }

    private fun clearEncampment(tile: TileInfo) {
        tile.improvement = null

        var goldGained = civInfo.getDifficulty().clearBarbarianCampReward * civInfo.gameInfo.gameParameters.gameSpeed.modifier
        if (civInfo.nation.unique == UniqueAbility.RIVER_WARLORD)
            goldGained *= 3f

        civInfo.gold += goldGained.toInt()
        civInfo.addNotification("We have captured a barbarian encampment and recovered [${goldGained.toInt()}] gold!", tile.position, Color.RED)
    }

    fun disband() {
        // evacuation of transported units before disbanding, if possible. toListed because we're modifying the unit list.
        for (unit in currentTile.getUnits().filter { it.isTransported && isTransportTypeOf(it) }.toList()) {
            // we disbanded a carrier in a city, it can still stay in the city
            if (currentTile.isCityCenter() && unit.movement.canMoveTo(currentTile)) continue
            // if no "fuel" to escape, should be disbanded as well
            if (unit.currentMovement < 0.1)
                unit.disband()
            // let's find closest city or another carrier where it can be evacuated
            val tileCanMoveTo = unit.currentTile.getTilesInDistance(unit.getRange() * 2).filterNot { it == currentTile }.firstOrNull { unit.movement.canMoveTo(it) }

            if (tileCanMoveTo != null)
                unit.movement.moveToTile(tileCanMoveTo)
            else
                unit.disband()
        }

        // save action data to replay
        val gameInfo = currentTile.tileMap.gameInfo
        if (UncivGame.Current.replayDebugSwitch && !gameInfo.replayMode) {
            val action = Action(ActionType.Disband, getUnitId())
            gameInfo.replay?.saveAction(civInfo, action)
        }


        destroy()
        if (currentTile.getOwner() == civInfo)
            civInfo.gold += baseUnit.getDisbandGold()
        if (civInfo.isDefeated()) civInfo.destroy()
    }

    private fun getAncientRuinBonus(tile: TileInfo) {
        tile.improvement=null
        val tileBasedRandom = Random(tile.position.toString().hashCode())
        val actions: ArrayList<() -> Unit> = ArrayList()
        if(civInfo.cities.isNotEmpty()) actions.add {
            val city = civInfo.cities.random(tileBasedRandom)
            city.population.population++
            city.population.autoAssignPopulation()
            civInfo.addNotification("We have found survivors in the ruins - population added to ["+city.name+"]",tile.position, Color.GREEN)
        }
        val researchableAncientEraTechs = tile.tileMap.gameInfo.ruleSet.technologies.values
                .filter {
                    !civInfo.tech.isResearched(it.name)
                            && civInfo.tech.canBeResearched(it.name)
                            && it.era() == Constants.ancientEra
                }
        if(researchableAncientEraTechs.isNotEmpty())
            actions.add {
                val tech = researchableAncientEraTechs.random(tileBasedRandom).name
                civInfo.tech.addTechnology(tech)
                civInfo.addNotification("We have discovered the lost technology of [$tech] in the ruins!",tile.position, Color.BLUE)
            }

        actions.add {
            val chosenUnit = listOf(Constants.settler, Constants.worker, "Warrior")
                    .filter { civInfo.gameInfo.ruleSet.units.containsKey(it) }.random(tileBasedRandom)
            if (!(civInfo.isCityState() || civInfo.isOneCityChallenger()) || chosenUnit != Constants.settler) { //City-States and OCC don't get settler from ruins
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
            val amount = listOf(25,60,100).random(tileBasedRandom)
            civInfo.gold+=amount
            civInfo.addNotification("We have found a stash of [$amount] gold in the ruins!",tile.position, Color.GOLD)
        }

        // Map of the surrounding area
        actions.add {
            val revealCenter = tile.getTilesAtDistance(ANCIENT_RUIN_MAP_REVEAL_OFFSET).toList().random(tileBasedRandom)
            val tilesToReveal = revealCenter
                .getTilesInDistance(ANCIENT_RUIN_MAP_REVEAL_RANGE)
                .filter { Random.nextFloat() < ANCIENT_RUIN_MAP_REVEAL_CHANCE }
                .map { it.position }
            civInfo.exploredTiles.addAll(tilesToReveal)
            civInfo.updateViewableTiles()
            civInfo.addNotification("We have found a crudely-drawn map in the ruins!", tile.position, Color.RED)
        }

        (actions.random(tileBasedRandom))()
    }

    fun assignOwner(civInfo:CivilizationInfo, updateCivInfo:Boolean=true) {
        owner = civInfo.civName
        this.civInfo = civInfo
        civInfo.addUnit(this, updateCivInfo)
    }

    fun canIntercept(attackedTile: TileInfo): Boolean {
        if (attacksThisTurn > 1) return false
        if (interceptChance() == 0) return false
        if (attacksThisTurn > 0 && !hasUnique("1 extra Interception may be made per turn")) return false
        if (currentTile.aerialDistanceTo(attackedTile) > baseUnit.interceptRange) return false
        return true
    }

    fun interceptChance():Int{
        val interceptUnique = getUniques()
                .firstOrNull { it.endsWith(CHANCE_TO_INTERCEPT_AIR_ATTACKS) }
        if(interceptUnique==null) return 0
        val percent = Regex("\\d+").find(interceptUnique)!!.value.toInt()
        return percent
    }

    fun isTransportTypeOf(mapUnit: MapUnit): Boolean {
        val isAircraftCarrier = getUniques().contains("Can carry 2 aircraft")
        val isMissileCarrier = getUniques().contains("Can carry 2 missiles")
        if(!isMissileCarrier && !isAircraftCarrier)
            return false
        if(!mapUnit.type.isAirUnit()) return false
        if(isMissileCarrier && mapUnit.type!=UnitType.Missile)
            return false
        if(isAircraftCarrier && mapUnit.type==UnitType.Missile)
            return false
        return true
    }

    fun canTransport(mapUnit: MapUnit): Boolean {
        if (!isTransportTypeOf(mapUnit)) return false
        if (owner != mapUnit.owner) return false

        var unitCapacity = 2
        unitCapacity += getUniques().count { it == "Can carry 1 extra air unit" }

        if (currentTile.airUnits.filter { it.isTransported }.size >= unitCapacity) return false

        return true
    }

    fun interceptDamagePercentBonus():Int{
        var sum=0
        for(unique in getUniques().filter { it.startsWith(BONUS_WHEN_INTERCEPTING) }){
            val percent = Regex("\\d+").find(unique)!!.value.toInt()
            sum += percent
        }
        return sum
    }

    private fun getCitadelDamage() {
        // Check for Citadel damage
        val applyCitadelDamage = currentTile.neighbors
                .filter{ it.getOwner() != null && civInfo.isAtWarWith(it.getOwner()!!) }
                .map{ it.getTileImprovement() }
                .filter{ it != null && it.hasUnique("Deal 30 damage to adjacent enemy units") }
                .any()

        if (applyCitadelDamage) {
            health -= 30

            if (health <= 0) {
                civInfo.addNotification("An enemy [Citadel] has destroyed our [$name]", currentTile.position, Color.RED)
                // todo - add notification for attacking civ
                destroy()
            } else {
                civInfo.addNotification("An enemy [Citadel] has attacked our [$name]", currentTile.position, Color.RED)
            }
        }
    }

    //endregion
}