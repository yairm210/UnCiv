package com.unciv.ui.newgamescreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.ui.TextField.TextFieldFilter.DigitsOnlyFilter
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.map.*
import com.unciv.ui.utils.*

/** Table for editing [mapParameters]
 *
 *  This is a separate class, because it should be in use both in the New Game screen and the Map Editor screen
 *
 *  @param isEmptyMapAllowed whether the [MapType.empty] option should be present. Is used by the Map Editor, but should **never** be used with the New Game
 * */
class MapParametersTable(
    private val mapParameters: MapParameters,
    private val isEmptyMapAllowed: Boolean = false
) : Table() {
    // These are accessed fom outside the class to read _and_ write values,
    // namely from MapOptionsTable, NewMapScreen and NewGameScreen
    lateinit var mapTypeSelectBox: TranslatedSelectBox
    lateinit var customMapSizeRadius: TextField
    lateinit var customMapWidth: TextField
    lateinit var customMapHeight: TextField

    private lateinit var worldSizeSelectBox: TranslatedSelectBox
    private var customWorldSizeTable = Table ()
    private var hexagonalSizeTable = Table()
    private var rectangularSizeTable = Table()
    private lateinit var noRuinsCheckbox: CheckBox
    private lateinit var noNaturalWondersCheckbox: CheckBox
    private lateinit var worldWrapCheckbox: CheckBox

    // Keep references (in the key) and settings value getters (in the value) of the 'advanced' sliders
    // in a HashMap for reuse later - in the reset to defaults button. Better here as field than as closure.
    // A HashMap indexed on a Widget is problematic, as it does not define its own hashCode and equals
    // overrides nor is a Widget a data class. Seems to work anyway.  
    private val advancedSliders = HashMap<UncivSlider, ()->Float>()

    init {
        skin = CameraStageBaseScreen.skin
        defaults().pad(5f, 10f)
        addMapShapeSelectBox()
        addMapTypeSelectBox()
        addWorldSizeTable()
        addWrappedCheckBoxes()
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
        // MapType is not an enum so we can't simply enumerate. //todo: make it so!
        val mapTypes = listOfNotNull(
            MapType.default,
            MapType.pangaea,
            MapType.continents,
            MapType.fourCorners,
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
            textFieldFilter = DigitsOnlyFilter()
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
            textFieldFilter = DigitsOnlyFilter()
        }

        val defaultHeight = mapParameters.mapSize.height.toString()
        customMapHeight = TextField(defaultHeight, skin).apply {
            textFieldFilter = DigitsOnlyFilter()
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
            .apply { wrap = true }).width(prefWidth).colspan(hexagonalSizeTable.columns)
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

    private fun Table.addNoRuinsCheckbox() {
        noRuinsCheckbox = "No Ancient Ruins".toCheckBox(mapParameters.noRuins) {
            mapParameters.noRuins = it
        }
        add(noRuinsCheckbox).row()
    }

    private fun Table.addNoNaturalWondersCheckbox() {
        noNaturalWondersCheckbox = "No Natural Wonders".toCheckBox(mapParameters.noNaturalWonders) {
            mapParameters.noNaturalWonders = it
        }
        add(noNaturalWondersCheckbox).row()
    }

    private fun Table.addWorldWrapCheckbox() {
        worldWrapCheckbox = "World Wrap".toCheckBox(mapParameters.worldWrap) {
            mapParameters.worldWrap = it
        }
        add(worldWrapCheckbox).row()
    }

    private fun addWrappedCheckBoxes() {
        val showWorldWrap = UncivGame.Current.settings.showExperimentalWorldWrap
        add(Table(skin).apply {
            defaults().left().pad(2.5f)
            addNoRuinsCheckbox()
            addNoNaturalWondersCheckbox()
            if (showWorldWrap) addWorldWrapCheckbox()
        }).colspan(2).center().row()
        if (showWorldWrap)
            add("World wrap maps are very memory intensive - creating large world wrap maps on Android can lead to crashes!"
                .toLabel(fontSize = 14).apply { wrap=true }).colspan(2).fillX().row()
    }

    private fun addAdvancedSettings() {
        val expander = ExpanderTab("Advanced Settings", startsOutOpened = false) {
            addAdvancedControls(it)
        }
        add(expander).pad(0f).padTop(10f).colspan(2).growX().row()
    }

    private fun addAdvancedControls(table: Table) {
        table.defaults().pad(5f)

        val seedTextField = TextField(mapParameters.seed.toString(), skin)
        seedTextField.textFieldFilter = DigitsOnlyFilter()

        // If the field is empty, fallback seed value to 0
        seedTextField.onChange {
            mapParameters.seed = try {
                seedTextField.text.toLong()
            } catch (e: Exception) {
                0L
            }
        }

        table.add("RNG Seed".toLabel()).left()
        table.add(seedTextField).fillX().padBottom(10f).row()

        fun addSlider(text: String, getValue:()->Float, min:Float, max:Float, onChange: (value:Float)->Unit): UncivSlider {
            val slider = UncivSlider(min, max, (max - min) / 20, onChange = onChange)
            slider.value = getValue()
            table.add(text.toLabel()).left()
            table.add(slider).fillX().row()
            advancedSliders[slider] = getValue
            return slider
        }

        addSlider("Map Height", {mapParameters.elevationExponent}, 0.6f,0.8f)
        { mapParameters.elevationExponent = it }

        addSlider("Temperature extremeness", {mapParameters.temperatureExtremeness}, 0.4f,0.8f)
        { mapParameters.temperatureExtremeness = it }

        addSlider("Resource richness", {mapParameters.resourceRichness},0f,0.5f)
        { mapParameters.resourceRichness = it }

        addSlider("Vegetation richness", {mapParameters.vegetationRichness}, 0f, 1f)
        { mapParameters.vegetationRichness = it }

        addSlider("Rare features richness", {mapParameters.rareFeaturesRichness}, 0f, 0.5f)
        { mapParameters.rareFeaturesRichness = it }

        addSlider("Max Coast extension", {mapParameters.maxCoastExtension.toFloat()}, 0f, 5f)
        { mapParameters.maxCoastExtension = it.toInt() }.apply { stepSize = 1f }

        addSlider("Biome areas extension", {mapParameters.tilesPerBiomeArea.toFloat()}, 1f, 15f)
        { mapParameters.tilesPerBiomeArea = it.toInt() }.apply { stepSize = 1f }

        addSlider("Water level", {mapParameters.waterThreshold}, -0.1f, 0.1f)
        { mapParameters.waterThreshold = it }

        val resetToDefaultButton = "Reset to default".toTextButton()
        resetToDefaultButton.onClick {
            mapParameters.resetAdvancedSettings()
            seedTextField.text = mapParameters.seed.toString()
            for (entry in advancedSliders)
                entry.key.value = entry.value()
        }
        table.add(resetToDefaultButton).colspan(2).padTop(10f).row()
    }
}
