package com.unciv.ui.newgamescreen

import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Array
import com.unciv.logic.MapSaver
import com.unciv.logic.map.MapType
import com.unciv.models.translations.tr
import com.unciv.ui.utils.CameraStageBaseScreen
import com.unciv.ui.utils.onChange
import com.unciv.ui.utils.toLabel

class MapOptionsTable(val newGameScreen: NewGameScreen): Table() {

    val mapParameters = newGameScreen.gameSetupInfo.mapParameters
    private var mapTypeSpecificTable = Table()
    private val generatedMapOptionsTable = MapParametersTable(mapParameters)
    private val savedMapOptionsTable = Table()

    init {
        defaults().pad(5f)

        add("Map Options".toLabel(fontSize = 24)).top().padBottom(20f).colspan(2).row()
        addMapTypeSelection()
    }


    private fun addMapTypeSelection() {
        add("{Map Type}:".toLabel())
        val mapTypes = arrayListOf("Generated")
        if (MapSaver.getMaps().isNotEmpty()) mapTypes.add(MapType.custom)
        val mapTypeSelectBox = TranslatedSelectBox(mapTypes, "Generated", CameraStageBaseScreen.skin)

        val mapFileSelectBox = getMapFileSelectBox()
        savedMapOptionsTable.defaults().pad(5f)
        savedMapOptionsTable.add("{Map file}:".toLabel()).left()
        // because SOME people gotta give the hugest names to their maps
        savedMapOptionsTable.add(mapFileSelectBox).maxWidth(newGameScreen.stage.width / 2)
                .right().row()
        val loadScenarioCheckBox = getLoadScenarioCheckbox()
        savedMapOptionsTable.add(loadScenarioCheckBox).colspan(2).row()

        fun updateOnMapTypeChange() {
            mapTypeSpecificTable.clear()
            if (mapTypeSelectBox.selected.value == MapType.custom) {
                mapParameters.type = MapType.custom
                mapParameters.name = mapFileSelectBox.selected
                mapTypeSpecificTable.add(savedMapOptionsTable)
            } else {
                mapParameters.name = ""
                mapParameters.type = generatedMapOptionsTable.mapTypeSelectBox.selected.value
                mapTypeSpecificTable.add(generatedMapOptionsTable)
            }
        }

        // activate once, so when we had a file map before we'll have the right things set for another one
        updateOnMapTypeChange()

        mapTypeSelectBox.onChange { updateOnMapTypeChange() }

        add(mapTypeSelectBox).row()
        add(mapTypeSpecificTable).colspan(2).row()
    }


    private fun getMapFileSelectBox(): SelectBox<String> {
        val mapFileSelectBox = SelectBox<String>(CameraStageBaseScreen.skin)
        val mapNames = Array<String>()
        for (mapName in MapSaver.getMaps()) mapNames.add(mapName)
        mapFileSelectBox.items = mapNames
        if (mapParameters.name in mapNames) mapFileSelectBox.selected = mapParameters.name

        mapFileSelectBox.onChange { mapParameters.name = mapFileSelectBox.selected!! }
        return mapFileSelectBox
    }

    private fun getLoadScenarioCheckbox(): CheckBox {
        val loadScenarioCheckbox = CheckBox("Load Scenario".tr(), CameraStageBaseScreen.skin)
        loadScenarioCheckbox.isChecked = mapParameters.loadScenario
        loadScenarioCheckbox.onChange { mapParameters.loadScenario = loadScenarioCheckbox.isChecked }
        return loadScenarioCheckbox
    }

}