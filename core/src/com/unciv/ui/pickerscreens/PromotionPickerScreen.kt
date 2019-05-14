package com.unciv.ui.pickerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.VerticalGroup
import com.unciv.UnCivGame
import com.unciv.logic.map.MapUnit
import com.unciv.models.gamebasics.GameBasics
import com.unciv.models.gamebasics.Translations
import com.unciv.models.gamebasics.tr
import com.unciv.models.gamebasics.unit.Promotion
import com.unciv.ui.utils.*

class PromotionPickerScreen(mapUnit: MapUnit) : PickerScreen() {
    private var selectedPromotion: Promotion? = null


    init {
        onBackButtonClicked { UnCivGame.Current.setWorldScreen() }
        setDefaultCloseAction()

        fun accept(promotion: Promotion?) {
            mapUnit.promotions.addPromotion(promotion!!.name)
            if(mapUnit.promotions.canBePromoted()) game.screen = PromotionPickerScreen(mapUnit)
            else game.setWorldScreen()
            dispose()
        }

        rightSideButton.setText("Pick promotion".tr())
        rightSideButton.onClick("promote") {
          accept(selectedPromotion)
        }

        val availablePromotionsGroup = Table()

        val unitType = mapUnit.type
        val promotionsForUnitType = GameBasics.UnitPromotions.values.filter { it.unitTypes.contains(unitType.toString()) }
        val unitAvailablePromotions = mapUnit.promotions.getAvailablePromotions()

        for (promotion in promotionsForUnitType) {
            if(promotion.name=="Heal Instantly" && mapUnit.health==100) continue
            val isPromotionAvailable = promotion in unitAvailablePromotions
            val unitHasPromotion = mapUnit.promotions.promotions.contains(promotion.name)
            val promotionButton = Button(skin)

            if(!isPromotionAvailable) promotionButton.disable()
            promotionButton.add(ImageGetter.getPromotionIcon(promotion.name)).size(30f).pad(10f)
            promotionButton.add(promotion.name.toLabel()
                    .setFontColor(Color.WHITE)).pad(10f)
            if(unitHasPromotion) promotionButton.color = Color.GREEN

            promotionButton.onClick {
                accept(promotion)
            }

            availablePromotionsGroup.add(promotionButton)


            val helpButton = Button(skin)
            helpButton.add(Label("?", skin)).pad(15f)

            helpButton.onClick {
                selectedPromotion = promotion
                rightSideButton.setText(promotion.name.tr())
                if(isPromotionAvailable && !unitHasPromotion) rightSideButton.enable()
                else rightSideButton.disable()

                // we translate it before it goes in to get uniques like "vs units in rough terrain" and after to get "vs city
                var descriptionText = Translations.translateBonusOrPenalty(promotion.effect.tr())

                if(promotion.prerequisites.isNotEmpty()) {
                    val prerequisitesString:ArrayList<String> = arrayListOf()
                   for (i in promotion.prerequisites.filter { promotionsForUnitType.any { promotion ->  promotion.name==it } }){
                       prerequisitesString.add(i.tr())
                   }
                    descriptionText +="\n{Requires}: ".tr()+prerequisitesString.joinToString(" OR ".tr())
                }
                descriptionLabel.setText(descriptionText)
            }
            availablePromotionsGroup.add(helpButton).pad(5f).row()
        }
        topTable.add(availablePromotionsGroup)
    }
}