package com.unciv.ui.worldscreen.mainmenu

import com.badlogic.gdx.Gdx
import com.unciv.Constants
import com.unciv.MainMenuScreen
import com.unciv.models.translations.tr
import com.unciv.ui.CivilopediaScreen
import com.unciv.ui.newgamescreen.GameSetupInfo
import com.unciv.ui.newgamescreen.NewGameScreen
import com.unciv.ui.saves.LoadGameScreen
import com.unciv.ui.saves.SaveGameScreen
import com.unciv.ui.utils.Popup
import com.unciv.ui.utils.addSeparator
import com.unciv.ui.victoryscreen.VictoryScreen
import com.unciv.ui.worldscreen.WorldScreen

class WorldScreenMenuPopup(val worldScreen: WorldScreen) : Popup(worldScreen) {

    init {
        val width = 200f
        val height = 30f
        addSquareButton("Main menu".tr()){
            worldScreen.game.setScreen(MainMenuScreen())
            close()
        }.size(width,height)
        addSeparator()

        addSquareButton("Civilopedia".tr()){
            worldScreen.game.setScreen(CivilopediaScreen(worldScreen.gameInfo.ruleSet))
            close()
        }.size(width,height)
        addSeparator()

        addSquareButton("Save game".tr()){
            worldScreen.game.setScreen(SaveGameScreen())
            close()
        }.size(width,height)
        addSeparator()

        addSquareButton("Load game".tr()){
            worldScreen.game.setScreen(LoadGameScreen(worldScreen))
            close()
        }.size(width,height)
        addSeparator()

        addSquareButton("Start new game".tr()){
            worldScreen.game.setScreen(NewGameScreen(worldScreen, GameSetupInfo(worldScreen.gameInfo)))
            close()
        }.size(width,height)
        addSeparator()

        addSquareButton("Victory status".tr()){
            worldScreen.game.setScreen(VictoryScreen(worldScreen))
            close()
        }.size(width,height)
        addSeparator()

        addSquareButton("Options".tr()){
            OptionsPopup(worldScreen).open(force = true)
            close()
        }.size(width,height)
        addSeparator()

        addSquareButton("Community"){
            WorldScreenCommunityPopup(worldScreen).open(force = true)
            close()
        }.size(width,height)
        addSeparator()

        addSquareButton(Constants.close){
            close()
        }.size(width,height)
    }



}

class WorldScreenCommunityPopup(val worldScreen: WorldScreen) : Popup(worldScreen) {
    init {
        addButton("Discord") {
            Gdx.net.openURI("https://discord.gg/bjrB4Xw")
            close()
        }

        addButton("Github") {
            Gdx.net.openURI("https://github.com/yairm210/UnCiv")
            close()
        }

        addButton("Reddit") {
            Gdx.net.openURI("https://www.reddit.com/r/Unciv/")
            close()
        }

        addCloseButton()
    }
}
