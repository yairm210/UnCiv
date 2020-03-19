package com.unciv.ui.trade

import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOffersList
import com.unciv.logic.trade.TradeType
import com.unciv.logic.trade.TradeType.*
import com.unciv.models.translations.tr
import com.unciv.ui.cityscreen.ExpanderTab
import com.unciv.ui.utils.CameraStageBaseScreen
import com.unciv.ui.utils.disable
import com.unciv.ui.utils.onClick
import kotlin.math.min

class OffersListScroll(val onOfferClicked: (TradeOffer) -> Unit) : ScrollPane(null) {
    val table = Table(CameraStageBaseScreen.skin).apply { defaults().pad(5f) }


    private val expanderTabs = HashMap<TradeType, ExpanderTab>()

    fun update(offersToDisplay:TradeOffersList) {
        table.clear()
        expanderTabs.clear()

        for (offertype in values()) {
            val labelName = when(offertype){
                Gold, Gold_Per_Turn, Treaty,Agreement,Introduction -> ""
                Luxury_Resource -> "Luxury resources"
                Strategic_Resource -> "Strategic resources"
                Technology -> "Technologies"
                WarDeclaration -> "Declarations of war"
                City -> "Cities"
            }
            val offersOfType = offersToDisplay.filter { it.type == offertype }
            if (labelName!="" && offersOfType.any()) {
                expanderTabs[offertype] = ExpanderTab(labelName.tr(), CameraStageBaseScreen.skin)
                expanderTabs[offertype]!!.innerTable.defaults().pad(5f)
            }
        }

        for (offerType in values()) {
            val offersOfType = offersToDisplay.filter { it.type == offerType }
                    .sortedWith(compareBy({if (UncivGame.Current.settings.orderTradeOffersByAmount) -it.amount else 0},{if (it.type==City) it.getOfferText() else it.name.tr()}))

            if (expanderTabs.containsKey(offerType)) {
                expanderTabs[offerType]!!.innerTable.clear()
                table.add(expanderTabs[offerType]!!).row()
            }

            for (offer in offersOfType) {
                val tradeButton = TextButton(offer.getOfferText(), CameraStageBaseScreen.skin)
                val amountPerClick =
                        if (offer.type == Gold) 50
                        else 1
                if (offer.amount > 0 && offer.name != Constants.peaceTreaty && offer.name != Constants.researchAgreement) // can't disable peace treaty!
                    tradeButton.onClick {
                        val amountTransferred = min(amountPerClick, offer.amount)
                        onOfferClicked(offer.copy(amount = amountTransferred))
                    }
                else tradeButton.disable()  // for instance we have negative gold


                if (expanderTabs.containsKey(offerType))
                    expanderTabs[offerType]!!.innerTable.add(tradeButton).row()
                else table.add(tradeButton).row()
            }
        }
        actor = table
    }

}