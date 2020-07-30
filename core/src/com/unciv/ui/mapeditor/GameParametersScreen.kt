package com.unciv.ui.mapeditor

import com.unciv.UncivGame
import com.unciv.logic.map.Scenario
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.ui.newgamescreen.GameOptionsTable
import com.unciv.ui.newgamescreen.GameSetupInfo
import com.unciv.ui.newgamescreen.PlayerPickerTable
import com.unciv.ui.newgamescreen.IPreviousScreen
import com.unciv.ui.pickerscreens.PickerScreen
import com.unciv.ui.utils.*
/**
 * This [Screen] is used for editing game parameters when scenario is edited/created in map editor.
 * Implements [PreviousScreenInterface] for compatibility with [PlayerPickerTable], [GameOptionsTable]
 * Uses [PlayerPickerTable] and [GameOptionsTable] to change local [gameSetupInfo]. Upon confirmation
 * updates [mapEditorScreen] and switches to it.
 * @param [mapEditorScreen] previous screen from map editor.
 */
class GameParametersScreen(var mapEditorScreen: MapEditorScreen): IPreviousScreen, PickerScreen() {
    override var gameSetupInfo = mapEditorScreen.gameSetupInfo.clone()  // using clone() to be able to cancel changes
    override var ruleset = RulesetCache.getComplexRuleset(gameSetupInfo.gameParameters)
    var playerPickerTable = PlayerPickerTable(this, gameSetupInfo.gameParameters)
    var gameOptionsTable = GameOptionsTable(this) { desiredCiv: String -> playerPickerTable.update(desiredCiv) }


    init {
        setDefaultCloseAction(mapEditorScreen)
        scrollPane.setScrollingDisabled(true, true)

        topTable.add(AutoScrollPane(gameOptionsTable).apply { setScrollingDisabled(true, false) })
                .maxHeight(topTable.parent.height).width(stage.width / 2).padTop(20f).top()
        topTable.addSeparatorVertical()
        topTable.add(playerPickerTable).maxHeight(topTable.parent.height).width(stage.width / 2).padTop(20f).top()
        rightSideButton.setText("OK")
        rightSideButton.onClick {
            mapEditorScreen.gameSetupInfo = gameSetupInfo
            mapEditorScreen.scenario = Scenario(mapEditorScreen.tileMap, mapEditorScreen.gameSetupInfo.gameParameters)
            mapEditorScreen.ruleset.clear()
            mapEditorScreen.ruleset.add(ruleset)
            mapEditorScreen.tileEditorOptions.update()
            mapEditorScreen.mapHolder.updateTileGroups()
            UncivGame.Current.setScreen(mapEditorScreen)
            dispose()
        }
    }
}

