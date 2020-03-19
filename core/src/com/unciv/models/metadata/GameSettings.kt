package com.unciv.models.metadata

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.unciv.logic.GameSaver

class GameSettings {
    var showWorkedTiles: Boolean = false
    var showResourcesAndImprovements: Boolean = true
    var checkForDueUnits: Boolean = true
    var singleTapMove: Boolean = false
    var language: String = "English"
    var resolution: String = "900x600"
    var tutorialsShown = HashSet<String>()
    var tutorialTasksCompleted = HashSet<String>()
    var hasCrashedRecently = false
    var soundEffectsVolume = 0.5f
    var musicVolume = 0.5f
    var turnsBetweenAutosaves = 1
    var tileSet:String = "FantasyHex"
    var showTutorials: Boolean = true
    var autoAssignCityProduction: Boolean = true
    var autoBuildingRoads: Boolean = true
    var showMinimap: Boolean = true
    var showPixelUnits: Boolean = false
    var showPixelImprovements: Boolean = true
    var showPixelResources: Boolean = true
    var nuclearWeaponEnabled = false
    var continuousRendering = false
    var userId = ""
    var multiplayerTurnCheckerEnabled = true
    var multiplayerTurnCheckerPersistentNotificationEnabled = true
    var multiplayerTurnCheckerDelayInMinutes = 5
    var orderTradeOffersByAmount = true

    init {
        // 26 = Android Oreo. Versions below may display permanent icon in notification bar.
        if (Gdx.app.type == Application.ApplicationType.Android && Gdx.app.version < 26) {
            multiplayerTurnCheckerPersistentNotificationEnabled = false
        }
    }

    fun save(){
        GameSaver().setGeneralSettings(this)
    }

    fun addCompletedTutorialTask(tutorialTask:String){
        tutorialTasksCompleted.add(tutorialTask)
        save()
    }
}