package com.unciv.ui.pickerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.UncivGame
import com.unciv.logic.map.MapUnit
import com.unciv.models.Tutorial
import com.unciv.models.UncivSound
import com.unciv.models.ruleset.unit.Promotion
import com.unciv.models.translations.tr
import com.unciv.ui.utils.*

class PromotionPickerScreen(val unit: MapUnit, scrollY: Float = 0f) : PickerScreen() {
    private var selectedPromotion: Promotion? = null

    private fun acceptPromotion(promotion: Promotion?) {
        // if user managed to click disabled button, still do nothing
        if (promotion == null) return

        unit.promotions.addPromotion(promotion.name)
        if (unit.promotions.canBePromoted())
            game.setScreen(PromotionPickerScreen(unit, scrollPane.scrollY))
        else
            game.setWorldScreen()
        dispose()
        game.worldScreen.shouldUpdate = true
    }

    init {
        onBackButtonClicked { UncivGame.Current.setWorldScreen() }
        setDefaultCloseAction()

        rightSideButton.setText("Pick promotion".tr())
        rightSideButton.onClick(UncivSound.Promote) {
          acceptPromotion(selectedPromotion)
        }
        val canBePromoted = unit.promotions.canBePromoted()
        val canChangeState = game.worldScreen.canChangeState
        val canPromoteNow = canBePromoted && canChangeState
        if (!canPromoteNow)
            rightSideButton.isEnabled = false

        val availablePromotionsGroup = Table()
        availablePromotionsGroup.defaults().pad(5f)

        val unitType = unit.type
        val promotionsForUnitType = unit.civInfo.gameInfo.ruleSet.unitPromotions.values.filter {
            it.unitTypes.contains(unitType.toString())
                    || unit.promotions.promotions.contains(it.name) }
        val unitAvailablePromotions = unit.promotions.getAvailablePromotions()

        if(canPromoteNow && unit.instanceName == null) {
            val renameButton = "Choose name for [${unit.name}]".toTextButton()
            renameButton.isEnabled = true
            renameButton.onClick {
                RenameUnitPopup(unit, this).open()
            }
            availablePromotionsGroup.add(renameButton)
            availablePromotionsGroup.row()
        }
        for (promotion in promotionsForUnitType) {
            if(promotion.name=="Heal Instantly" && unit.health==100) continue
            val isPromotionAvailable = promotion in unitAvailablePromotions
            val unitHasPromotion = unit.promotions.promotions.contains(promotion.name)

            val selectPromotionButton = Button(skin)
            selectPromotionButton.add(ImageGetter.getPromotionIcon(promotion.name)).size(30f).pad(10f)
            selectPromotionButton.add(promotion.name.toLabel()).pad(10f).padRight(20f)
            selectPromotionButton.isEnabled = true
            selectPromotionButton.onClick {
                val enable = canBePromoted && isPromotionAvailable && !unitHasPromotion && canChangeState
                selectedPromotion = if (enable) promotion else null
                rightSideButton.isEnabled = enable
                rightSideButton.setText(promotion.name.tr())

                descriptionLabel.setText(promotion.getDescription(promotionsForUnitType))
            }

            availablePromotionsGroup.add(selectPromotionButton)

            if (canBePromoted && isPromotionAvailable && canChangeState) {
                val pickNow = "Pick now!".toLabel()
                pickNow.setAlignment(Align.center)
                pickNow.onClick {
                    acceptPromotion(promotion)
                }
                availablePromotionsGroup.add(pickNow).padLeft(10f).fillY()
            }
            else if (unitHasPromotion) selectPromotionButton.color = Color.GREEN
            else selectPromotionButton.color= Color.GRAY

            availablePromotionsGroup.row()

        }
        topTable.add(availablePromotionsGroup)
        splitPane.pack()    // otherwise scrollPane.maxY == 0
        scrollPane.scrollY = scrollY
        scrollPane.updateVisualScroll()

        displayTutorial(Tutorial.Experience)
    }

    override fun resize(width: Int, height: Int) {
        if (stage.viewport.screenWidth != width || stage.viewport.screenHeight != height) {
            game.setScreen(PromotionPickerScreen(unit, scrollPane.scrollY))
        }
    }
}
