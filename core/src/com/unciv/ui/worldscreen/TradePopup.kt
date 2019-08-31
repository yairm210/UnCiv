package com.unciv.ui.worldscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeType
import com.unciv.models.gamebasics.tr
import com.unciv.ui.trade.DiplomacyScreen
import com.unciv.ui.utils.addSeparator
import com.unciv.ui.utils.toLabel
import com.unciv.ui.worldscreen.optionstable.PopupTable
import kotlin.math.max

class TradePopup(worldScreen: WorldScreen): PopupTable(worldScreen){
    init{
        val currentPlayerCiv = worldScreen.viewingCiv
        val tradeRequest = currentPlayerCiv.tradeRequests.first()

        val requestingCiv = worldScreen.gameInfo.getCivilization(tradeRequest.requestingCiv)
        val translatedNation = requestingCiv.getTranslatedNation()
        val otherCivLeaderName = "[${translatedNation.leaderName}] of [${translatedNation.getNameTranslation()}]".tr()

        add(otherCivLeaderName.toLabel())
        addSeparator()

        val trade = tradeRequest.trade
        val tradeOffersTable = Table().apply { defaults().pad(10f) }
        for(i in 0..max(trade.theirOffers.lastIndex, trade.ourOffers.lastIndex)){
            if(trade.theirOffers.lastIndex>=i) tradeOffersTable.add(trade.theirOffers[i].getOfferText().toLabel())
            else tradeOffersTable.add()
            if(trade.ourOffers.lastIndex>=i) tradeOffersTable.add(trade.ourOffers[i].getOfferText().toLabel())
            else tradeOffersTable.add()
            tradeOffersTable.row()
        }
        add(tradeOffersTable).row()

        addGoodSizedLabel(translatedNation.tradeRequest).colspan(columns).row()

        addButton("Sounds good!"){
            val tradeLogic = TradeLogic(currentPlayerCiv, requestingCiv)
            tradeLogic.currentTrade.set(trade)
            tradeLogic.acceptTrade()
            currentPlayerCiv.tradeRequests.remove(tradeRequest)
            close()
            PopupTable(worldScreen).apply {
                add(otherCivLeaderName.toLabel()).colspan(2)
                addSeparator()
                addGoodSizedLabel("Excellent!").row()
                addButton("Farewell."){
                    close()
                    worldScreen.shouldUpdate=true
                    // in all cases, worldScreen.shouldUpdate should be set to true when we remove the last of the popups
                    // in order for the next trade to appear immediately
                }
                open()
            }
            requestingCiv.addNotification("[${currentPlayerCiv.civName}] has accepted your trade request", Color.GOLD)
        }
        addButton("Not this time.".tr()){
            currentPlayerCiv.tradeRequests.remove(tradeRequest)

            val diplomacyManager = requestingCiv.getDiplomacyManager(currentPlayerCiv)
            if(trade.ourOffers.all { it.type==TradeType.Luxury_Resource } && trade.theirOffers.all { it.type==TradeType.Luxury_Resource })
                diplomacyManager.setFlag(DiplomacyFlags.DeclinedLuxExchange,20) // offer again in 20 turns

            if(trade.ourOffers.any{ it.type==TradeType.Treaty && it.name== Constants.peaceTreaty })
                diplomacyManager.setFlag(DiplomacyFlags.DeclinedPeace,5)

            close()
            requestingCiv.addNotification("[${currentPlayerCiv.civName}] has denied your trade request", Color.GOLD)

            worldScreen.shouldUpdate=true
        }
        addButton("How about something else...".tr()){
            currentPlayerCiv.tradeRequests.remove(tradeRequest)
            close()

            val diplomacyScreen= DiplomacyScreen()
            val tradeTable =  diplomacyScreen.setTrade(requestingCiv)
            tradeTable.tradeLogic.currentTrade.set(trade)
            tradeTable.offerColumnsTable.update()
            worldScreen.game.screen=diplomacyScreen
            worldScreen.shouldUpdate=true
        }
        open()
    }
}