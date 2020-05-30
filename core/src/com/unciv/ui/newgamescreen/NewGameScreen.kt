package com.unciv.ui.newgamescreen

import com.unciv.ui.utils.AutoScrollPane as ScrollPane
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.utils.Array
import com.unciv.UncivGame
import com.unciv.logic.*
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.Scenario
import com.unciv.models.metadata.GameParameters
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.translations.tr
import com.unciv.ui.pickerscreens.PickerScreen
import com.unciv.ui.utils.*
import com.unciv.ui.worldscreen.mainmenu.OnlineMultiplayer
import java.util.*
import kotlin.concurrent.thread


class GameSetupInfo(var gameId:String, var gameParameters: GameParameters, var mapParameters: MapParameters) {
    constructor() : this("", GameParameters(), MapParameters())
    constructor(gameInfo: GameInfo) : this("", gameInfo.gameParameters.clone(), gameInfo.tileMap.mapParameters)
}

class NewGameScreen(previousScreen:CameraStageBaseScreen, _gameSetupInfo: GameSetupInfo?=null): PickerScreen() {

    var gameSetupInfo: GameSetupInfo = _gameSetupInfo ?: GameSetupInfo()
    val ruleset = RulesetCache.getComplexRuleset(gameSetupInfo.gameParameters.mods)
    var loadedScenario: Scenario ?= null
    var useLoadedScenario = false

    init {
        setDefaultCloseAction(previousScreen)
        scrollPane.setScrollingDisabled(true, true)

        val playerPickerTable = PlayerPickerTable(this, gameSetupInfo.gameParameters)
        val newGameOptionsTable = NewGameOptionsTable(this) { desiredCiv: String -> playerPickerTable.update(desiredCiv) }
        topTable.add(ScrollPane(MapOptionsTable(this).apply {name = "MapOptionsTable"})
                .apply { setOverscroll(false, false) })
                .maxHeight(topTable.parent.height).width(stage.width / 3).padTop(20f).top()
        topTable.addSeparatorVertical()
        topTable.add(playerPickerTable.apply {name = "PlayerPickerTable"}).maxHeight(topTable.parent.height).width(stage.width / 3).padTop(20f).top()
        topTable.addSeparatorVertical()
        topTable.add(ScrollPane(newGameOptionsTable.apply {name = "NewGameOptionsTable"})
                .apply { setOverscroll(false, false) })
                .maxHeight(topTable.parent.height).width(stage.width / 3).padTop(20f).top()
        topTable.pack()
        topTable.setFillParent(true)

        if (UncivGame.Current.scenarioDebugSwitch
//                && loadedScenario != null
        ) {
            var loadScenarioCheckBox = getLoadScenarioCheckbox()
            bottomTable.add(loadScenarioCheckBox)
        }

        rightSideButton.enable()
        rightSideButton.setText("Start game!".tr())
        rightSideButton.onClick {
            if (gameSetupInfo.gameParameters.players.none { it.playerType == PlayerType.Human }) {
                val noHumanPlayersPopup = Popup(this)
                noHumanPlayersPopup.addGoodSizedLabel("No human players selected!".tr()).row()
                noHumanPlayersPopup.addCloseButton()
                noHumanPlayersPopup.open()
                return@onClick
            }

            if (gameSetupInfo.gameParameters.isOnlineMultiplayer) {
                for (player in gameSetupInfo.gameParameters.players.filter { it.playerType == PlayerType.Human }) {
                    try {
                        UUID.fromString(IdChecker.checkAndReturnPlayerUuid(player.playerId))
                    } catch (ex: Exception) {
                        val invalidPlayerIdPopup = Popup(this)
                        invalidPlayerIdPopup.addGoodSizedLabel("Invalid player ID!".tr()).row()
                        invalidPlayerIdPopup.addCloseButton()
                        invalidPlayerIdPopup.open()
                        return@onClick
                    }
                }
            }

            Gdx.input.inputProcessor = null // remove input processing - nothing will be clicked!
            rightSideButton.disable()
            rightSideButton.setText("Working...".tr())

            thread(name = "NewGame") {
                // Creating a new game can take a while and we don't want ANRs
                newGameThread()
            }
        }
    }

    private fun newGameThread() {
        try {
            newGame = GameStarter.startNewGame(gameSetupInfo)
            if (gameSetupInfo.gameParameters.isOnlineMultiplayer) {
                newGame!!.isUpToDate = true // So we don't try to download it from dropbox the second after we upload it - the file is not yet ready for loading!
                try {
                    OnlineMultiplayer().tryUploadGame(newGame!!)
                    GameSaver.autoSave(newGame!!) {}

                    //Saved as Multiplayer game to show up in the session browser
                    GameSaver.saveGame(newGame!!, newGame!!.gameId, true)
                    //Save gameId to clipboard because you have to do it anyway.
                    Gdx.app.clipboard.contents = newGame!!.gameId
                    //Popup to notify the User that the gameID got copied to the clipboard
                    Gdx.app.postRunnable { ResponsePopup("gameID copied to clipboard".tr(), UncivGame.Current.worldScreen, 2500) }
                } catch (ex: Exception) {
                    Gdx.app.postRunnable {
                        val cantUploadNewGamePopup = Popup(this)
                        cantUploadNewGamePopup.addGoodSizedLabel("Could not upload game!")
                        cantUploadNewGamePopup.addCloseButton()
                        cantUploadNewGamePopup.open()
                    }
                    newGame = null
                }
            }
        } catch (exception: Exception) {
            val cantMakeThatMapPopup = Popup(this)
            cantMakeThatMapPopup.addGoodSizedLabel("It looks like we can't make a map with the parameters you requested!".tr()).row()
            cantMakeThatMapPopup.addGoodSizedLabel("Maybe you put too many players into too small a map?".tr()).row()
            cantMakeThatMapPopup.addCloseButton()
            cantMakeThatMapPopup.open()
            Gdx.input.inputProcessor = stage
            rightSideButton.enable()
            rightSideButton.setText("Start game!".tr())
        }
        Gdx.graphics.requestRendering()
    }

    fun setNewGameButtonEnabled(bool: Boolean) {
        if (bool) rightSideButton.enable()
        else rightSideButton.disable()
    }

    private fun getLoadScenarioCheckbox(): CheckBox {
        val loadScenarioCheckbox = CheckBox("Load Scenario".tr(), skin)
        var playerPickerTable = topTable.findActor<PlayerPickerTable>("PlayerPickerTable")
        var newGameOptionsTable = topTable.findActor<NewGameOptionsTable>("NewGameOptionsTable")

        loadScenarioCheckbox.isChecked = useLoadedScenario
        loadScenarioCheckbox.onChange {
            if (loadScenarioCheckbox.isChecked) {
                var scenarioName = "MyTestScenario"
                var scenario = MapSaver.loadScenario(scenarioName)

                playerPickerTable.newGameParameters = scenario.gameParameters
                playerPickerTable.update()
            } else {

            }
            useLoadedScenario = loadScenarioCheckbox.isChecked

        }
        return loadScenarioCheckbox
    }
    var newGame: GameInfo? = null

    override fun render(delta: Float) {
        if (newGame != null) {
            game.loadGame(newGame!!)
        }
        super.render(delta)
    }
}

class TranslatedSelectBox(values : Collection<String>, default:String, skin: Skin) : SelectBox<TranslatedSelectBox.TranslatedString>(skin) {
    class TranslatedString(val value: String) {
        val translation = value.tr()
        override fun toString() = translation
    }

    init {
        val array = Array<TranslatedString>()
        values.forEach { array.add(TranslatedString(it)) }
        items = array
        val defaultItem = array.firstOrNull { it.value == default }
        selected = if (defaultItem != null) defaultItem else array.first()
    }
}