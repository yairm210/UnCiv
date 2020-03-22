package com.unciv.ui.worldscreen.mainmenu

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.Array
import com.unciv.UncivGame
import com.unciv.models.UncivSound
import com.unciv.models.translations.tr
import com.unciv.ui.utils.*
import com.unciv.ui.worldscreen.WorldScreen
import kotlin.concurrent.thread

class Language(val language:String, val percentComplete:Int){
    override fun toString(): String {
        val spaceSplitLang = language.replace("_"," ")
        return "$spaceSplitLang- $percentComplete%"
    }
}

class WorldScreenOptionsPopup(val worldScreen:WorldScreen) : Popup(worldScreen){
    var selectedLanguage: String = "English"

    init {
        UncivGame.Current.settings.addCompletedTutorialTask("Open the options table")
        update()
    }


    fun update() {
        val settings = UncivGame.Current.settings
        settings.save()
        clear()

        val innerTable = Table(CameraStageBaseScreen.skin)

        innerTable.add("Display options".toLabel(fontSize = 24)).colspan(2).row()

        innerTable.add("Show worked tiles".toLabel())
        addButton(innerTable, if (settings.showWorkedTiles) "Yes" else "No") {
            settings.showWorkedTiles= !settings.showWorkedTiles
            update()
        }

        innerTable.add("Show resources and improvements".toLabel())
        addButton(innerTable, if (settings.showResourcesAndImprovements) "Yes" else "No") {
            settings.showResourcesAndImprovements = !settings.showResourcesAndImprovements
            update()
        }


        innerTable.add("Show tutorials".toLabel())
        addButton(innerTable, if (settings.showTutorials) "Yes" else "No") {
            settings.showTutorials = !settings.showTutorials
            update()
        }

        innerTable.add("Show minimap".toLabel())
        addButton(innerTable, if (settings.showMinimap) "Yes" else "No") {
            settings.showMinimap = !settings.showMinimap
            update()
        }

        innerTable.add("Show pixel units".toLabel())
        addButton(innerTable, if (settings.showPixelUnits) "Yes" else "No") {
            settings.showPixelUnits = !settings.showPixelUnits
            update()
        }

        innerTable.add("Show pixel improvements".toLabel())
        addButton(innerTable, if (settings.showPixelImprovements) "Yes" else "No") {
            settings.showPixelImprovements = !settings.showPixelImprovements
            update()
        }

        innerTable.add("Order trade offers by amount".toLabel())
        addButton(innerTable, if (settings.orderTradeOffersByAmount) "Yes" else "No") {
            settings.orderTradeOffersByAmount = !settings.orderTradeOffersByAmount
            update()
        }

        addLanguageSelectBox(innerTable)

        addResolutionSelectBox(innerTable)

        addTileSetSelectBox(innerTable)

        innerTable.add("Continuous rendering".toLabel())
        addButton(innerTable, if (settings.continuousRendering) "Yes" else "No") {
            settings.continuousRendering = !settings.continuousRendering
            Gdx.graphics.isContinuousRendering = settings.continuousRendering
            update()
        }

        innerTable.add("Gameplay options".toLabel(fontSize = 24)).colspan(2).padTop(20f).row()


        innerTable.add("Check for idle units".toLabel())
        addButton(innerTable, if (settings.checkForDueUnits) "Yes" else "No") {
            settings.checkForDueUnits = !settings.checkForDueUnits
            update()
        }

        innerTable.add("Move units with a single tap".toLabel())
        addButton(innerTable, if (settings.singleTapMove) "Yes" else "No") {
            settings.singleTapMove = !settings.singleTapMove
            update()
        }

        innerTable.add("Auto-assign city production".toLabel())
        addButton(innerTable, if (settings.autoAssignCityProduction) "Yes" else "No") {
            settings.autoAssignCityProduction = !settings.autoAssignCityProduction
            update()
        }

        innerTable.add("Auto-build roads".toLabel())
        addButton(innerTable, if (settings.autoBuildingRoads) "Yes" else "No") {
            settings.autoBuildingRoads = !settings.autoBuildingRoads
            update()
        }


        innerTable.add("Enable nuclear weapons".toLabel())
        addButton(innerTable, if (settings.nuclearWeaponEnabled) "Yes" else "No") {
            settings.nuclearWeaponEnabled = !settings.nuclearWeaponEnabled
            update()
        }

        addAutosaveTurnsSelectBox(innerTable)

        // at the moment the notification service only exists on Android
        if (Gdx.app.type == Application.ApplicationType.Android) {
            innerTable.add("Multiplayer options".toLabel(fontSize = 24)).colspan(2).padTop(20f).row()

            innerTable.add("Enable out-of-game turn notifications".toLabel())
            addButton(innerTable, if (settings.multiplayerTurnCheckerEnabled) "Yes" else "No") {
                settings.multiplayerTurnCheckerEnabled = !settings.multiplayerTurnCheckerEnabled
                update()
            }
            if (settings.multiplayerTurnCheckerEnabled) {
                addMultiplayerTurnCheckerDelayBox(innerTable)

                innerTable.add("Show persistent notification for turn notifier service".toLabel())
                addButton(innerTable, if (settings.multiplayerTurnCheckerPersistentNotificationEnabled) "Yes" else "No") {
                    settings.multiplayerTurnCheckerPersistentNotificationEnabled = !settings.multiplayerTurnCheckerPersistentNotificationEnabled
                    update()
                }
            }
        }

        innerTable.add("Other options".toLabel(fontSize = 24)).colspan(2).padTop(20f).row()


        addSoundEffectsVolumeSlider(innerTable)
        addMusicVolumeSlider(innerTable)

        innerTable.add("Version".toLabel()).pad(10f)
        innerTable.add(UncivGame.Current.version.toLabel()).pad(10f).row()


        val scrollPane = ScrollPane(innerTable, skin)
        scrollPane.setOverscroll(false, false)
        scrollPane.fadeScrollBars = false
        scrollPane.setScrollingDisabled(true, false)
        add(scrollPane).maxHeight(screen.stage.height * 0.6f).row()

        addCloseButton()

        pack() // Needed to show the background.
        center(UncivGame.Current.worldScreen.stage)
        UncivGame.Current.worldScreen.shouldUpdate = true
    }

    private fun addButton(table: Table, text: String, action: () -> Unit): Cell<TextButton> {
        val button = TextButton(text.tr(), skin).apply { color = ImageGetter.getBlue() }
        button.onClick(action)
        return table.add(button).apply { row() }
    }


    private fun addSoundEffectsVolumeSlider(innerTable: Table) {
        innerTable.add("Sound effects volume".tr())

        val soundEffectsVolumeSlider = Slider(0f, 1.0f, 0.1f, false, skin)
        soundEffectsVolumeSlider.value = UncivGame.Current.settings.soundEffectsVolume
        soundEffectsVolumeSlider.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                UncivGame.Current.settings.soundEffectsVolume = soundEffectsVolumeSlider.value
                UncivGame.Current.settings.save()
                Sounds.play(UncivSound.Click)
            }
        })
        innerTable.add(soundEffectsVolumeSlider).pad(10f).row()
    }

    private fun addMusicVolumeSlider(innerTable: Table) {
        val musicLocation =Gdx.files.local(UncivGame.Current.musicLocation)
        if(musicLocation.exists()) {
            innerTable.add("Music volume".tr())

            val musicVolumeSlider = Slider(0f, 1.0f, 0.1f, false, skin)
            musicVolumeSlider.value = UncivGame.Current.settings.musicVolume
            musicVolumeSlider.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) {
                    UncivGame.Current.settings.musicVolume = musicVolumeSlider.value
                    UncivGame.Current.settings.save()

                    val music = UncivGame.Current.music
                    if (music == null) // restart music, if it was off at the app start
                        thread(name="Music") { UncivGame.Current.startMusic() }

                    music?.volume = 0.4f * musicVolumeSlider.value
                }
            })
            innerTable.add(musicVolumeSlider).pad(10f).row()
        }
        else{
            val downloadMusicButton = TextButton("Download music".tr(),CameraStageBaseScreen.skin)
            innerTable.add(downloadMusicButton).colspan(2).row()
            val errorTable = Table()
            innerTable.add(errorTable).colspan(2).row()

            downloadMusicButton.onClick {
                // So the whole game doesn't get stuck while downloading the file
                thread(name="Music") {
                    try {
                        downloadMusicButton.disable()
                        errorTable.clear()
                        errorTable.add("Downloading...".toLabel())
                        val file = DropBox().downloadFile("/Music/thatched-villagers.mp3")
                        musicLocation.write(file, false)
                        update()
                        UncivGame.Current.startMusic()
                    } catch (ex: Exception) {
                        errorTable.clear()
                        errorTable.add("Could not download music!".toLabel(Color.RED))
                    }
                }
            }
        }
    }

    private fun addResolutionSelectBox(innerTable: Table) {
        innerTable.add("Resolution".toLabel())

        val resolutionSelectBox = SelectBox<String>(skin)
        val resolutionArray = Array<String>()
        resolutionArray.addAll("750x500","900x600", "1050x700", "1200x800", "1500x1000")
        resolutionSelectBox.items = resolutionArray
        resolutionSelectBox.selected = UncivGame.Current.settings.resolution
        innerTable.add(resolutionSelectBox).minWidth(240f).pad(10f).row()

        resolutionSelectBox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                UncivGame.Current.settings.resolution = resolutionSelectBox.selected
                UncivGame.Current.settings.save()
                UncivGame.Current.worldScreen = WorldScreen(worldScreen.viewingCiv)
                UncivGame.Current.setWorldScreen()
                WorldScreenOptionsPopup(UncivGame.Current.worldScreen).open()
            }
        })
    }

    private fun addTileSetSelectBox(innerTable: Table) {
        innerTable.add("Tileset".toLabel())

        val tileSetSelectBox = SelectBox<String>(skin)
        val tileSetArray = Array<String>()
        val tileSets = ImageGetter.atlas.regions.filter { it.name.startsWith("TileSets") }
                .map { it.name.split("/")[1] }.distinct()
        for(tileset in tileSets) tileSetArray.add(tileset)
        tileSetSelectBox.items = tileSetArray
        tileSetSelectBox.selected = UncivGame.Current.settings.tileSet
        innerTable.add(tileSetSelectBox).minWidth(240f).pad(10f).row()

        tileSetSelectBox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                UncivGame.Current.settings.tileSet = tileSetSelectBox.selected
                UncivGame.Current.settings.save()
                UncivGame.Current.worldScreen = WorldScreen(worldScreen.viewingCiv)
                UncivGame.Current.setWorldScreen()
                WorldScreenOptionsPopup(UncivGame.Current.worldScreen).open()
            }
        })
    }

    private fun addAutosaveTurnsSelectBox(innerTable: Table) {
        innerTable.add("Turns between autosaves".toLabel())

        val autosaveTurnsSelectBox = SelectBox<Int>(skin)
        val autosaveTurnsArray = Array<Int>()
        autosaveTurnsArray.addAll(1,2,5,10)
        autosaveTurnsSelectBox.items = autosaveTurnsArray
        autosaveTurnsSelectBox.selected = UncivGame.Current.settings.turnsBetweenAutosaves

        innerTable.add(autosaveTurnsSelectBox).pad(10f).row()

        autosaveTurnsSelectBox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                UncivGame.Current.settings.turnsBetweenAutosaves= autosaveTurnsSelectBox.selected
                UncivGame.Current.settings.save()
                update()
            }
        })
    }

    private fun addMultiplayerTurnCheckerDelayBox(innerTable: Table) {
        innerTable.add("Time between turn checks out-of-game (in minutes)".toLabel())

        val checkDelaySelectBox = SelectBox<Int>(skin)
        val possibleDelaysArray = Array<Int>()
        possibleDelaysArray.addAll(1, 2, 5, 15)
        checkDelaySelectBox.items = possibleDelaysArray
        checkDelaySelectBox.selected = UncivGame.Current.settings.multiplayerTurnCheckerDelayInMinutes

        innerTable.add(checkDelaySelectBox).pad(10f).row()

        checkDelaySelectBox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                UncivGame.Current.settings.multiplayerTurnCheckerDelayInMinutes = checkDelaySelectBox.selected
                UncivGame.Current.settings.save()
                update()
            }
        })
    }

    private fun addLanguageSelectBox(innerTable: Table) {
        val languageSelectBox = SelectBox<Language>(skin)
        val languageArray = Array<Language>()
        UncivGame.Current.translations.percentCompleteOfLanguages
                .map { Language(it.key, if(it.key=="English") 100 else it.value) }
                .sortedByDescending { it.percentComplete }
                .forEach { languageArray.add(it) }
        if(languageArray.size==0) return
        innerTable.add("Language".toLabel())
        languageSelectBox.items = languageArray
        val matchingLanguage = languageArray.firstOrNull { it.language == UncivGame.Current.settings.language }
        languageSelectBox.selected = if (matchingLanguage != null) matchingLanguage else languageArray.first()
        innerTable.add(languageSelectBox).minWidth(240f).pad(10f).row()

        languageSelectBox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                // Sometimes the "changed" is triggered even when we didn't choose something that isn't the
                selectedLanguage = languageSelectBox.selected.language

                if(selectedLanguage!=UncivGame.Current.settings.language )
                    selectLanguage()
            }
        })

    }

    fun selectLanguage(){
        UncivGame.Current.settings.language = selectedLanguage
        UncivGame.Current.settings.save()

        UncivGame.Current.translations.tryReadTranslationForCurrentLanguage()
        CameraStageBaseScreen.resetFonts() // to load chinese characters if necessary
        UncivGame.Current.worldScreen = WorldScreen(worldScreen.viewingCiv)
        UncivGame.Current.setWorldScreen()
        WorldScreenOptionsPopup(UncivGame.Current.worldScreen).open()
    }
}
