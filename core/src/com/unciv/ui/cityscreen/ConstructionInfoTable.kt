package com.unciv.ui.cityscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.city.CityInfo
import com.unciv.logic.city.IConstruction
import com.unciv.logic.city.PerpetualConstruction
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.translations.tr
import com.unciv.ui.utils.ImageGetter
import com.unciv.ui.utils.surroundWithCircle
import com.unciv.ui.utils.toLabel

class ConstructionInfoTable(val city: CityInfo): Table() {
    val selectedConstructionTable = Table()

    init {
        selectedConstructionTable.background = ImageGetter.getBackground(ImageGetter.getBlue().lerp(Color.BLACK, 0.5f))
        add(selectedConstructionTable).pad(2f).fill()
        background = ImageGetter.getBackground(Color.WHITE)
    }

    fun update(selectedConstruction: IConstruction?) {
        selectedConstructionTable.clear()
        selectedConstructionTable.pad(5f)

        if (selectedConstruction == null) {
            isVisible = false
            return
        }
        isVisible = true

        addSelectedConstructionTable(selectedConstruction)

        pack()
    }

    private fun addSelectedConstructionTable(construction: IConstruction) {
        val cityConstructions = city.cityConstructions

        //val selectedConstructionTable = Table()
        selectedConstructionTable.background = ImageGetter.getBackground(ImageGetter.getBlue().lerp(Color.BLACK, 0.5f))
        selectedConstructionTable.pad(10f)

        selectedConstructionTable.add(
                ImageGetter.getConstructionImage(construction.name).surroundWithCircle(50f))
                .pad(5f)


        var buildingText = construction.name.tr()
        val specialConstruction = PerpetualConstruction.perpetualConstructionsMap[construction.name]

        if (specialConstruction == null) buildingText += cityConstructions.getTurnsToConstructionString(construction.name)
        else buildingText += specialConstruction.getProductionTooltip(city)

        selectedConstructionTable.add(buildingText.toLabel()).row()


        val description: String = when (construction) {
            is BaseUnit -> construction.getDescription()
            is Building -> construction.getDescription(city, city.civInfo.gameInfo.ruleSet)
            is PerpetualConstruction -> construction.description.replace("[rate]", "[${construction.getConversionRate(city)}]").tr()
            else -> "" // Should never happen
        }

        val descriptionLabel = description.toLabel()
        descriptionLabel.wrap = true
        selectedConstructionTable.add(descriptionLabel).colspan(2).width(stage.width / 4)
    }

}