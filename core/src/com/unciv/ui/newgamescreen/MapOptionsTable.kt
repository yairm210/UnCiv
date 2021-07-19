package com.unciv.ui.newgamescreen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Array
import com.unciv.logic.MapSaver
import com.unciv.logic.map.MapType
import com.unciv.logic.map.TileMap
import com.unciv.ui.utils.CameraStageBaseScreen
import com.unciv.ui.utils.Popup
import com.unciv.ui.utils.onChange
import com.unciv.ui.utils.toLabel

class MapOptionsTable(private val newGameScreen: NewGameScreen): Table() {

    private val mapParameters = newGameScreen.gameSetupInfo.mapParameters
    private var mapTypeSpecificTable = Table()
    val generatedMapOptionsTable = MapParametersTable(mapParameters)
    private val savedMapOptionsTable = Table()
    lateinit var mapTypeSelectBox: TranslatedSelectBox
    private val mapFileSelectBox = createMapFileSelectBox()

    private val mapFilesSequence = sequence<FileHandleWrapper> {
        yieldAll(MapSaver.getMaps().asSequence().map { FileHandleWrapper(it) })
        for (mod in Gdx.files.local("mods").list()) {
            val mapsFolder = mod.child("maps")
            if (mapsFolder.exists())
                yieldAll(mapsFolder.list().asSequence().map { FileHandleWrapper(it) })
        }
    }

    init {
        //defaults().pad(5f) - each nested table having the same can give 'stairs' effects,
        // better control directly. Besides, the first Labels/Buttons should have 10f to look nice
        addMapTypeSelection()
    }

    private fun addMapTypeSelection() {
        val mapTypes = arrayListOf("Generated")
        if (mapFilesSequence.any()) mapTypes.add(MapType.custom)
        mapTypeSelectBox = TranslatedSelectBox(mapTypes, "Generated", CameraStageBaseScreen.skin)

        savedMapOptionsTable.defaults().pad(5f)
        savedMapOptionsTable.add("{Map file}:".toLabel()).left()
        // because SOME people gotta give the hugest names to their maps
        val columnWidth = newGameScreen.stage.width / (if (newGameScreen.isNarrowerThan4to3()) 1 else 3)
        savedMapOptionsTable.add(mapFileSelectBox)
            .maxWidth((columnWidth - 120f).coerceAtLeast(120f))
            .right().row()


        fun updateOnMapTypeChange() {
            mapTypeSpecificTable.clear()
            if (mapTypeSelectBox.selected.value == MapType.custom) {
                fillMapFileSelectBox()
                mapParameters.type = MapType.custom
                mapParameters.name = mapFileSelectBox.selected.toString()
                mapTypeSpecificTable.add(savedMapOptionsTable)
                newGameScreen.unlockTables()
            } else { // generated map
                mapParameters.name = ""
                mapParameters.type = generatedMapOptionsTable.mapTypeSelectBox.selected.value
                mapTypeSpecificTable.add(generatedMapOptionsTable)
                newGameScreen.unlockTables()
            }
            newGameScreen.gameSetupInfo.gameParameters.godMode = false
            newGameScreen.updateTables()
        }

        // activate once, so when we had a file map before we'll have the right things set for another one
        updateOnMapTypeChange()

        mapTypeSelectBox.onChange { updateOnMapTypeChange() }

        val mapTypeSelectWrapper = Table()  // wrap to center-align Label and SelectBox easier
        mapTypeSelectWrapper.add("{Map Type}:".toLabel()).left().expandX()
        mapTypeSelectWrapper.add(mapTypeSelectBox).right()
        add(mapTypeSelectWrapper).pad(10f).fillX().row()
        add(mapTypeSpecificTable).row()
    }

    private fun createMapFileSelectBox(): SelectBox<FileHandleWrapper> {
        val mapFileSelectBox = SelectBox<FileHandleWrapper>(CameraStageBaseScreen.skin)
        mapFileSelectBox.onChange {
            val mapFile = mapFileSelectBox.selected.fileHandle
            val map: TileMap
            try {
                map = MapSaver.loadMap(mapFile)
            } catch (ex:Exception){
                Popup(newGameScreen).apply {
                    addGoodSizedLabel("Could not load map!")
                    addCloseButton()
                    open()
                }
                return@onChange
            }
            mapParameters.name = mapFile.name()
            newGameScreen.gameSetupInfo.mapFile = mapFile
            newGameScreen.gameSetupInfo.gameParameters.mods = map.mapParameters.mods
            newGameScreen.updateRuleset()
            newGameScreen.updateTables()
        }
        return mapFileSelectBox
    }
    
    private fun fillMapFileSelectBox() {
        if (!mapFileSelectBox.items.isEmpty) return
        val mapFiles = Array<FileHandleWrapper>()
        mapFilesSequence.forEach { mapFiles.add(it) }
        mapFileSelectBox.items = mapFiles
        val selectedItem = mapFiles.firstOrNull { it.fileHandle.name() == mapParameters.name }
        if (selectedItem != null) {
            mapFileSelectBox.selected = selectedItem
            newGameScreen.gameSetupInfo.mapFile = mapFileSelectBox.selected.fileHandle
        } else if (!mapFiles.isEmpty) {
            mapFileSelectBox.selected = mapFiles.first()
            newGameScreen.gameSetupInfo.mapFile = mapFileSelectBox.selected.fileHandle
        }
    }

    // The SelectBox auto displays the text a object.toString(), which on the FileHandle itself includes the folder path.
    //  So we wrap it in another object with a custom toString()
    class FileHandleWrapper(val fileHandle: FileHandle) {
        override fun toString(): String = fileHandle.name()
    }
    
}
