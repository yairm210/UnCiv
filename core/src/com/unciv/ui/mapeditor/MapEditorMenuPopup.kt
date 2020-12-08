package com.unciv.ui.mapeditor

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.utils.Json
import com.unciv.Constants
import com.unciv.MainMenuScreen
import com.unciv.UncivGame
import com.unciv.logic.MapSaver
import com.unciv.logic.map.MapType
import com.unciv.logic.map.RoadStatus
import com.unciv.logic.map.ScenarioMap
import com.unciv.logic.map.TileMap
import com.unciv.models.translations.tr
import com.unciv.models.metadata.Player
import com.unciv.ui.saves.Gzip
import com.unciv.ui.utils.*
import com.unciv.ui.worldscreen.mainmenu.DropBox
import kotlin.concurrent.thread

class MapEditorMenuPopup(var mapEditorScreen: MapEditorScreen): Popup(mapEditorScreen){
    private val mapNameEditor: TextField = TextField(mapEditorScreen.mapName, skin)

    init {
        mapNameEditor.textFieldFilter = TextField.TextFieldFilter { _, char -> char != '\\' && char != '/' }
        add(mapNameEditor).fillX().row()
        mapNameEditor.selectAll()
        mapNameEditor.maxLength = 240       // A few under max for most filesystems
        mapEditorScreen.stage.keyboardFocus = mapNameEditor

        addNewMapButton()
        addClearCurrentMapButton()
        addSaveMapButton()
        addCopyMapAsTextButton()
        addLoadMapButton()
//        addUploadMapButton()
        if (UncivGame.Current.settings.extendedMapEditor) addScenarioButton()
        addExitMapEditorButton()
        addCloseOptionsButton()
    }

    private fun Popup.addNewMapButton() {
        val newMapButton = "New map".toTextButton()
        newMapButton.onClick {
            UncivGame.Current.setScreen(NewMapScreen())
        }
        add(newMapButton).row()
    }

    private fun Popup.addClearCurrentMapButton() {
        val clearCurrentMapButton = "Clear current map".toTextButton()

        clearCurrentMapButton.onClick {
            YesNoPopup("Are you sure you want to clear the entire map?", {
                for (tileGroup in mapEditorScreen.mapHolder.tileGroups.values) {
                    val tile = tileGroup.tileInfo
                    tile.baseTerrain = Constants.ocean
                    tile.terrainFeature = null
                    tile.naturalWonder = null
                    tile.hasBottomRiver = false
                    tile.hasBottomLeftRiver = false
                    tile.hasBottomRightRiver = false
                    tile.resource = null
                    tile.improvement = null
                    tile.improvementInProgress = null
                    tile.roadStatus = RoadStatus.None

                    tile.setTransients()

                    tileGroup.update()
                }
            }, mapEditorScreen).open(true)
        }
        add(clearCurrentMapButton).row()
    }

    private fun Popup.addSaveMapButton() {
        val saveMapButton = "Save map".toTextButton()
        saveMapButton.onClick {
            mapEditorScreen.tileMap.mapParameters.name = mapEditorScreen.mapName
            mapEditorScreen.tileMap.mapParameters.type = MapType.custom
            thread(name = "SaveMap") { // this works for both scenarios and non-scenarios
                try {
                    if(mapEditorScreen.hasScenario()) {
                        mapEditorScreen.tileMap.mapParameters.type = MapType.scenarioMap
                        mapEditorScreen.scenarioMap = ScenarioMap(mapEditorScreen.tileMap, mapEditorScreen.gameSetupInfo.gameParameters)
                        mapEditorScreen.scenarioMap!!.gameParameters.godMode = true // so we can edit this scenario when loading from the map
                        mapEditorScreen.scenarioName = mapNameEditor.text
                        MapSaver.saveScenario(mapNameEditor.text, mapEditorScreen.scenarioMap!!)
                    }
                    else {
                        MapSaver.saveMap(mapEditorScreen.mapName, mapEditorScreen.tileMap)
                    }
                    close()
                    Gdx.app.postRunnable {
                        ToastPopup("Map saved", mapEditorScreen) // todo - add this text to translations
                    }
                } catch (ex: Exception) {
                    ex.printStackTrace()
                    Gdx.app.postRunnable {
                        val cantLoadGamePopup = Popup(mapEditorScreen)
                        cantLoadGamePopup.addGoodSizedLabel("It looks like your map can't be saved!").row()
                        cantLoadGamePopup.addCloseButton()
                        cantLoadGamePopup.open(force = true)
                    }
                }
            }
        }
        saveMapButton.isEnabled = mapNameEditor.text.isNotEmpty()
        add(saveMapButton).row()
        mapNameEditor.addListener {
            mapEditorScreen.mapName = mapNameEditor.text
            saveMapButton.isEnabled = mapNameEditor.text.isNotEmpty()
            true
        }
    }

    private fun Popup.addCopyMapAsTextButton() {
        val copyMapAsTextButton = "Copy to clipboard".toTextButton()
        copyMapAsTextButton.onClick {
            val json = Json().toJson(mapEditorScreen.tileMap)
            val base64Gzip = Gzip.zip(json)
            Gdx.app.clipboard.contents = base64Gzip
        }
        add(copyMapAsTextButton).row()
    }

    private fun Popup.addLoadMapButton() {
        val loadMapButton = "Load map".toTextButton()
        loadMapButton.onClick {
            UncivGame.Current.setScreen(LoadMapScreen(mapEditorScreen.tileMap))
        }
        add(loadMapButton).row()
    }

    private fun Popup.addUploadMapButton() {
        val uploadMapButton = "Upload map".toTextButton()
        uploadMapButton.onClick {
            thread(name = "MapUpload") {
                try {
                    val gzippedMap = Gzip.zip(Json().toJson(mapEditorScreen.tileMap))
                    DropBox.uploadFile("/Maps/" + mapEditorScreen.mapName, gzippedMap)

                    remove()
                    Gdx.app.postRunnable {
                        val uploadedSuccessfully = Popup(screen)
                        uploadedSuccessfully.addGoodSizedLabel("Map uploaded successfully!").row()
                        uploadedSuccessfully.addCloseButton()
                        uploadedSuccessfully.open()
                    }
                } catch (ex: Exception) {
                    remove()
                    Gdx.app.postRunnable {
                        val couldNotUpload = Popup(screen)
                        couldNotUpload.addGoodSizedLabel("Could not upload map!").row()
                        couldNotUpload.addCloseButton()
                        couldNotUpload.open()
                    }
                }
            }
        }
        add(uploadMapButton).row()
    }

    private fun Popup.addScenarioButton() {
        var scenarioButton = "".toTextButton()
        if (mapEditorScreen.hasScenario()) {
            scenarioButton.setText("Edit scenario parameters".tr())
        } else {
            scenarioButton.setText("Create scenario map".tr())
            // for newly created scenarios read players from tileMap
            val players = getPlayersFromMap(mapEditorScreen.tileMap)
            mapEditorScreen.gameSetupInfo.gameParameters.players = players
        }
        add(scenarioButton).row()
        scenarioButton.onClick {
            close()
            UncivGame.Current.setScreen(GameParametersScreen(mapEditorScreen).apply {
                playerPickerTable.noRandom = true
            })
        }
    }


    private fun Popup.addExitMapEditorButton() {
        val exitMapEditorButton = "Exit map editor".toTextButton()
        add(exitMapEditorButton).row()
        exitMapEditorButton.onClick { mapEditorScreen.game.setScreen(MainMenuScreen()); mapEditorScreen.dispose() }
    }

    private fun Popup.addCloseOptionsButton() {
        val closeOptionsButton = Constants.close.toTextButton()
        closeOptionsButton.onClick { close() }
        add(closeOptionsButton).row()
    }

    private fun getPlayersFromMap(tileMap: TileMap): ArrayList<Player> {
        val tilesWithStartingLocations = tileMap.values
                .filter { it.improvement != null && it.improvement!!.startsWith("StartingLocation ") }
        var players = ArrayList<Player>()
        for (tile in tilesWithStartingLocations) {
            players.add(Player().apply{
                chosenCiv = tile.improvement!!.removePrefix("StartingLocation ")
            })
        }
        return players
    }

}
