package com.unciv

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.GameSaver
import com.unciv.logic.GameStarter
import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MapType
import com.unciv.models.ruleset.RulesetCache
import com.unciv.ui.MultiplayerScreen
import com.unciv.ui.mapeditor.*
import com.unciv.ui.newgamescreen.GameSetupInfo
import com.unciv.ui.newgamescreen.NewGameScreen
import com.unciv.ui.saves.LoadGameScreen
import com.unciv.ui.saves.LoadReplayScreen
import com.unciv.ui.utils.*
import kotlin.concurrent.thread

class MainMenuScreen: CameraStageBaseScreen() {
    private val autosave = "Autosave"
    private val backgroundTable = Table().apply { background=ImageGetter.getBackground(Color.WHITE) }

    private fun getTableBlock(text: String, icon: String, function: () -> Unit): Table {
        val table = Table().pad(15f, 30f, 15f, 30f)
        table.background = ImageGetter.getRoundedEdgeTableBackground(ImageGetter.getBlue())
        table.add(ImageGetter.getImage(icon)).size(50f).padRight(30f)
        table.add(text.toLabel().setFontSize(30)).minWidth(200f)
        table.touchable = Touchable.enabled
        table.onClick(function)
        table.pack()
        return table
    }

    init {
        stage.addActor(backgroundTable)
        backgroundTable.center(stage)

        // If we were in a mod, some of the resource images for the background map we're creating
        // will not exist unless we reset the ruleset and images
        ImageGetter.ruleset = RulesetCache.getBaseRuleset()
        ImageGetter.refreshAtlas()

        thread(name = "ShowMapBackground") {
            val newMap = MapGenerator(RulesetCache.getBaseRuleset())
                    .generateMap(MapParameters().apply { size = MapSize.Small; type = MapType.default })
            Gdx.app.postRunnable { // for GL context
                val mapHolder = EditorMapHolder(MapEditorScreen(), newMap)
                backgroundTable.addAction(Actions.sequence(
                        Actions.fadeOut(0f),
                        Actions.run {
                            mapHolder.apply {
                                addTiles(30f)
                                touchable = Touchable.disabled
                                setScale(1f)
                                center(this@MainMenuScreen.stage)
                                layout()
                            }
                            backgroundTable.add(mapHolder).size(stage.width, stage.height)
                        },
                        Actions.fadeIn(0.3f)
                ))

            }
        }

        val table = Table().apply { defaults().pad(10f) }
        val autosaveGame = GameSaver.getSave(autosave, false)
        if (autosaveGame.exists()) {
            val resumeTable = getTableBlock("Resume", "OtherIcons/Resume") { autoLoadGame() }
            table.add(resumeTable).row()
        }

        val quickstartTable = getTableBlock("Quickstart", "OtherIcons/Quickstart") { quickstartNewGame() }
        table.add(quickstartTable).row()

        val newGameButton = getTableBlock("Start new game", "OtherIcons/New") {
            game.setScreen(NewGameScreen(this))
            dispose()
        }
        table.add(newGameButton).row()

        if (GameSaver.getSaves(false).any()) {
            val loadGameTable = getTableBlock("Load game", "OtherIcons/Load")
                { game.setScreen(LoadGameScreen(this)) }
            table.add(loadGameTable).row()
        }

        val multiplayerTable = getTableBlock("Multiplayer", "OtherIcons/Multiplayer")
            { game.setScreen(MultiplayerScreen(this)) }
        table.add(multiplayerTable).row()

        if (GameSaver.getReplays(false).any() && UncivGame.Current.replayDebugSwitch) {
            val loadReplayTable = getTableBlock("Load replay", "OtherIcons/Load")
                { game.setScreen(LoadReplayScreen(this)) }
            table.add(loadReplayTable).row()
        }
        val mapEditorScreenTable = getTableBlock("Map editor", "OtherIcons/MapEditor")
            { if(stage.actors.none { it is MapEditorMainScreenPopup }) MapEditorMainScreenPopup(this) }
        table.add(mapEditorScreenTable).padBottom(0f)

        // set the same width for all buttons
        table.cells.first().padTop(0f)
        table.pack()
        table.children.filterIsInstance<Table>().forEach {
            it.align(Align.left)
            it.moveBy((it.width - table.width) / 2, 0f)
            it.width = table.width
        }

        table.pack()
        val scroll = ScrollPane(table)
        scroll.setSize(table.width, stage.height * 0.98f)
        scroll.center(stage)
        scroll.setOverscroll(false, false)
        stage.addActor(scroll)
    }


    /** Shows the [Popup] with the map editor initialization options */
    class MapEditorMainScreenPopup(screen: MainMenuScreen):Popup(screen){
        init{
            defaults().pad(10f)

            val tableBackground = ImageGetter.getBackground(colorFromRGB(29, 102, 107))

            val newMapButton = screen.getTableBlock("New map", "OtherIcons/New") {
                screen.game.setScreen(NewMapScreen())
                screen.dispose()
            }
            newMapButton.background = tableBackground
            add(newMapButton).row()

            val loadMapButton = screen.getTableBlock("Load map", "OtherIcons/Load") {
                val loadMapScreen = LoadMapScreen(null)
                loadMapScreen.closeButton.isVisible = true
                loadMapScreen.closeButton.onClick {
                    screen.game.setScreen(MainMenuScreen())
                    loadMapScreen.dispose()
                }
                screen.game.setScreen(loadMapScreen)
                screen.dispose()
            }

            loadMapButton.background = tableBackground
            add(loadMapButton).row()

            if (UncivGame.Current.settings.extendedMapEditor) {
                val loadScenarioButton = screen.getTableBlock("Load scenario", "OtherIcons/Scenario") {
                    val loadScenarioScreen = LoadScenarioScreen(null)
                    loadScenarioScreen.closeButton.isVisible = true
                    loadScenarioScreen.closeButton.onClick {
                        screen.game.setScreen(MainMenuScreen())
                        loadScenarioScreen.dispose()
                    }
                    screen.game.setScreen(loadScenarioScreen)
                    screen.dispose()
                }

                loadScenarioButton.background = tableBackground
                add(loadScenarioButton).row()
            }

            add(screen.getTableBlock("Close", "OtherIcons/Close") { close() }
                    .apply { background=tableBackground })

            open(force = true)
        }
    }


    private fun autoLoadGame() {
        try {
            game.loadGame(autosave)
            dispose()
        }
        catch (outOfMemory:OutOfMemoryError){
            ResponsePopup("Not enough memory on phone to load game!", this)
        }
        catch (ex: Exception) { // silent fail if we can't read the autosave
            ResponsePopup("Cannot resume game!", this)
        }
    }

    private fun quickstartNewGame() {
        val newGame = GameStarter.startNewGame(GameSetupInfo().apply { gameParameters.difficulty = "Chieftain" })
        game.loadGame(newGame)
    }

}