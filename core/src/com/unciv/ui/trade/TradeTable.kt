package com.unciv.ui.trade

import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeRequest
import com.unciv.models.gamebasics.tr
import com.unciv.ui.utils.CameraStageBaseScreen
import com.unciv.ui.utils.disable
import com.unciv.ui.utils.enable
import com.unciv.ui.utils.onClick

class TradeTable(val otherCivilization: CivilizationInfo, stage: Stage, onTradeComplete: () -> Unit): Table(CameraStageBaseScreen.skin){
    val currentPlayerCiv = otherCivilization.gameInfo.getCurrentPlayerCivilization()
    var tradeLogic = TradeLogic(currentPlayerCiv,otherCivilization)
    var offerColumnsTable = OfferColumnsTable(tradeLogic, stage) { onChange() }
    var offerColumnsTableWrapper = Table() // This is so that after a trade has been traded, we can switch out the offersToDisplay to start anew - this is the easiest way
    val offerButton = TextButton("Offer trade".tr(), CameraStageBaseScreen.skin)

    fun isTradeOffered() = otherCivilization.tradeRequests.any{it.requestingCiv==currentPlayerCiv.civName}

    fun retractOffer(){
        otherCivilization.tradeRequests.removeAll { it.requestingCiv==currentPlayerCiv.civName }
        offerButton.setText("Offer trade".tr())
    }

    init{
        offerColumnsTableWrapper.add(offerColumnsTable)
        add(offerColumnsTableWrapper).row()

        val lowerTable = Table().apply { defaults().pad(10f) }

        val existingOffer = otherCivilization.tradeRequests.firstOrNull{it.requestingCiv==currentPlayerCiv.civName}
        if(existingOffer!=null){
            tradeLogic.currentTrade.set(existingOffer.trade.reverse())
            offerColumnsTable.update()
        }

        if(isTradeOffered()) offerButton.setText("Retract offer".tr()) // todo translation
        else offerButton.setText("Offer trade".tr())

        offerButton.onClick {
            if(isTradeOffered()) {
                retractOffer()
                return@onClick
            }

            otherCivilization.tradeRequests.add(TradeRequest(currentPlayerCiv.civName,tradeLogic.currentTrade.reverse()))
            offerButton.setText("Retract offer".tr())
        }

        lowerTable.add(offerButton)

        lowerTable.pack()
        lowerTable.y = 10f
        add(lowerTable)
        pack()
    }

    private fun onChange(){
        offerColumnsTable.update()
        retractOffer()
        if(tradeLogic.currentTrade.theirOffers.size==0 && tradeLogic.currentTrade.ourOffers.size==0)
            offerButton.disable()
        else offerButton.enable()
    }

}