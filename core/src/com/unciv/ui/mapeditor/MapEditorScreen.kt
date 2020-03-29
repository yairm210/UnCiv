package com.unciv.ui.mapeditor

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.MapSaver
import com.unciv.logic.map.TileInfo
import com.unciv.logic.map.TileMap
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.translations.tr
import com.unciv.ui.utils.*

class MapEditorScreen(): CameraStageBaseScreen() {
    lateinit var ruleset: Ruleset
    var mapName = ""

    var tileMap = TileMap()
    lateinit var mapHolder: EditorMapHolder

    lateinit var tileEditorOptions: TileEditorOptionsTable

    private val showHideEditorOptionsButton = TextButton(">", skin)


    constructor(mapNameToLoad: String?): this() {
        var mapToLoad = mapNameToLoad
        if (mapToLoad == null) {
            val existingSaves = MapSaver().getMaps()
            if(existingSaves.isNotEmpty())
                mapToLoad = existingSaves.first()
        }

        if(mapToLoad != null){
            mapName = mapToLoad
            tileMap = MapSaver().loadMap(mapName)
        }

        initialize()
    }

    constructor(map: TileMap): this() {
        mapName = "My first map".tr()
        tileMap = map
        initialize()
    }

    fun initialize() {
        ruleset = RulesetCache.getComplexRuleset(tileMap.createdWithMods)
        tileMap.setTransients(ruleset)
        ImageGetter.updateRuleset(ruleset)

        mapHolder = EditorMapHolder(this, tileMap)
        mapHolder.addTiles()
        stage.addActor(mapHolder)

        tileEditorOptions = TileEditorOptionsTable(this)
        stage.addActor(tileEditorOptions)
        tileEditorOptions.setPosition(stage.width - tileEditorOptions.width, 0f)

        showHideEditorOptionsButton.labelCell.pad(10f)
        showHideEditorOptionsButton.pack()
        showHideEditorOptionsButton.onClick {
            if (showHideEditorOptionsButton.text.toString() == ">") {
                tileEditorOptions.addAction(Actions.moveTo(stage.width, 0f, 0.5f))
                showHideEditorOptionsButton.setText("<")
            } else {
                tileEditorOptions.addAction(Actions.moveTo(stage.width - tileEditorOptions.width, 0f, 0.5f))
                showHideEditorOptionsButton.setText(">")
            }
        }
        showHideEditorOptionsButton.setPosition(stage.width - showHideEditorOptionsButton.width - 10f,
                stage.height - showHideEditorOptionsButton.height - 10f)
        stage.addActor(showHideEditorOptionsButton)


        val optionsMenuButton = TextButton("Menu".tr(), skin)
        optionsMenuButton.onClick {
            if(popups.any { it is MapEditorMenuPopup })
                return@onClick // already open
            MapEditorMenuPopup(this).open(force = true)
        }
        optionsMenuButton.label.setFontSize(24)
        optionsMenuButton.labelCell.pad(20f)
        optionsMenuButton.pack()
        optionsMenuButton.x = 30f
        optionsMenuButton.y = 30f
        stage.addActor(optionsMenuButton)

        mapHolder.addCaptureListener(object: InputListener() {
            var isDragging = false
            var isPainting = false
            var touchDownTime = System.currentTimeMillis()
            var lastDrawnTiles = HashSet<TileInfo>()

            override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                touchDownTime = System.currentTimeMillis()
                return true
            }

            override fun touchDragged(event: InputEvent?, x: Float, y: Float, pointer: Int) {
                if (!isDragging) {
                    isDragging = true
                    val deltaTime = System.currentTimeMillis() - touchDownTime
                    if (deltaTime > 400) {
                        isPainting = true
                        stage.cancelTouchFocusExcept(this, mapHolder)
                    }
                }

                if (isPainting) {

                    for (tileInfo in lastDrawnTiles)
                        mapHolder.tileGroups[tileInfo]!!.hideCircle()
                    lastDrawnTiles.clear()

                    val stageCoords = mapHolder.actor.stageToLocalCoordinates(Vector2(event!!.stageX, event.stageY))
                    val centerTileInfo = mapHolder.getClosestTileTo(stageCoords)
                    if (centerTileInfo != null) {
                        val distance = tileEditorOptions.brushSize - 1

                        for (tileInfo in tileMap.getTilesInDistance(centerTileInfo.position, distance)) {

                            tileEditorOptions.updateTileWhenClicked(tileInfo)

                            tileInfo.setTransients()
                            mapHolder.tileGroups[tileInfo]!!.update()
                            mapHolder.tileGroups[tileInfo]!!.showCircle(Color.WHITE)

                            lastDrawnTiles.add(tileInfo)
                        }
                    }
                }
            }

            override fun touchUp(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
                // Reset the whole map
                if (isPainting) {
                    mapHolder.updateTileGroups()
                    mapHolder.setTransients()
                }

                isDragging = false
                isPainting = false
            }
        })
    }
}

