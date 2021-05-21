package com.unciv.ui.newgamescreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Slider
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.map.*
import com.unciv.models.translations.tr
import com.unciv.ui.utils.*

/** Table for editing [mapParameters]
 *
 *  This is a separate class, because it should be in use both in the New Game screen and the Map Editor screen
 *
 *  @param isEmptyMapAllowed whether the [MapType.empty] option should be present. Is used by the Map Editor, but should **never** be used with the New Game
 * */
class MapParametersTable(val mapParameters: MapParameters, val isEmptyMapAllowed: Boolean = false):
    Table() {
    lateinit var mapTypeSelectBox: TranslatedSelectBox
    lateinit var worldSizeSelectBox: TranslatedSelectBox
    private var customWorldSizeTable = Table ()
    private var hexagonalSizeTable = Table()
    private var rectangularSizeTable = Table()
    lateinit var noRuinsCheckbox: CheckBox
    lateinit var noNaturalWondersCheckbox: CheckBox
    lateinit var worldWrapCheckbox: CheckBox
    lateinit var customMapSizeRadius: TextField
    lateinit var customMapWidth: TextField
    lateinit var customMapHeight: TextField


    init {
        skin = CameraStageBaseScreen.skin
        defaults().pad(5f)
        addMapShapeSelectBox()
        addMapTypeSelectBox()
        addWorldSizeTable()
        addNoRuinsCheckbox()
        addNoNaturalWondersCheckbox()
        if (UncivGame.Current.settings.showExperimentalWorldWrap) {
            addWorldWrapCheckbox()
        }
        addAdvancedSettings()
    }

    private fun addMapShapeSelectBox() {
        val mapShapes = listOfNotNull(
                MapShape.hexagonal,
                MapShape.rectangular
        )
        val mapShapeSelectBox =
                TranslatedSelectBox(mapShapes, mapParameters.shape, skin)
        mapShapeSelectBox.onChange {
                mapParameters.shape = mapShapeSelectBox.selected.value
                updateWorldSizeTable()
            }

        add ("{Map Shape}:".toLabel()).left()
        add(mapShapeSelectBox).fillX().row()
    }

    private fun addMapTypeSelectBox() {

        val mapTypes = listOfNotNull(
            MapType.default,
            MapType.pangaea,
            MapType.continents,
            MapType.perlin,
            MapType.archipelago,
            if (isEmptyMapAllowed) MapType.empty else null
        )

        mapTypeSelectBox = TranslatedSelectBox(mapTypes, mapParameters.type, skin)

        mapTypeSelectBox.onChange {
                mapParameters.type = mapTypeSelectBox.selected.value

                // If the map won't be generated, these options are irrelevant and are hidden
                noRuinsCheckbox.isVisible = mapParameters.type != MapType.empty
                noNaturalWondersCheckbox.isVisible = mapParameters.type != MapType.empty
            }

        add("{Map Generation Type}:".toLabel()).left()
        add(mapTypeSelectBox).fillX().row()
    }

    private fun addWorldSizeTable() {
        val mapSizes = MapSize.values().map { it.name } + listOf(Constants.custom)
        worldSizeSelectBox = TranslatedSelectBox(mapSizes, mapParameters.mapSize.name, skin)
        worldSizeSelectBox.onChange { updateWorldSizeTable() }

        addHexagonalSizeTable()
        addRectangularSizeTable()

        add("{World Size}:".toLabel()).left()
        add(worldSizeSelectBox).fillX().row()
        add(customWorldSizeTable).colspan(2).grow().row()

        updateWorldSizeTable()
    }

    private fun addHexagonalSizeTable() {
        val defaultRadius = mapParameters.mapSize.radius.toString()
        customMapSizeRadius = TextField(defaultRadius, skin).apply {
            textFieldFilter = TextField.TextFieldFilter.DigitsOnlyFilter()
        }
        customMapSizeRadius.onChange {
            mapParameters.mapSize = MapSizeNew(customMapSizeRadius.text.toIntOrNull() ?: 0 )
        }
        hexagonalSizeTable.add("{Radius}:".toLabel()).grow().left()
        hexagonalSizeTable.add(customMapSizeRadius).right().row()
        hexagonalSizeTable.add("Anything above 40 may work very slowly on Android!".toLabel(Color.RED)
                .apply { wrap=true }).width(prefWidth).colspan(hexagonalSizeTable.columns)
    }

    private fun addRectangularSizeTable() {
        val defaultWidth = mapParameters.mapSize.width.toString()
        customMapWidth = TextField(defaultWidth, skin).apply {
            textFieldFilter = TextField.TextFieldFilter.DigitsOnlyFilter()
        }

        val defaultHeight = mapParameters.mapSize.height.toString()
        customMapHeight = TextField(defaultHeight, skin).apply {
            textFieldFilter = TextField.TextFieldFilter.DigitsOnlyFilter()
        }

        customMapWidth.onChange {
            mapParameters.mapSize = MapSizeNew(customMapWidth.text.toIntOrNull() ?: 0, customMapHeight.text.toIntOrNull() ?: 0)
        }
        customMapHeight.onChange {
            mapParameters.mapSize = MapSizeNew(customMapWidth.text.toIntOrNull() ?: 0, customMapHeight.text.toIntOrNull() ?: 0)
        }

        rectangularSizeTable.defaults().pad(5f)
        rectangularSizeTable.add("{Width}:".toLabel()).grow().left()
        rectangularSizeTable.add(customMapWidth).right().row()
        rectangularSizeTable.add("{Height}:".toLabel()).grow().left()
        rectangularSizeTable.add(customMapHeight).right().row()
        rectangularSizeTable.add("Anything above 80 by 50 may work very slowly on Android!".toLabel(Color.RED)
                .apply { wrap=true }).width(prefWidth).colspan(hexagonalSizeTable.columns)
    }

    private fun updateWorldSizeTable() {
        customWorldSizeTable.clear()

        if (mapParameters.shape == MapShape.hexagonal && worldSizeSelectBox.selected.value == Constants.custom)
            customWorldSizeTable.add(hexagonalSizeTable).grow().row()
        else if (mapParameters.shape == MapShape.rectangular && worldSizeSelectBox.selected.value == Constants.custom)
            customWorldSizeTable.add(rectangularSizeTable).grow().row()
        else
            mapParameters.mapSize = MapSizeNew(worldSizeSelectBox.selected.value)
    }

    private fun addNoRuinsCheckbox() {
        noRuinsCheckbox = CheckBox("No Ancient Ruins".tr(), skin)
        noRuinsCheckbox.isChecked = mapParameters.noRuins
        noRuinsCheckbox.onChange { mapParameters.noRuins = noRuinsCheckbox.isChecked }
        add(noRuinsCheckbox).colspan(2).row()
    }

    private fun addNoNaturalWondersCheckbox() {
        noNaturalWondersCheckbox = CheckBox("No Natural Wonders".tr(), skin)
        noNaturalWondersCheckbox.isChecked = mapParameters.noNaturalWonders
        noNaturalWondersCheckbox.onChange {
            mapParameters.noNaturalWonders = noNaturalWondersCheckbox.isChecked
        }
        add(noNaturalWondersCheckbox).colspan(2).row()
    }

    private fun addWorldWrapCheckbox() {
        worldWrapCheckbox = CheckBox("World Wrap".tr(), skin)
        worldWrapCheckbox.isChecked = mapParameters.worldWrap
        worldWrapCheckbox.onChange {
            mapParameters.worldWrap = worldWrapCheckbox.isChecked
        }
        add(worldWrapCheckbox).colspan(2).row()
        add("World wrap maps are very memory intensive - creating large world wrap maps on Android can lead to crashes!"
                .toLabel(fontSize = 14).apply { wrap=true }).colspan(2).fillX().row()
    }

    private fun addAdvancedSettings() {

        val advancedSettingsTable = getAdvancedSettingsTable()

        val button = "Show advanced settings".toTextButton()

        add(button).colspan(2).row()
        val advancedSettingsCell = add(Table()).colspan(2)
        row()

        button.onClick {
            advancedSettingsTable.isVisible = !advancedSettingsTable.isVisible

            if (advancedSettingsTable.isVisible) {
                button.setText("Hide advanced settings".tr())
                advancedSettingsCell.setActor(advancedSettingsTable)
            } else {
                button.setText("Show advanced settings".tr())
                advancedSettingsCell.setActor(Table())
            }
        }

    }

    private fun getAdvancedSettingsTable(): Table {

        val advancedSettingsTable = Table()
                .apply {isVisible = false; defaults().pad(5f)}

        val sliders = HashMap<Slider, ()->Float>()

        fun addSlider(text:String, getValue:()->Float, min:Float, max:Float, onChange: (value:Float)->Unit): Slider {
            val slider = Slider(min, max, (max - min) / 20, false, skin)
            slider.value = getValue()
            slider.onChange { onChange(slider.value) }
            advancedSettingsTable.add(text.toLabel()).left()
            advancedSettingsTable.add(slider).fillX().row()
            sliders[slider] = getValue
            return slider
        }

        addSlider("Map Height", {mapParameters.elevationExponent}, 0.6f,0.8f)
        {mapParameters.elevationExponent=it}

        addSlider("Temperature extremeness", {mapParameters.temperatureExtremeness}, 0.4f,0.8f)
        { mapParameters.temperatureExtremeness = it}

        addSlider("Resource richness", {mapParameters.resourceRichness},0f,0.5f)
        { mapParameters.resourceRichness=it }

        addSlider("Vegetation richness", {mapParameters.vegetationRichness}, 0f, 1f)
        { mapParameters.vegetationRichness=it }

        addSlider("Rare features richness", {mapParameters.rareFeaturesRichness}, 0f, 0.5f)
        { mapParameters.rareFeaturesRichness = it }

        addSlider("Max Coast extension", {mapParameters.maxCoastExtension.toFloat()}, 0f, 5f)
        { mapParameters.maxCoastExtension =it.toInt() }.apply { stepSize=1f }

        addSlider("Biome areas extension", {mapParameters.tilesPerBiomeArea.toFloat()}, 1f, 15f)
        { mapParameters.tilesPerBiomeArea = it.toInt() }.apply { stepSize=1f }

        addSlider("Water level", {mapParameters.waterThreshold}, -0.1f, 0.1f)
        { mapParameters.waterThreshold = it }

        val resetToDefaultButton = "Reset to default".toTextButton()
        resetToDefaultButton.onClick {
            mapParameters.resetAdvancedSettings()
            for(entry in sliders)
                entry.key.value = entry.value()
        }
        advancedSettingsTable.add(resetToDefaultButton).colspan(2).row()
        return advancedSettingsTable
    }
}
