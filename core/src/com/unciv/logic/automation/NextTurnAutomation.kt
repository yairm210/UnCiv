package com.unciv.logic.automation

import com.badlogic.gdx.graphics.Color
import com.unciv.Constants
import com.unciv.logic.city.CityInfo
import com.unciv.logic.civilization.*
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.logic.map.MapUnit
import com.unciv.logic.trade.*
import com.unciv.models.metadata.GameSpeed
import com.unciv.models.ruleset.VictoryType
import com.unciv.models.ruleset.tech.Technology
import com.unciv.models.translations.tr
import kotlin.math.min

class NextTurnAutomation{

    /** Top-level AI turn tasklist */
    fun automateCivMoves(civInfo: CivilizationInfo) {
        if (civInfo.isBarbarian()) return BarbarianAutomation(civInfo).automate()

        respondToDemands(civInfo)
        respondToTradeRequests(civInfo)

        if(civInfo.isMajorCiv()) {
            offerDeclarationOfFriendship(civInfo)
            offerPeaceTreaty(civInfo)
            offerResearchAgreement(civInfo)
            exchangeTechs(civInfo)
            exchangeLuxuries(civInfo)
            issueRequests(civInfo)
            adoptPolicy(civInfo)
        } else {
            getFreeTechForCityStates(civInfo)
        }

        chooseTechToResearch(civInfo)
        updateDiplomaticRelationship(civInfo)
        declareWar(civInfo)
        automateCityBombardment(civInfo)
        useGold(civInfo)
        automateUnits(civInfo)
        reassignWorkedTiles(civInfo)
        trainSettler(civInfo)

        civInfo.popupAlerts.clear() // AIs don't care about popups.
    }

    private fun respondToTradeRequests(civInfo: CivilizationInfo) {
        for(tradeRequest in civInfo.tradeRequests){
            val otherCiv = civInfo.gameInfo.getCivilization(tradeRequest.requestingCiv)
            val tradeLogic = TradeLogic(civInfo, otherCiv)
            tradeLogic.currentTrade.set(tradeRequest.trade)
            if(TradeEvaluation().isTradeAcceptable(tradeLogic.currentTrade,civInfo,otherCiv)){
                tradeLogic.acceptTrade()
                otherCiv.addNotification("[${civInfo.civName}] has accepted your trade request", Color.GOLD)
            }
            else{
                otherCiv.addNotification("[${civInfo.civName}] has denied your trade request", Color.GOLD)
            }
        }
        civInfo.tradeRequests.clear()
    }

    private fun respondToDemands(civInfo: CivilizationInfo) {
        for(popupAlert in civInfo.popupAlerts){
            if(popupAlert.type==AlertType.DemandToStopSettlingCitiesNear){  // we're called upon to make a decision
                val demandingCiv = civInfo.gameInfo.getCivilization(popupAlert.value)
                val diploManager = civInfo.getDiplomacyManager(demandingCiv)
                if(Automation().threatAssessment(civInfo,demandingCiv) >= ThreatLevel.High)
                    diploManager.agreeNotToSettleNear()
                else diploManager.refuseDemandNotToSettleNear()
            }
        }
    }

    private fun tryGainInfluence(civInfo: CivilizationInfo, cityState:CivilizationInfo){
        if(civInfo.gold<250) return // save up
        if(cityState.getDiplomacyManager(civInfo).influence<20){
            civInfo.giveGoldGift(cityState,250)
            return
        }
        if(civInfo.gold<500) return // it's not worth it to invest now, wait until you have enough for 2
        civInfo.giveGoldGift(cityState,500)
        return
    }

    /** allow AI to spend money to purchase city-state friendship, buildings & unit */
    private fun useGold(civInfo: CivilizationInfo) {
        if(civInfo.victoryType()==VictoryType.Cultural){
            for(cityState in civInfo.getKnownCivs()
                    .filter { it.isCityState() && it.getCityStateType()==CityStateType.Cultured }){
                val diploManager = cityState.getDiplomacyManager(civInfo)
                if(diploManager.influence < 40){ // we want to gain influence with them
                    tryGainInfluence(civInfo,cityState)
                    return
                }
            }
        }

        if(civInfo.getHappiness() < 5){
            for(cityState in civInfo.getKnownCivs()
                    .filter { it.isCityState() && it.getCityStateType()==CityStateType.Mercantile }){
                val diploManager = cityState.getDiplomacyManager(civInfo)
                if(diploManager.influence < 40){ // we want to gain influence with them
                    tryGainInfluence(civInfo,cityState)
                    return
                }
            }
        }


        for (city in civInfo.cities.sortedByDescending{ it.population.population }) {
            val construction = city.cityConstructions.getCurrentConstruction()
            if (construction.canBePurchased()
                    && city.civInfo.gold / 3 >= construction.getGoldCost(civInfo) ) {
                city.cityConstructions.purchaseConstruction(construction.name)
            }
        }
    }

    private fun exchangeTechs(civInfo: CivilizationInfo) {
        if(!civInfo.gameInfo.getDifficulty().aisExchangeTechs) return
        val otherCivList = civInfo.getKnownCivs()
                .filter { it.playerType == PlayerType.AI && it.isMajorCiv() }
                .sortedBy { it.tech.techsResearched.size }

        for (otherCiv in otherCivList) {
            val tradeLogic = TradeLogic(civInfo, otherCiv)
            var ourGold = tradeLogic.ourAvailableOffers.first { it.type == TradeType.Gold }.amount
            val ourTradableTechs = tradeLogic.ourAvailableOffers
                    .filter { it.type == TradeType.Technology }
            val theirTradableTechs = tradeLogic.theirAvailableOffers
                    .filter { it.type == TradeType.Technology }

            for (theirOffer in theirTradableTechs) {
                val theirValue = TradeEvaluation().evaluateBuyCost(theirOffer, civInfo, otherCiv)
                val ourOfferList = ourTradableTechs.filter{
                    TradeEvaluation().evaluateBuyCost(it, otherCiv, civInfo) == theirValue
                            && !tradeLogic.currentTrade.ourOffers.contains(it) }

                if (ourOfferList.isNotEmpty()) {
                    tradeLogic.currentTrade.ourOffers.add(ourOfferList.random())
                    tradeLogic.currentTrade.theirOffers.add(theirOffer)
                } else {
                    //try to buy tech with money, not spending more than 1/3 of treasury
                    if (ourGold / 2 >= theirValue)
                    {
                        tradeLogic.currentTrade.ourOffers.add(TradeOffer("Gold".tr(), TradeType.Gold, 0, theirValue))
                        tradeLogic.currentTrade.theirOffers.add(theirOffer)
                        ourGold -= theirValue
                    }
                }
            }

            if (tradeLogic.currentTrade.theirOffers.isNotEmpty()) {
                tradeLogic.acceptTrade()
            }
        }
    }

    private fun getFreeTechForCityStates(civInfo: CivilizationInfo) {
        //City states automatically get all invented techs
        for (otherCiv in civInfo.getKnownCivs().filterNot { it.isCityState() }) {
            for (entry in otherCiv.tech.techsResearched
                    .filterNot { civInfo.tech.isResearched(it) }
                    .filter { civInfo.tech.canBeResearched(it) }) {
                civInfo.tech.addTechnology(entry)
            }
        }
        return
    }

    private fun chooseTechToResearch(civInfo: CivilizationInfo) {
        if (civInfo.tech.techsToResearch.isEmpty()) {
            val researchableTechs = civInfo.gameInfo.ruleSet.technologies.values
                    .filter { !civInfo.tech.isResearched(it.name) && civInfo.tech.canBeResearched(it.name) }
            val techsGroups = researchableTechs.groupBy { it.cost }
            val costs = techsGroups.keys.sorted()

            val tech: Technology
            if (researchableTechs.isEmpty()) { // no non-researched techs available, go for future tech
                civInfo.tech.techsToResearch.add(Constants.futureTech)
                return
            }

            val techsCheapest = techsGroups[costs[0]]!!
            //Do not consider advanced techs if only one tech left in cheapest groupe
            if (techsCheapest.size == 1 || costs.size == 1) {
                tech = techsCheapest.random()
            } else {
                //Choose randomly between cheapest and second cheapest groupe
                val techsAdvanced = techsGroups[costs[1]]!!
                tech = (techsCheapest + techsAdvanced).random()
            }

            civInfo.tech.techsToResearch.add(tech.name)
        }
    }

    private fun adoptPolicy(civInfo: CivilizationInfo) {
        while (civInfo.policies.canAdoptPolicy()) {

            val adoptablePolicies = civInfo.gameInfo.ruleSet.policyBranches.values
                    .flatMap { it.policies.union(listOf(it)) }
                    .filter { civInfo.policies.isAdoptable(it) }

            // This can happen if the player is crazy enough to have the game continue forever and he disabled cultural victory
            if(adoptablePolicies.isEmpty()) return


            val preferredVictoryType = civInfo.victoryType()
            val policyBranchPriority =
                    when(preferredVictoryType) {
                        VictoryType.Cultural -> listOf("Piety", "Freedom", "Tradition", "Rationalism")
                        VictoryType.Scientific -> listOf("Rationalism","Commerce","Liberty","Freedom")
                        VictoryType.Domination-> listOf("Autocracy","Honor","Liberty","Rationalism")
                        VictoryType.Neutral -> listOf()
                    }
            val policiesByPreference = adoptablePolicies
                    .groupBy {
                        if (it.branch.name in policyBranchPriority)
                            policyBranchPriority.indexOf(it.branch.name) else 10
                    }

            val preferredPolicies = policiesByPreference.minBy { it.key }!!.value

            val policyToAdopt = preferredPolicies.random()
            civInfo.policies.adopt(policyToAdopt)
        }
    }

    fun potentialLuxuryTrades(civInfo:CivilizationInfo, otherCivInfo:CivilizationInfo): ArrayList<Trade> {
        val tradeLogic = TradeLogic(civInfo, otherCivInfo)
        val ourTradableLuxuryResources = tradeLogic.ourAvailableOffers
                .filter { it.type == TradeType.Luxury_Resource && it.amount > 1 }
        val theirTradableLuxuryResources = tradeLogic.theirAvailableOffers
                .filter { it.type == TradeType.Luxury_Resource && it.amount > 1 }
        val weHaveTheyDont = ourTradableLuxuryResources
                .filter { resource ->
                    tradeLogic.theirAvailableOffers
                            .none { it.name == resource.name && it.type == TradeType.Luxury_Resource }
                }
        val theyHaveWeDont = theirTradableLuxuryResources
                .filter { resource ->
                    tradeLogic.ourAvailableOffers
                            .none { it.name == resource.name && it.type == TradeType.Luxury_Resource }
                }
        val trades = ArrayList<Trade>()
        for(i in 0..min(weHaveTheyDont.lastIndex, theyHaveWeDont.lastIndex)){
            val trade = Trade()
            trade.ourOffers.add(weHaveTheyDont[i].copy(amount = 1))
            trade.theirOffers.add(theyHaveWeDont[i].copy(amount = 1))
            trades.add(trade)
        }
        return trades
    }

    private fun exchangeLuxuries(civInfo: CivilizationInfo) {
        val knownCivs = civInfo.getKnownCivs()

        // Player trades are... more complicated.
        // When the AI offers a trade, it's not immediately accepted,
        // so what if it thinks that it has a spare luxury and offers it to two human players?
        // What's to stop the AI "nagging" the player to accept a luxury trade?
        // We should A. add some sort of timer (20? 30 turns?) between luxury trade requests if they're denied
        // B. have a way for the AI to keep track of the "pending offers" - see DiplomacyManager.resourcesFromTrade

        for (otherCiv in knownCivs.filter { it.isPlayerCivilization() && !it.isAtWarWith(civInfo)
                && !civInfo.getDiplomacyManager(it).hasFlag(DiplomacyFlags.DeclinedLuxExchange)}) {

            val relationshipLevel = civInfo.getDiplomacyManager(otherCiv).relationshipLevel()
            if(relationshipLevel <= RelationshipLevel.Enemy)
                continue

            val trades = potentialLuxuryTrades(civInfo,otherCiv)
            for(trade in trades){
                val tradeRequest = TradeRequest(civInfo.civName, trade.reverse())
                otherCiv.tradeRequests.add(tradeRequest)
            }
        }

        // AI luxury trades are automatically accepted
        for (otherCiv in knownCivs.filter { !it.isPlayerCivilization() && !it.isAtWarWith(civInfo) }) {
            val trades = potentialLuxuryTrades(civInfo,otherCiv)
            for(trade in trades){
                val tradeLogic = TradeLogic(civInfo,otherCiv)
                tradeLogic.currentTrade.ourOffers.addAll(trade.ourOffers)
                tradeLogic.currentTrade.theirOffers.addAll(trade.theirOffers)
                tradeLogic.acceptTrade()
            }
        }


    }

    fun getMinDistanceBetweenCities(civ1: CivilizationInfo, civ2: CivilizationInfo): Int {
        return getClosestCities(civ1,civ2).arialDistance
    }

    data class CityDistance(val city1:CityInfo, val city2:CityInfo, val arialDistance: Int)
    fun getClosestCities(civ1: CivilizationInfo, civ2: CivilizationInfo): CityDistance {
        val cityDistances = arrayListOf<CityDistance>()
        for(civ1city in civ1.cities)
            for(civ2city in civ2.cities)
                cityDistances.add(CityDistance(civ1city,civ2city,
                        civ1city.getCenterTile().arialDistanceTo(civ2city.getCenterTile())))

        return cityDistances.minBy { it.arialDistance }!!
    }

    private fun offerDeclarationOfFriendship(civInfo: CivilizationInfo) {
        val canDeclareFriendshipCiv = civInfo.getKnownCivs()
                .asSequence()
                .filter { it.isMajorCiv() }
                .filter { !it.isAtWarWith(civInfo) }
                .filter { it.getDiplomacyManager(civInfo).relationshipLevel() > RelationshipLevel.Neutral }
                .filter { !civInfo.getDiplomacyManager(it).hasFlag(DiplomacyFlags.DeclarationOfFriendship) }
                .filter { !civInfo.getDiplomacyManager(it).hasFlag(DiplomacyFlags.Denunceation) }
                .sortedByDescending { it.getDiplomacyManager(civInfo).relationshipLevel() }
                .toList()
        for (civ in canDeclareFriendshipCiv) {
            // Default setting is 5, this will be changed according to different civ.
            if ((1..10).random()<=5) civInfo.getDiplomacyManager(civ).signDeclarationOfFriendship()
            else continue
        }
    }

    private fun offerResearchAgreement(civInfo: CivilizationInfo) {
        // if Civ has researched future tech, they will not want to sign RA.
        if (civInfo.tech.getTechUniques().contains("Enables Research agreements")
                && civInfo.gold >= civInfo.goldCostOfSignResearchAgreement()
                && civInfo.gameInfo.ruleSet.technologies.values.any { !civInfo.tech.isResearched(it.name) && civInfo.tech.canBeResearched(it.name) }){
            val canSignResearchAgreementCiv = civInfo.getKnownCivs()
                    .asSequence()
                    .filter { it.isMajorCiv() }
                    .filter { civInfo.getDiplomacyManager(it).hasFlag(DiplomacyFlags.DeclarationOfFriendship) }
                    .filter { !civInfo.getDiplomacyManager(it).hasFlag(DiplomacyFlags.ResearchAgreement) }
                    .filter { !it.getDiplomacyManager(civInfo).hasFlag(DiplomacyFlags.ResearchAgreement) }
                    .filter { it.tech.getTechUniques().contains("Enables Research agreements") }
                    .filter { it.gold >= civInfo.goldCostOfSignResearchAgreement() }
                    .sortedByDescending { it.statsForNextTurn.science }
                    .toList()
            val duration = when(civInfo.gameInfo.gameParameters.gameSpeed) {
                GameSpeed.Quick -> 25
                GameSpeed.Standard -> 30
                GameSpeed.Epic -> 45
            }
            for (otherCiv in canSignResearchAgreementCiv) {
                // if otherCiv has researched future tech, they will not want to sign RA.
                if (otherCiv.gameInfo.ruleSet.technologies.values.none { !otherCiv.tech.isResearched(it.name) && otherCiv.tech.canBeResearched(it.name) })
                    continue
                // Default setting is 5, this will be changed according to different civ.
                if ((1..10).random()<=5
                        && civInfo.gold >= civInfo.goldCostOfSignResearchAgreement()) {
                    val tradeLogic = TradeLogic(civInfo, otherCiv)
                    tradeLogic.currentTrade.ourOffers.add(TradeOffer(Constants.researchAgreement, TradeType.Treaty, duration, civInfo.goldCostOfSignResearchAgreement()))
                    tradeLogic.currentTrade.theirOffers.add(TradeOffer(Constants.researchAgreement, TradeType.Treaty, duration, civInfo.goldCostOfSignResearchAgreement()))
                    if (otherCiv.isPlayerCivilization())
                        otherCiv.tradeRequests.add(TradeRequest(civInfo.civName, tradeLogic.currentTrade.reverse()))
                    // Default setting is 5, this will be changed according to different civ.
                    else if ((1..10).random()<=5) tradeLogic.acceptTrade()
                }
            }
        }
    }

    private fun offerPeaceTreaty(civInfo: CivilizationInfo) {
        if (!civInfo.isAtWar() || civInfo.cities.isEmpty() || civInfo.diplomacy.isEmpty()) return

        val ourCombatStrength = Automation().evaluteCombatStrength(civInfo)
        val enemiesCiv = civInfo.diplomacy.filter { it.value.diplomaticStatus == DiplomaticStatus.War }
                .map { it.value.otherCiv() }
                .filterNot { it == civInfo || it.isBarbarian() || it.cities.isEmpty() }
                .filter { !civInfo.getDiplomacyManager(it).hasFlag(DiplomacyFlags.DeclinedPeace) }

        for (enemy in enemiesCiv) {
            val enemiesStrength = Automation().evaluteCombatStrength(enemy)
            if (civInfo.victoryType()!=VictoryType.Cultural
                    && enemiesStrength < ourCombatStrength*2 ) {
                continue //We're losing, but can still fight. Refuse peace.
            }

            // pay for peace
            val tradeLogic = TradeLogic(civInfo, enemy)

            tradeLogic.currentTrade.ourOffers.add(TradeOffer(Constants.peaceTreaty, TradeType.Treaty, 30))
            tradeLogic.currentTrade.theirOffers.add(TradeOffer(Constants.peaceTreaty, TradeType.Treaty, 30))

            var moneyWeNeedToPay = -TradeEvaluation().evaluatePeaceCostForThem(civInfo, enemy)
            if (moneyWeNeedToPay > 0) {
                if (moneyWeNeedToPay > civInfo.gold && civInfo.gold > 0) { // we need to make up for this somehow...
                    moneyWeNeedToPay = civInfo.gold
                }
                if (civInfo.gold > 0) tradeLogic.currentTrade.ourOffers.add(TradeOffer("Gold".tr(), TradeType.Gold, 0, moneyWeNeedToPay))
            }

            if (enemy.isPlayerCivilization())
                enemy.tradeRequests.add(TradeRequest(civInfo.civName, tradeLogic.currentTrade.reverse()))
            else {
                if (enemy.victoryType()!=VictoryType.Cultural
                        && enemy.getCivUnits().filter { !it.type.isCivilian() }.size > enemy.cities.size
                        && enemy.getHappiness() > 0) {
                    continue //enemy AI has too large army and happiness. It continues to fight for profit.
                }
                tradeLogic.acceptTrade()
            }
        }
    }

    private fun updateDiplomaticRelationship(civInfo: CivilizationInfo) {
        // Check if city-state invaded by other civs
        if (civInfo.isCityState()) {
            for (otherCiv in civInfo.getKnownCivs().filter { it.isMajorCiv() }) {
                if(civInfo.isAtWarWith(otherCiv)) continue
                val diplomacy = civInfo.getDiplomacyManager(otherCiv)

                val unitsInBorder = otherCiv.getCivUnits().count { !it.type.isCivilian() && it.getTile().getOwner() == civInfo }
                if (unitsInBorder > 0 && diplomacy.relationshipLevel() < RelationshipLevel.Friend) {
                    diplomacy.influence -= 10f
                    if (!diplomacy.hasFlag(DiplomacyFlags.BorderConflict)) {
                        otherCiv.popupAlerts.add(PopupAlert(AlertType.BorderConflict,civInfo.civName))
                        diplomacy.setFlag(DiplomacyFlags.BorderConflict,10)
                    }
                }
            }
        }
    }

    private fun declareWar(civInfo: CivilizationInfo) {
        if (civInfo.isCityState()) return
        if (civInfo.victoryType() == VictoryType.Cultural) return
        if (civInfo.cities.isEmpty() || civInfo.diplomacy.isEmpty()) return
        if (civInfo.isAtWar() || civInfo.getHappiness() <= 0) return

        val ourMilitaryUnits = civInfo.getCivUnits().filter { !it.type.isCivilian() }.size
        if (ourMilitaryUnits < civInfo.cities.size) return

        //evaluate war
        val enemyCivs = civInfo.getKnownCivs()
                .filterNot { it == civInfo || it.cities.isEmpty() || !civInfo.getDiplomacyManager(it).canDeclareWar() }
        if (enemyCivs.isEmpty()) return

        var enemyCivsToEvaluate = enemyCivs.filter { getMinDistanceBetweenCities(civInfo, it) <= 7 }

        var isFightingFarAway = false
        if (enemyCivsToEvaluate.isEmpty()) { // so, there ARE civs we can declare war on, just not close by
            if (civInfo.victoryType() != VictoryType.Domination) return // No point in attacking civs that are too far away
            enemyCivsToEvaluate = enemyCivs
            isFightingFarAway = true
        }

        val weakestCloseCiv = enemyCivsToEvaluate.minBy { Automation().evaluteCombatStrength(it) }!!
        val weakestCloseCivCombatStrength = Automation().evaluteCombatStrength(weakestCloseCiv)
        val ourCombatStrength = Automation().evaluteCombatStrength(civInfo)

        val amountWeNeedToBeStronger =
                if (civInfo.victoryType() == VictoryType.Domination && !isFightingFarAway) 1.5f else 2f

        if (weakestCloseCivCombatStrength * amountWeNeedToBeStronger < ourCombatStrength)
            civInfo.getDiplomacyManager(weakestCloseCiv).declareWar()
    }

    private fun automateUnits(civInfo: CivilizationInfo) {
        val rangedUnits = mutableListOf<MapUnit>()
        val meleeUnits = mutableListOf<MapUnit>()
        val civilianUnits = mutableListOf<MapUnit>()
        val generals = mutableListOf<MapUnit>()

        for (unit in civInfo.getCivUnits()) {
            if (unit.promotions.canBePromoted()) {
                val availablePromotions = unit.promotions.getAvailablePromotions()
                if (availablePromotions.isNotEmpty())
                    unit.promotions.addPromotion(availablePromotions.random().name)
            }

            when {
                unit.type.isRanged() -> rangedUnits.add(unit)
                unit.type.isMelee() -> meleeUnits.add(unit)
                unit.name == "Great General" -> generals.add(unit) //generals move after military units
                else -> civilianUnits.add(unit)
            }
        }

        for (unit in civilianUnits) UnitAutomation().automateUnitMoves(unit) // They move first so that combat units can accompany a settler
        for (unit in rangedUnits) UnitAutomation().automateUnitMoves(unit)
        for (unit in meleeUnits) UnitAutomation().automateUnitMoves(unit)
        for (unit in generals) UnitAutomation().automateUnitMoves(unit)
    }

    private fun automateCityBombardment(civInfo: CivilizationInfo) {
        for (city in civInfo.cities) UnitAutomation().tryBombardEnemy(city)
    }

    private fun reassignWorkedTiles(civInfo: CivilizationInfo) {
        for (city in civInfo.cities) {
            if (city.isPuppet && city.population.population > 9
                    && !city.isInResistance()) {
                city.annexCity()
            }

            city.reassignWorkers()

            city.cityConstructions.chooseNextConstruction()
            if (city.health < city.getMaxHealth())
                Automation().trainMilitaryUnit(city) // override previous decision if city is under attack
        }
    }

    private fun trainSettler(civInfo: CivilizationInfo) {
        if(civInfo.isCityState()) return
        if(civInfo.isAtWar()) return // don't train settlers when you could be training troops.
        if(civInfo.victoryType()==VictoryType.Cultural && civInfo.cities.size >3) return
        if (civInfo.cities.any()
                && civInfo.getHappiness() > civInfo.cities.size + 5
                && civInfo.getCivUnits().none { it.name == Constants.settler }
                && civInfo.cities.none { it.cityConstructions.currentConstruction == Constants.settler }) {

            val bestCity = civInfo.cities.maxBy { it.cityStats.currentCityStats.production }!!
            if (bestCity.cityConstructions.builtBuildings.size > 1) // 2 buildings or more, otherwise focus on self first
                bestCity.cityConstructions.currentConstruction = Constants.settler
        }
    }


    private fun issueRequests(civInfo: CivilizationInfo) {
        for(otherCiv in civInfo.getKnownCivs().filter { it.isMajorCiv() }){
            val diploManager = civInfo.getDiplomacyManager(otherCiv)
            if(diploManager.hasFlag(DiplomacyFlags.SettledCitiesNearUs))
                onCitySettledNearBorders(civInfo,otherCiv)
        }
    }

    fun onCitySettledNearBorders(civInfo: CivilizationInfo, otherCiv:CivilizationInfo){
        val diplomacyManager = civInfo.getDiplomacyManager(otherCiv)
        when {
            diplomacyManager.hasFlag(DiplomacyFlags.IgnoreThemSettlingNearUs) -> {}
            diplomacyManager.hasFlag(DiplomacyFlags.AgreedToNotSettleNearUs) -> {
                otherCiv.popupAlerts.add(PopupAlert(AlertType.CitySettledNearOtherCivDespiteOurPromise, civInfo.civName))
                diplomacyManager.setFlag(DiplomacyFlags.IgnoreThemSettlingNearUs,100)
                diplomacyManager.setModifier(DiplomaticModifiers.BetrayedPromiseToNotSettleCitiesNearUs,-20f)
            }
            else -> {
                val threatLevel = Automation().threatAssessment(civInfo,otherCiv)
                if(threatLevel<ThreatLevel.High) // don't piss them off for no reason please.
                    otherCiv.popupAlerts.add(PopupAlert(AlertType.DemandToStopSettlingCitiesNear, civInfo.civName))
            }
        }
        diplomacyManager.removeFlag(DiplomacyFlags.SettledCitiesNearUs)
    }

}
