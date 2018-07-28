package com.unciv.ui.worldscreen.optionstable

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.unciv.UnCivGame
import com.unciv.models.gamebasics.GameBasics
import com.unciv.ui.utils.CameraStageBaseScreen
import com.unciv.ui.utils.center
import com.unciv.ui.worldscreen.WorldScreen

class WorldScreenDisplayOptionsTable() : PopupTable(){
    init {
        update()
    }

    fun update() {
        val settings = UnCivGame.Current.settings
        settings.save()
        clear()
        val tileMapHolder = UnCivGame.Current.worldScreen.tileMapHolder

        if (settings.showWorkedTiles) addButton("{Hide} {worked tiles}") { settings.showWorkedTiles = false; update() }
        else addButton("{Show} {worked tiles}") { settings.showWorkedTiles = true; update() }

        if (settings.showResourcesAndImprovements)
            addButton("{Hide} {resources and improvements}") { settings.showResourcesAndImprovements = false; update() }
        else addButton("{Show} {resources and improvements}") { settings.showResourcesAndImprovements = true; update() }

        val languageSelectBox = SelectBox<String>(CameraStageBaseScreen.skin)
        val languageArray = com.badlogic.gdx.utils.Array<String>()
        GameBasics.Translations.getLanguages().forEach { languageArray.add(it) }
        languageSelectBox.setItems(languageArray)
        languageSelectBox.selected = UnCivGame.Current.settings.language
        add(languageSelectBox).pad(10f).row()
        languageSelectBox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                UnCivGame.Current.settings.language = languageSelectBox.selected;
                UnCivGame.Current.settings.save()
                UnCivGame.Current.worldScreen = WorldScreen()
                UnCivGame.Current.setWorldScreen()
                UnCivGame.Current.worldScreen.stage.addActor(WorldScreenDisplayOptionsTable())
            }
        })

        val resolutionSelectBox= SelectBox<String>(CameraStageBaseScreen.skin)
        val resolutionArray = com.badlogic.gdx.utils.Array<String>()
        resolutionArray.addAll("900x600","1050x700","1200x800","1500x1000")
        resolutionSelectBox.setItems(resolutionArray)
        resolutionSelectBox.selected = UnCivGame.Current.settings.resolution
        add(resolutionSelectBox).pad(10f).row()

        resolutionSelectBox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                UnCivGame.Current.settings.resolution = resolutionSelectBox.selected
                UnCivGame.Current.settings.save()
                UnCivGame.Current.worldScreen = WorldScreen()
                UnCivGame.Current.setWorldScreen()
                UnCivGame.Current.worldScreen.stage.addActor(WorldScreenDisplayOptionsTable())
            }
        })

        addButton("Close"){ remove() }

        pack() // Needed to show the background.
        center(UnCivGame.Current.worldScreen.stage)
        tileMapHolder.updateTiles()
    }
}