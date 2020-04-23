package com.unciv

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.TiledDrawable
import com.badlogic.gdx.utils.Align
import com.unciv.logic.GameSaver
import com.unciv.logic.GameStarter
import com.unciv.logic.map.MapGenerator
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MapType
import com.unciv.models.metadata.GameParameters
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.translations.tr
import com.unciv.ui.MultiplayerScreen
import com.unciv.ui.map.TileGroupMap
import com.unciv.ui.mapeditor.EditorMapHolder
import com.unciv.ui.mapeditor.LoadMapScreen
import com.unciv.ui.mapeditor.MapEditorScreen
import com.unciv.ui.mapeditor.NewMapScreen
import com.unciv.ui.newgamescreen.NewGameScreen
import com.unciv.ui.saves.LoadGameScreen
import com.unciv.ui.utils.*
import com.unciv.ui.worldscreen.WorldMapHolder
import kotlin.concurrent.thread
import kotlin.concurrent.timer

class MenuScreen: CameraStageBaseScreen() {
    val autosave = "Autosave"
    val backgroundTable = Table().apply { background=ImageGetter.getBackground(Color.WHITE) }

    private fun getTableBlock(text: String, icon: String, function: () -> Unit): Table {
        val table = Table().pad(30f)
        table.background = ImageGetter.getBackground(colorFromRGB(11, 135, 133))
        table.add(ImageGetter.getImage(icon)).size(50f).padRight(30f)
        table.add(text.toLabel().setFontSize(30).apply { /* setAlignment(Align.center) */ }).width(200f)
        table.touchable= Touchable.enabled
        table.onClick(function)
        table.pack()
        return table
    }

    init {
        stage.addActor(backgroundTable)
        backgroundTable.center(stage)

        thread(name="ShowMapBackground") {
            val newMap = MapGenerator(RulesetCache.getBaseRuleset())
                    .generateMap(MapParameters().apply { size = MapSize.Small; type=MapType.default })
            Gdx.app.postRunnable { // for GL context
                val mapHolder = EditorMapHolder(MapEditorScreen(), newMap)
                backgroundTable.addAction(Actions.sequence(
                        Actions.fadeOut(0f),
                        Actions.run {
                            mapHolder.apply {
                                addTiles(30f)
                                touchable = Touchable.disabled
                                setScale(1f)
                                center(this@MenuScreen.stage)
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
            val resumeTable = getTableBlock("Resume","OtherIcons/Resume") { autoLoadGame() }
            table.add(resumeTable).row()
        }

        val quickstartTable = getTableBlock("Quickstart","OtherIcons/Quickstart") { startNewGame() }
        table.add(quickstartTable).row()

        val newGameButton = getTableBlock("Start new game","OtherIcons/New") {
            game.setScreen(NewGameScreen(this))
        }
        table.add(newGameButton).row()

        if (GameSaver.getSaves(false).any()) {
            val loadGameTable = getTableBlock("Load game","OtherIcons/Load") { game.setScreen(LoadGameScreen(this)) }
            table.add(loadGameTable).row()
        }

        val multiplayerTable = getTableBlock("Multiplayer","OtherIcons/Multiplayer") { game.setScreen(MultiplayerScreen(this)) }
        table.add(multiplayerTable).row()

        val mapEditorScreenTable = getTableBlock("Map editor","OtherIcons/MapEditor") { openMapEditorPopup() }
        table.add(mapEditorScreenTable)


        table.pack()
        val scroll = ScrollPane(table)
        scroll.setSize(table.width, stage.height * 0.8f)
        scroll.center(stage)
        scroll.setOverscroll(false, false)
        stage.addActor(scroll)
    }


    /** Shows the [Popup] with the map editor initialization options */
    private fun openMapEditorPopup() {

        val mapEditorPopup = Popup(this)
        mapEditorPopup.defaults().pad(10f)

        val tableBackground = ImageGetter.getBackground(colorFromRGB(29, 102, 107))

        val newMapButton = getTableBlock("New map", "OtherIcons/New") { game.setScreen(NewMapScreen()) }
        newMapButton.background = tableBackground
        mapEditorPopup.add(newMapButton).row()

        val loadMapButton = getTableBlock("Load map", "OtherIcons/Load") {
            val loadMapScreen = LoadMapScreen(null)
            loadMapScreen.closeButton.isVisible = true
            loadMapScreen.closeButton.onClick {
                game.setScreen(MenuScreen())
                loadMapScreen.dispose()
            }
            game.setScreen(loadMapScreen)
        }
        loadMapButton.background = tableBackground
        mapEditorPopup.add(loadMapButton).row()

        mapEditorPopup.add(getTableBlock("Close", "OtherIcons/Close") { mapEditorPopup.close() }
                .apply { background=tableBackground })

        mapEditorPopup.open(force = true)
    }

    fun autoLoadGame() {
        try {
            game.loadGame(autosave)
        } catch (ex: Exception) { // silent fail if we can't read the autosave
            ResponsePopup("Cannot resume game!", this)
        }
    }

    fun startNewGame() {
        val newGame = GameStarter.startNewGame(GameParameters().apply { difficulty = "Chieftain" }, MapParameters())
        game.loadGame(newGame)
    }

}