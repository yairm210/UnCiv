package com.unciv.ui.worldscreen.unit

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.automation.UnitAutomation
import com.unciv.logic.automation.WorkerAutomation
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.map.MapUnit
import com.unciv.logic.map.RoadStatus
import com.unciv.logic.map.TileInfo
import com.unciv.models.UncivSound
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.Building
import com.unciv.models.translations.tr
import com.unciv.ui.pickerscreens.ImprovementPickerScreen
import com.unciv.ui.pickerscreens.PromotionPickerScreen
import com.unciv.ui.utils.YesNoPopup
import com.unciv.ui.utils.hasOpenPopups
import com.unciv.ui.worldscreen.WorldScreen

object UnitActions {

    fun getUnitActions(unit: MapUnit, worldScreen: WorldScreen): List<UnitAction> {
        val tile = unit.getTile()
        val unitTable = worldScreen.bottomUnitTable
        val actionList = ArrayList<UnitAction>()

        if (unit.isMoving()) actionList += UnitAction(UnitActionType.StopMovement) { unit.action = null }

        // Constants.workerUnique deprecated since 3.15.5
        val workingOnImprovement = unit.currentTile.hasImprovementInProgress() && unit.canBuildImprovement(unit.currentTile.getTileImprovementInProgress()!!)
        if (!unit.isFortified() && !unit.canFortify() && unit.currentMovement > 0 && !workingOnImprovement) {
            addSleepActions(actionList, unit, unitTable)
        }

        if (unit.canFortify()) addFortifyActions(actionList, unit, unitTable)
        else if (unit.isFortified()) {
            actionList += UnitAction(
                    type = if (unit.action!!.endsWith(" until healed"))
                        UnitActionType.FortifyUntilHealed else
                        UnitActionType.Fortify,
                    isCurrentAction = true,
                    title = "${"Fortification".tr()} ${unit.getFortificationTurns() * 20}%"
            )
        }

        addSwapAction(unit, actionList, worldScreen)
        addExplorationActions(unit, actionList)
        addPromoteAction(unit, actionList)
        addUnitUpgradeAction(unit, actionList)
        addPillageAction(unit, actionList, worldScreen)
        addParadropAction(unit, actionList, worldScreen)
        addSetupAction(unit, actionList)
        addFoundCityAction(unit, actionList, tile)
        addWorkerActions(unit, actionList, tile, worldScreen, unitTable)
        // Deprecated since 3.15.4
            addConstructRoadsAction(unit, tile, actionList)
        //
        addCreateWaterImprovements(unit, actionList)
        addGreatPersonActions(unit, actionList, tile)
        addFoundReligionAction(unit, actionList, tile)
        addSpreadReligionActions(unit, actionList, tile)
        actionList += getImprovementConstructionActions(unit, tile)
        addDisbandAction(actionList, unit, worldScreen)
        addGiftAction(unit, actionList, tile)

        return actionList
    }

    private fun addSwapAction(unit: MapUnit, actionList: ArrayList<UnitAction>, worldScreen: WorldScreen) {
        // Air units cannot swap
        if (unit.type.isAirUnit() || unit.type.isMissile()) return
        // Disable unit swapping if multiple units are selected. It would make little sense.
        // In principle, the unit swapping mode /will/ function with multiselect: it will simply
        // only consider the first selected unit, and ignore the other selections. However, it does
        // have the visual bug that the tile overlays for the eligible swap locations are drawn for
        // /all/ selected units instead of only the first one. This could be fixed, but again,
        // swapping makes little sense for multiselect anyway.
        if (worldScreen.bottomUnitTable.selectedUnits.count() > 1) return
        // Only show the swap action if there is at least one possible swap movement
        if (unit.movement.getUnitSwappableTiles().none()) return
        actionList += UnitAction(
            type = UnitActionType.SwapUnits,
            isCurrentAction = worldScreen.bottomUnitTable.selectedUnitIsSwapping,
            action = {
                worldScreen.bottomUnitTable.selectedUnitIsSwapping = !worldScreen.bottomUnitTable.selectedUnitIsSwapping
                worldScreen.shouldUpdate = true
            }
        )
    }

    private fun addDisbandAction(actionList: ArrayList<UnitAction>, unit: MapUnit, worldScreen: WorldScreen) {
        actionList += UnitAction(type = UnitActionType.DisbandUnit, action = {
            if (!worldScreen.hasOpenPopups()) {
                val disbandText = if (unit.currentTile.getOwner() == unit.civInfo)
                    "Disband this unit for [${unit.baseUnit.getDisbandGold(unit.civInfo)}] gold?".tr()
                else "Do you really want to disband this unit?".tr()
                YesNoPopup(disbandText, { unit.disband(); worldScreen.shouldUpdate = true }).open()
            }
        }.takeIf { unit.currentMovement > 0 })
    }

    private fun addCreateWaterImprovements(unit: MapUnit, actionList: ArrayList<UnitAction>) {
        val waterImprovementAction = getWaterImprovementAction(unit)
        if (waterImprovementAction != null) actionList += waterImprovementAction
    }

    fun getWaterImprovementAction(unit: MapUnit): UnitAction? {
        val tile = unit.currentTile
        if (!tile.isWater || !unit.hasUnique("May create improvements on water resources") || tile.resource == null) return null

        val improvementName = tile.getTileResource().improvement ?: return null
        val improvement = tile.ruleset.tileImprovements[improvementName] ?: return null
        if (!tile.canBuildImprovement(improvement, unit.civInfo)) return null

        return UnitAction(UnitActionType.Create, "Create [$improvementName]",
            action = {
                tile.improvement = improvementName
                val city = tile.getCity()
                if (city != null) {
                    city.cityStats.update()
                    city.civInfo.updateDetailedCivResources()
                }
                unit.destroy()
            }.takeIf { unit.currentMovement > 0 })
    }
    
    // This entire function is deprecated since 3.15.4, as the 'can construct roads' unique is deprecated
        private fun addConstructRoadsAction(unit: MapUnit, tile: TileInfo, actionList: ArrayList<UnitAction>) {
            val improvement = RoadStatus.Road.improvement(unit.civInfo.gameInfo.ruleSet) ?: return
            if (unit.hasUnique("Can construct roads")
                    && tile.roadStatus == RoadStatus.None
                    && tile.improvementInProgress != RoadStatus.Road.name
                    && tile.isLand
                    && (improvement.techRequired == null || unit.civInfo.tech.isResearched(improvement.techRequired!!)))
                actionList += UnitAction(UnitActionType.ConstructRoad,
                        action = {
                            tile.improvementInProgress = RoadStatus.Road.name
                            tile.turnsToImprovement = improvement.getTurnsToBuild(unit.civInfo)
                        }.takeIf { unit.currentMovement > 0 })
        }
    //

    private fun addFoundCityAction(unit: MapUnit, actionList: ArrayList<UnitAction>, tile: TileInfo) {
        val getFoundCityAction = getFoundCityAction(unit, tile)
        if (getFoundCityAction != null) actionList += getFoundCityAction
    }

    /** Produce a [UnitAction] for founding a city.
     * @param unit The unit to do the founding.
     * @param tile The tile to found a city on.
     * @return null if impossible (the unit lacks the ability to found), 
     * or else a [UnitAction] 'defining' the founding. 
     * The [action][UnitAction.action] field will be null if the action cannot be done here and now   
     * (no movement left, too close to another city).
      */ 
    fun getFoundCityAction(unit: MapUnit, tile: TileInfo): UnitAction? {
        if (!unit.hasUnique("Founds a new city") || tile.isWater) return null

        if (unit.currentMovement <= 0 || tile.getTilesInDistance(3).any { it.isCityCenter() })
            return UnitAction(UnitActionType.FoundCity, uncivSound = UncivSound.Silent, action = null)

        val foundAction = {
            UncivGame.Current.settings.addCompletedTutorialTask("Found city")
            unit.civInfo.addCity(tile.position)
            if (tile.ruleset.tileImprovements.containsKey("City center"))
                tile.improvement = "City center"
            unit.destroy()
            UncivGame.Current.worldScreen.shouldUpdate = true
        }
        
        if (unit.civInfo.playerType == PlayerType.AI)
            return UnitAction(UnitActionType.FoundCity,  uncivSound = UncivSound.Silent, action = foundAction)

        return UnitAction(
                type = UnitActionType.FoundCity,
                uncivSound = UncivSound.Chimes,
                action = {
                    // check if we would be breaking a promise
                    val leaders = TestPromiseNotToSettle(unit.civInfo, tile)
                    if (leaders == null)
                        foundAction()
                    else {
                        // ask if we would be breaking a promise
                        val text = "Do you want to break your promise to [$leaders]?"
                        YesNoPopup(text, foundAction, UncivGame.Current.worldScreen).open(force = true)
                    }
                }
            )
    }

    /**
     * Checks whether a civ founding a city on a certain tile would break a promise.
     * @param civInfo The civilization trying to found a city 
     * @param tile The tile where the new city would go 
     * @return null if no promises broken, else a String listing the leader(s) we would p* off.
     */
    private fun TestPromiseNotToSettle(civInfo: CivilizationInfo, tile: TileInfo): String? {
        val brokenPromises = HashSet<String>()
        for (otherCiv in civInfo.getKnownCivs().filter { it.isMajorCiv() && !civInfo.isAtWarWith(it) }) {
            val diplomacyManager = otherCiv.getDiplomacyManager(civInfo)
            if (diplomacyManager.hasFlag(DiplomacyFlags.AgreedToNotSettleNearUs)) {
                val citiesWithin6Tiles = otherCiv.cities
                    .filter { it.getCenterTile().aerialDistanceTo(tile) <= 6 }
                    .filter { otherCiv.exploredTiles.contains(it.location) }
                if (citiesWithin6Tiles.isNotEmpty()) brokenPromises += otherCiv.getLeaderDisplayName()
            }
        }
        return if(brokenPromises.isEmpty()) null else brokenPromises.joinToString(", ")
    }

    private fun addPromoteAction(unit: MapUnit, actionList: ArrayList<UnitAction>) {
        if (unit.type.isCivilian() || !unit.promotions.canBePromoted()) return
        // promotion does not consume movement points, so we can do it always
        actionList += UnitAction(UnitActionType.Promote,
                uncivSound = UncivSound.Promote,
                action = {
                    UncivGame.Current.setScreen(PromotionPickerScreen(unit))
                })
    }

    private fun addSetupAction(unit: MapUnit, actionList: ArrayList<UnitAction>) {
        if (!unit.hasUnique("Must set up to ranged attack") || unit.isEmbarked()) return
        val isSetUp = unit.action == "Set Up"
        actionList += UnitAction(UnitActionType.SetUp,
                isCurrentAction = isSetUp,
                uncivSound = UncivSound.Setup,
                action = {
                    unit.action = Constants.unitActionSetUp
                    unit.useMovementPoints(1f)
                }.takeIf { unit.currentMovement > 0 && !isSetUp })
    }

    private fun addParadropAction(unit: MapUnit, actionList: ArrayList<UnitAction>, worldScreen: WorldScreen) {
        val paradropUniques = unit.getMatchingUniques("May Paradrop up to [] tiles from inside friendly territory")
        if (!paradropUniques.any() || unit.isEmbarked()) return
        unit.paradropRange = paradropUniques.maxOfOrNull { it.params[0] }!!.toInt()
        actionList += UnitAction(UnitActionType.Paradrop,
                isCurrentAction = unit.action == Constants.unitActionParadrop,
                action = {
                    if (unit.action != Constants.unitActionParadrop) {
                        unit.action = Constants.unitActionParadrop
                    } else {
                        unit.action = null
                    }
                }.takeIf {
                    unit.currentMovement == unit.getMaxMovement().toFloat() &&
                    unit.currentTile.isFriendlyTerritory(unit.civInfo) &&
                    !unit.isEmbarked()
                })

    }

    private fun addPillageAction(unit: MapUnit, actionList: ArrayList<UnitAction>, worldScreen: WorldScreen) {
        val pillageAction = getPillageAction(unit)
        if (pillageAction == null) return
        if (pillageAction.action == null)
            actionList += UnitAction(UnitActionType.Pillage, action = null)
        else actionList += UnitAction(type = UnitActionType.Pillage) {
            if (!worldScreen.hasOpenPopups()) {
                val pillageText = "Are you sure you want to pillage this [${unit.currentTile.improvement}]?"
                YesNoPopup(pillageText, { (pillageAction.action)(); worldScreen.shouldUpdate = true }).open()
            }
        }
    }

    fun getPillageAction(unit: MapUnit): UnitAction? {
        val tile = unit.currentTile
        if (unit.type.isCivilian() || tile.improvement == null) return null

        return UnitAction(UnitActionType.Pillage,
                action = {
                    // http://well-of-souls.com/civ/civ5_improvements.html says that naval improvements are destroyed upon pilllage
                    //    and I can't find any other sources so I'll go with that
                    if (tile.isLand) {
                        tile.improvementInProgress = tile.improvement
                        tile.turnsToImprovement = 2
                    }
                    tile.improvement = null
                    if (tile.resource != null) tile.getOwner()?.updateDetailedCivResources()    // this might take away a resource

                    val freePillage = unit.hasUnique("No movement cost to pillage") ||
                            (unit.type.isMelee() && unit.civInfo.hasUnique("Melee units pay no movement cost to pillage"))
                    if (!freePillage) unit.useMovementPoints(1f)

                    unit.healBy(25)
                }.takeIf { unit.currentMovement > 0 && canPillage(unit, tile) })
    }

    private fun addExplorationActions(unit: MapUnit, actionList: ArrayList<UnitAction>) {
        if (unit.baseUnit.movesLikeAirUnits()) return
        if (unit.action != Constants.unitActionExplore) {
            actionList += UnitAction(UnitActionType.Explore) {
                unit.action = Constants.unitActionExplore
                if (unit.currentMovement > 0) UnitAutomation.automatedExplore(unit)
            }
        } else actionList += UnitAction(UnitActionType.StopExploration) { unit.action = null }
    }

    private fun addUnitUpgradeAction(unit: MapUnit, actionList: ArrayList<UnitAction>) {
        val upgradeAction = getUpgradeAction(unit)
        if (upgradeAction != null) actionList += upgradeAction
    }

    fun getUpgradeAction(unit: MapUnit): UnitAction? {
        val tile = unit.currentTile
        if (unit.baseUnit().upgradesTo == null || tile.getOwner() != unit.civInfo
                || !unit.canUpgrade()) return null
        val goldCostOfUpgrade = unit.getCostOfUpgrade()
        val upgradedUnit = unit.getUnitToUpgradeTo()

        return UnitAction(UnitActionType.Upgrade,
                title = "Upgrade to [${upgradedUnit.name}] ([$goldCostOfUpgrade] gold)",
                uncivSound = UncivSound.Upgrade,
                action = {
                    unit.civInfo.addGold(-goldCostOfUpgrade)
                    val unitTile = unit.getTile()
                    unit.destroy()
                    val newunit = unit.civInfo.placeUnitNearTile(unitTile.position, upgradedUnit.name)!!
                    newunit.health = unit.health
                    newunit.promotions = unit.promotions
                    newunit.instanceName = unit.instanceName

                    for (promotion in newunit.baseUnit.promotions)
                        if (promotion !in newunit.promotions.promotions)
                            newunit.promotions.addPromotion(promotion, true)

                    newunit.updateUniques()
                    newunit.updateVisibleTiles()
                    newunit.currentMovement = 0f
                }.takeIf {
                    unit.civInfo.gold >= goldCostOfUpgrade && !unit.isEmbarked()
                            && unit.currentMovement == unit.getMaxMovement().toFloat()
                })
    }

    private fun addWorkerActions(unit: MapUnit, actionList: ArrayList<UnitAction>, tile: TileInfo, worldScreen: WorldScreen, unitTable: UnitTable) {
        // Constants.workerUnique deprecated since 3.15.5
        if (!unit.hasUnique(Constants.canBuildImprovements) && !unit.hasUnique(Constants.workerUnique)) return

        // Allow automate/unautomate when embarked, but not building improvements - see #1963
        if (Constants.unitActionAutomation == unit.action) {
            actionList += UnitAction(UnitActionType.StopAutomation) { unit.action = null }
        } else {
            actionList += UnitAction(UnitActionType.Automate,
                    action = {
                        unit.action = Constants.unitActionAutomation
                        WorkerAutomation(unit).automateWorkerAction()
                    }.takeIf { unit.currentMovement > 0 })
        }

        if (unit.isEmbarked()) return

        val canConstruct = unit.currentMovement > 0
                && !tile.isCityCenter()
                && unit.civInfo.gameInfo.ruleSet.tileImprovements.values.any { tile.canBuildImprovement(it, unit.civInfo) && unit.canBuildImprovement(it) }
        
        actionList += UnitAction(UnitActionType.ConstructImprovement,
                isCurrentAction = unit.currentTile.hasImprovementInProgress(),
                action = {
                    worldScreen.game.setScreen(ImprovementPickerScreen(tile, unit) { unitTable.selectUnit() })
                }.takeIf { canConstruct })
    }

    private fun addGreatPersonActions(unit: MapUnit, actionList: ArrayList<UnitAction>, tile: TileInfo) {

        if (unit.currentMovement > 0) for (unique in unit.getUniques()) when (unique.placeholderText) {
            "Can hurry technology research" -> {
                actionList += UnitAction(UnitActionType.HurryResearch,
                    uncivSound = UncivSound.Chimes,
                    action = {
                        unit.civInfo.tech.addScience(unit.civInfo.tech.getScienceFromGreatScientist())
                        addGoldPerGreatPersonUsage(unit.civInfo)
                        unit.destroy()
                    }.takeIf { unit.civInfo.tech.currentTechnologyName() != null }
                )
            }
            "Can start an []-turn golden age" -> {
                val turnsToGoldenAge = unique.params[0].toInt()
                actionList += UnitAction(UnitActionType.StartGoldenAge,
                    uncivSound = UncivSound.Chimes,
                    action = {
                        unit.civInfo.goldenAges.enterGoldenAge(turnsToGoldenAge)
                        addGoldPerGreatPersonUsage(unit.civInfo)
                        unit.destroy()
                    }.takeIf { unit.currentTile.getOwner() != null && unit.currentTile.getOwner() == unit.civInfo }
                )
            }
            "Can speed up construction of a wonder" -> {
                val canHurryWonder = if (!tile.isCityCenter()) false
                else {
                    val currentConstruction = tile.getCity()!!.cityConstructions.getCurrentConstruction()
                    if (currentConstruction !is Building) false
                    else currentConstruction.isWonder || currentConstruction.isNationalWonder
                }
                actionList += UnitAction(UnitActionType.HurryWonder,
                    uncivSound = UncivSound.Chimes,
                    action = {
                        tile.getCity()!!.cityConstructions.apply {
                            addProductionPoints(300 + 30 * tile.getCity()!!.population.population) //http://civilization.wikia.com/wiki/Great_engineer_(Civ5)
                            constructIfEnough()
                        }
                        addGoldPerGreatPersonUsage(unit.civInfo)
                        unit.destroy()
                    }.takeIf { canHurryWonder }
                )
            }
            "Can undertake a trade mission with City-State, giving a large sum of gold and [] Influence" -> {
                val canConductTradeMission = tile.owningCity?.civInfo?.isCityState() == true
                        && tile.owningCity?.civInfo?.isAtWarWith(unit.civInfo) == false
                val influenceEarned = unique.params[0].toInt()
                actionList += UnitAction(UnitActionType.ConductTradeMission,
                    uncivSound = UncivSound.Chimes,
                    action = {
                        // http://civilization.wikia.com/wiki/Great_Merchant_(Civ5)
                        var goldEarned = ((350 + 50 * unit.civInfo.getEraNumber()) * unit.civInfo.gameInfo.gameParameters.gameSpeed.modifier).toInt()
                        if (unit.civInfo.hasUnique("Double gold from Great Merchant trade missions"))
                            goldEarned *= 2
                        unit.civInfo.addGold(goldEarned)
                        tile.owningCity!!.civInfo.getDiplomacyManager(unit.civInfo).influence += influenceEarned
                        unit.civInfo.addNotification("Your trade mission to [${tile.owningCity!!.civInfo}] has earned you [${goldEarned}] gold and [$influenceEarned] influence!",
                            tile.owningCity!!.civInfo.civName, NotificationIcon.Gold, NotificationIcon.Culture)
                        addGoldPerGreatPersonUsage(unit.civInfo)
                        unit.destroy()
                    }.takeIf { canConductTradeMission }
                )
            }
        }
    }
    
    private fun addFoundReligionAction(unit: MapUnit, actionList: ArrayList<UnitAction>, tile: TileInfo) {
        if (!unit.hasUnique("May found a religion")) return // should later also include enhance religion
        if (!unit.civInfo.religionManager.mayUseGreatProphetAtAll(unit)) return
        actionList += UnitAction(UnitActionType.FoundReligion,
            uncivSound = UncivSound.Choir,
            action = {
                addGoldPerGreatPersonUsage(unit.civInfo)
                unit.civInfo.religionManager.useGreatProphet(unit)
                unit.destroy()
            }.takeIf { unit.civInfo.religionManager.mayUseGreatProphetNow(unit) }
        )
    }
    
    private fun addSpreadReligionActions(unit: MapUnit, actionList: ArrayList<UnitAction>, tile: TileInfo) {
        if (!unit.hasUnique("Can spread religion [] times")) return
        if (unit.religion == null) return
        val maxReligionSpreads = unit.maxReligionSpreads()
        if (!unit.abilityUsedCount.containsKey("Religion Spread")) return // This should be impossible anyways, but just in case
        if (maxReligionSpreads <= unit.abilityUsedCount["Religion Spread"]!!) return
        val city = tile.getCity()
        actionList += UnitAction(UnitActionType.SpreadReligion,
            title = "Spread [${unit.religion!!}]",
            uncivSound = UncivSound.Choir,
            action = {
                unit.abilityUsedCount["Religion Spread"] = unit.abilityUsedCount["Religion Spread"]!! + 1
                city!!.religion[unit.religion!!] = 100
                unit.currentMovement = 0f
                if (unit.abilityUsedCount["Religion Spread"] == maxReligionSpreads) {
                    addGoldPerGreatPersonUsage(unit.civInfo)
                    unit.destroy()
                }
            }.takeIf { unit.currentMovement > 0 && city != null && city.civInfo == unit.civInfo } // For now you can only convert your own cities
        )
    }
    
    fun getImprovementConstructionActions(unit: MapUnit, tile: TileInfo): ArrayList<UnitAction> {
        val finalActions = ArrayList<UnitAction>()
        var uniquesToCheck = unit.getMatchingUniques("Can construct []")
        if (unit.abilityUsedCount.containsKey("Religion Spread") && unit.abilityUsedCount["Religion Spread"]!! == 0 && unit.maxReligionSpreads() > 0)
            uniquesToCheck += unit.getMatchingUniques("Can construct [] if it hasn't spread religion yet")
        for (unique in uniquesToCheck) {
            val improvementName = unique.params[0]
            val improvement = tile.ruleset.tileImprovements[improvementName]
            if (improvement == null) continue
            finalActions += UnitAction(UnitActionType.Create,
                title = "Create [$improvementName]",
                uncivSound = UncivSound.Chimes,
                action = {
                    val unitTile = unit.getTile()
                    for (terrainFeature in tile.terrainFeatures.filter { unitTile.ruleset.tileImprovements.containsKey("Remove $it") })
                        unitTile.terrainFeatures.remove(terrainFeature)// remove forest/jungle/marsh
                    unitTile.improvement = improvementName
                    unitTile.improvementInProgress = null
                    unitTile.turnsToImprovement = 0
                    if (improvementName == Constants.citadel)
                        takeOverTilesAround(unit)
                    val city = unitTile.getCity()
                    if (city != null) {
                        city.cityStats.update()
                        city.civInfo.updateDetailedCivResources()
                    }
                    if (unit.hasUnique("Great Person - []"))
                        addGoldPerGreatPersonUsage(unit.civInfo)
                    unit.destroy()
                }.takeIf {
                    unit.currentMovement > 0f && tile.canBuildImprovement(improvement, unit.civInfo)
                            && !tile.isImpassible() // Not 100% sure that this check is necessary...
                })
        }
        return finalActions
    }

    private fun takeOverTilesAround(unit: MapUnit) {
        // This method should only be called for a citadel - therefore one of the neighbour tile
        // must belong to unit's civ, so minByOrNull in the nearestCity formula should be never `null`.
        // That is, unless a mod does not specify the proper unique - then fallbackNearestCity will take over.

        fun priority(tile: TileInfo): Int { // helper calculates priority (lower is better): distance plus razing malus
            val city = tile.getCity()!!       // !! assertion is guaranteed by the outer filter selector.
            return city.getCenterTile().aerialDistanceTo(tile) +
                    (if (city.isBeingRazed) 5 else 0)
        }
        fun fallbackNearestCity(unit: MapUnit) =
            unit.civInfo.cities.minByOrNull {
               it.getCenterTile().aerialDistanceTo(unit.currentTile) +
                   (if (it.isBeingRazed) 5 else 0)
            }!!

        // In the rare case more than one city owns tiles neighboring the citadel
        // this will prioritize the nearest one not being razed
        var nearestCity = unit.currentTile.neighbors
            .filter { it.getOwner() == unit.civInfo }
            .minByOrNull { priority(it) }?.getCity()
            ?: fallbackNearestCity(unit)

        // capture all tiles which do not belong to unit's civ and are not enemy cities
        // we use getTilesInDistance here, not neighbours to include the current tile as well
        val tilesToTakeOver = unit.currentTile.getTilesInDistance(1)
                .filter { !it.isCityCenter() && it.getOwner() != unit.civInfo }

        val civsToNotify = mutableSetOf<CivilizationInfo>()
        for (tile in tilesToTakeOver) {
            val otherCiv = tile.getOwner()
            if (otherCiv != null) {
                // decrease relations for -10 pt/tile
                if (!otherCiv.knows(unit.civInfo)) otherCiv.makeCivilizationsMeet(unit.civInfo)
                otherCiv.getDiplomacyManager(unit.civInfo).addModifier(DiplomaticModifiers.StealingTerritory, -10f)
                civsToNotify.add(otherCiv)
            }
            nearestCity.expansion.takeOwnership(tile)
        }

        for (otherCiv in civsToNotify)
            otherCiv.addNotification("[${unit.civInfo}] has stolen your territory!", unit.currentTile.position, unit.civInfo.civName, NotificationIcon.War)
    }

    private fun addGoldPerGreatPersonUsage(civInfo: CivilizationInfo) {
        val uniqueText = "Provides a sum of gold each time you spend a Great Person"
        val cityWithMausoleum = civInfo.cities.firstOrNull { it.containsBuildingUnique(uniqueText) }
                ?: return
        val goldEarned = (100 * civInfo.gameInfo.gameParameters.gameSpeed.modifier).toInt()
        civInfo.addGold(goldEarned)

        val mausoleum = cityWithMausoleum.cityConstructions.getBuiltBuildings().first { it.uniques.contains(uniqueText) }
        civInfo.addNotification("[${mausoleum.name}] has provided [$goldEarned] Gold!", cityWithMausoleum.location, NotificationIcon.Gold)
    }

    private fun addFortifyActions(actionList: ArrayList<UnitAction>, unit: MapUnit, unitTable: UnitTable) {

        val action = UnitAction(UnitActionType.Fortify,
                uncivSound = UncivSound.Fortify,
                action = {
                    unit.fortify()
                    unitTable.selectUnit()
                }.takeIf { unit.currentMovement > 0 })

        if (unit.health < 100) {
            val actionForWounded = action.copy(UnitActionType.FortifyUntilHealed,
                    title = UnitActionType.FortifyUntilHealed.value,
                    action = {
                        unit.fortifyUntilHealed()
                        unitTable.selectUnit()
                    }.takeIf { unit.currentMovement > 0 })
            actionList += actionForWounded
        }

        actionList += action
    }

    private fun addSleepActions(actionList: ArrayList<UnitAction>, unit: MapUnit, unitTable: UnitTable) {
        val isSleeping = unit.isSleeping()

        val action = UnitAction(UnitActionType.Sleep,
                isCurrentAction = isSleeping,
                action = {
                    unit.action = Constants.unitActionSleep
                    unitTable.selectUnit()
                }.takeIf { !isSleeping })

        if (unit.health < 100 && !isSleeping) {
            val actionForWounded = action.copy(UnitActionType.SleepUntilHealed,
                    title = UnitActionType.SleepUntilHealed.value,
                    action = {
                        unit.action = Constants.unitActionSleepUntilHealed
                        unitTable.selectUnit()
                    })
            actionList += actionForWounded
        }

        actionList += action
    }

    fun canPillage(unit: MapUnit, tile: TileInfo): Boolean {
        val tileImprovement = tile.getTileImprovement()
        // City ruins, Ancient Ruins, Barbarian Camp, City Center marked in json
        if (tileImprovement == null || tileImprovement.hasUnique("Unpillagable")) return false
        val tileOwner = tile.getOwner()
        // Can't pillage friendly tiles, just like you can't attack them - it's an 'act of war' thing
        return tileOwner == null || tileOwner == unit.civInfo || unit.civInfo.isAtWarWith(tileOwner)
    }

    private fun addGiftAction(unit: MapUnit, actionList: ArrayList<UnitAction>, tile: TileInfo) {
        val getGiftAction = getGiftAction(unit, tile)
        if (getGiftAction != null) actionList += getGiftAction
    }

    fun getGiftAction(unit: MapUnit, tile: TileInfo): UnitAction? {
        val recipient = tile.getOwner()
        // We need to be in another civs territory.
        if (recipient == null || recipient.isCurrentPlayer()) return null

        // City States only take miliary units (and GPs for certain civs)
        if (recipient.isCityState()) {
            if (unit.isGreatPerson()) return null    // Unless Sweden
            else if (!unit.baseUnit().matchesFilter("Military")) return null
        }
        // If gifting to major civ they need to be friendly
        else if (!tile.isFriendlyTerritory(unit.civInfo)) return null

        if (unit.currentMovement <= 0)
            return UnitAction(UnitActionType.GiftUnit, uncivSound = UncivSound.Silent, action = null)

        val giftAction = {
            if (recipient.isCityState()) {
                if (unit.isGreatPerson())
                    recipient.getDiplomacyManager(unit.civInfo).influence += 90
                else
                    recipient.getDiplomacyManager(unit.civInfo).influence += 5
                recipient.updateAllyCivForCityState()
            }
            else recipient.getDiplomacyManager(unit.civInfo).addModifier(DiplomaticModifiers.GaveUsUnits, 5f)

            unit.gift(recipient)
            // UncivGame.Current.worldScreen.shouldUpdate = true
        }

        return UnitAction(UnitActionType.GiftUnit, uncivSound = UncivSound.Silent, action = giftAction)
    }
}
