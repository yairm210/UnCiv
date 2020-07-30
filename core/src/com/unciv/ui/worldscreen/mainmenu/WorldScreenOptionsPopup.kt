package com.unciv.ui.worldscreen.mainmenu

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.utils.Array
import com.unciv.UncivGame
import com.unciv.logic.civilization.PlayerType
import com.unciv.models.UncivSound
import com.unciv.models.translations.TranslationFileWriter
import com.unciv.models.translations.Translations
import com.unciv.models.translations.tr
import com.unciv.ui.utils.*
import com.unciv.ui.worldscreen.WorldScreen
import java.util.*
import kotlin.concurrent.thread
import com.unciv.ui.utils.AutoScrollPane as ScrollPane

class Language(val language:String, val percentComplete:Int){
    override fun toString(): String {
        val spaceSplitLang = language.replace("_"," ")
        return "$spaceSplitLang - $percentComplete%"
    }
}

class WorldScreenOptionsPopup(val worldScreen:WorldScreen) : Popup(worldScreen) {
    var selectedLanguage: String = "English"
    private val settings = worldScreen.game.settings
    private val innerTable = Table(CameraStageBaseScreen.skin)

    init {
        settings.addCompletedTutorialTask("Open the options table")

        rebuildInnerTable()

        val scrollPane = ScrollPane(innerTable, skin)
        scrollPane.setOverscroll(false, false)
        scrollPane.fadeScrollBars = false
        scrollPane.setScrollingDisabled(true, false)
        add(scrollPane).maxHeight(screen.stage.height * 0.6f).row()

        addCloseButton() { worldScreen.enableNextTurnButtonAfterOptions() }

        pack() // Needed to show the background.
        center(worldScreen.game.worldScreen.stage)
    }

    private fun addHeader (text: String) {
        innerTable.add(text.toLabel(fontSize = 24)).colspan(2).padTop(if (innerTable.cells.isEmpty) 0f else 20f).row()
    }

    private fun addYesNoRow (text: String, initialValue: Boolean, updateWorld: Boolean = false, action: ((Boolean) -> Unit)) {
        innerTable.add(text.toLabel())
        val button = YesNoButton(initialValue, skin) {
            action(it)
            settings.save()
            if (updateWorld)
                worldScreen.game.worldScreen.shouldUpdate = true
        }
        innerTable.add(button).row()
    }

    private fun reloadWorldAndOptions() {
        settings.save()
        worldScreen.game.worldScreen = WorldScreen(worldScreen.viewingCiv)
        worldScreen.game.setWorldScreen()
        WorldScreenOptionsPopup(worldScreen.game.worldScreen).open()
    }

    private fun rebuildInnerTable() {
        settings.save()
        innerTable.clear()

        addHeader("Display options")

        addYesNoRow ("Show worked tiles", settings.showWorkedTiles, true) { settings.showWorkedTiles = it }
        addYesNoRow ("Show resources and improvements", settings.showResourcesAndImprovements, true) {
            settings.showResourcesAndImprovements = it
        }
        addYesNoRow ("Show tutorials", settings.showTutorials, true) {settings.showTutorials = it }
        addYesNoRow ("Show minimap", settings.showMinimap, true) { settings.showMinimap = it }
        addYesNoRow ("Show pixel units", settings.showPixelUnits, true) { settings.showPixelUnits = it }
        addYesNoRow ("Show pixel improvements", settings.showPixelImprovements, true) { settings.showPixelImprovements = it }

        addLanguageSelectBox()

        addResolutionSelectBox()

        addTileSetSelectBox()

        addYesNoRow ("Continuous rendering", settings.continuousRendering) {
            settings.continuousRendering = it
            Gdx.graphics.isContinuousRendering = it
        }

        val continuousRenderingDescription = "When disabled, saves battery life but certain animations will be suspended"
        innerTable.add(continuousRenderingDescription.toLabel(fontSize = 14)).colspan(2).padTop(20f).row()

        addHeader("Gameplay options")

        addYesNoRow ("Check for idle units", settings.checkForDueUnits, true) { settings.checkForDueUnits = it }
        addYesNoRow ("Move units with a single tap", settings.singleTapMove) { settings.singleTapMove = it }
        addYesNoRow ("Auto-assign city production", settings.autoAssignCityProduction, true) {
            settings.autoAssignCityProduction = it
            if (it && worldScreen.viewingCiv.isCurrentPlayer() && worldScreen.viewingCiv.playerType == PlayerType.Human) {
                worldScreen.game.gameInfo.currentPlayerCiv.cities.forEach {
                    city -> city.cityConstructions.chooseNextConstruction()
                }
            }
        }
        addYesNoRow ("Auto-build roads", settings.autoBuildingRoads) { settings.autoBuildingRoads = it }
        addYesNoRow ("Order trade offers by amount", settings.orderTradeOffersByAmount) { settings.orderTradeOffersByAmount = it }

        addAutosaveTurnsSelectBox()

        if (UncivGame.Current.replayDebugSwitch)
            addYesNoRow ("Autosave replays", settings.autoSaveReplay) { settings.autoSaveReplay = it }

        // at the moment the notification service only exists on Android
        addNotificationOptions()

        addHeader("Other options")

        addYesNoRow("Extended map editor", settings.extendedMapEditor) { settings.extendedMapEditor = it }
        addYesNoRow("Experimental spectator mode", settings.spectatorMode) { settings.spectatorMode = it }

        addSoundEffectsVolumeSlider()
        addMusicVolumeSlider()
        addTranslationGeneration()
        addSetUserId()

        innerTable.add("Version".toLabel()).pad(10f)
        innerTable.add(worldScreen.game.version.toLabel()).pad(10f).row()
    }

    private fun addSetUserId() {
        val idSetLabel = "".toLabel()
        val takeUserIdFromClipboardButton = "Take user ID from clipboard".toTextButton()
                .onClick {
                    try {
                        val clipboardContents = Gdx.app.clipboard.contents.trim()
                        UUID.fromString(clipboardContents)
                        YesNoPopup("Doing this will reset your current user ID to the clipboard contents - are you sure?",
                                {
                                    settings.userId = clipboardContents
                                    settings.save()
                                    idSetLabel.setFontColor(Color.WHITE).setText("ID successfully set!".tr())
                                }).open(true)
                        idSetLabel.isVisible = true
                    } catch (ex: Exception) {
                        idSetLabel.isVisible = true
                        idSetLabel.setFontColor(Color.RED).setText("Invalid ID!".tr())
                    }
                }
        innerTable.add(takeUserIdFromClipboardButton).pad(5f).colspan(2).row()
        innerTable.add(idSetLabel).colspan(2).row()
    }

    private fun addNotificationOptions() {
        if (Gdx.app.type == Application.ApplicationType.Android) {
            addHeader("Multiplayer options")

            addYesNoRow("Enable out-of-game turn notifications", settings.multiplayerTurnCheckerEnabled)
            { settings.multiplayerTurnCheckerEnabled = it }

            if (settings.multiplayerTurnCheckerEnabled) {
                addMultiplayerTurnCheckerDelayBox()

                addYesNoRow("Show persistent notification for turn notifier service", settings.multiplayerTurnCheckerPersistentNotificationEnabled)
                { settings.multiplayerTurnCheckerPersistentNotificationEnabled = it }
            }
        }
    }

    private fun addTranslationGeneration() {
        if (Gdx.app.type == Application.ApplicationType.Desktop) {
            val generateTranslationsButton = "Generate translation files".toTextButton()
            generateTranslationsButton.onClick {
                val translations = Translations()
                translations.readAllLanguagesTranslation()
                TranslationFileWriter.writeNewTranslationFiles(translations)
                // notify about completion
                generateTranslationsButton.setText("Translation files are generated successfully.".tr())
                generateTranslationsButton.disable()
            }
            innerTable.add(generateTranslationsButton).colspan(2).row()
        }
    }

    private fun addSoundEffectsVolumeSlider() {
        innerTable.add("Sound effects volume".tr())

        val soundEffectsVolumeSlider = Slider(0f, 1.0f, 0.1f, false, skin)
        soundEffectsVolumeSlider.value = settings.soundEffectsVolume
        soundEffectsVolumeSlider.onChange {
            settings.soundEffectsVolume = soundEffectsVolumeSlider.value
            settings.save()
            Sounds.play(UncivSound.Click)
        }
        innerTable.add(soundEffectsVolumeSlider).pad(10f).row()
    }

    private fun addMusicVolumeSlider() {
        val musicLocation = Gdx.files.local(worldScreen.game.musicLocation)
        if (musicLocation.exists()) {
            innerTable.add("Music volume".tr())

            val musicVolumeSlider = Slider(0f, 1.0f, 0.1f, false, skin)
            musicVolumeSlider.value = settings.musicVolume
            musicVolumeSlider.onChange {
                settings.musicVolume = musicVolumeSlider.value
                settings.save()

                val music = worldScreen.game.music
                if (music == null) // restart music, if it was off at the app start
                    thread(name = "Music") { worldScreen.game.startMusic() }

                music?.volume = 0.4f * musicVolumeSlider.value
            }
            innerTable.add(musicVolumeSlider).pad(10f).row()
        } else {
            val downloadMusicButton = "Download music".toTextButton()
            innerTable.add(downloadMusicButton).colspan(2).row()
            val errorTable = Table()
            innerTable.add(errorTable).colspan(2).row()

            downloadMusicButton.onClick {
                downloadMusicButton.disable()
                errorTable.clear()
                errorTable.add("Downloading...".toLabel())

                // So the whole game doesn't get stuck while downloading the file
                thread(name = "Music") {
                    try {
                        val file = DropBox.downloadFile("/Music/thatched-villagers.mp3")
                        musicLocation.write(file, false)
                        Gdx.app.postRunnable {
                            rebuildInnerTable()
                            worldScreen.game.startMusic()
                        }
                    } catch (ex: Exception) {
                        Gdx.app.postRunnable {
                            errorTable.clear()
                            errorTable.add("Could not download music!".toLabel(Color.RED))
                        }
                    }
                }
            }
        }
    }

    private fun addResolutionSelectBox() {
        innerTable.add("Resolution".toLabel())

        val resolutionSelectBox = SelectBox<String>(skin)
        val resolutionArray = Array<String>()
        resolutionArray.addAll("750x500", "900x600", "1050x700", "1200x800", "1500x1000")
        resolutionSelectBox.items = resolutionArray
        resolutionSelectBox.selected = settings.resolution
        innerTable.add(resolutionSelectBox).minWidth(240f).pad(10f).row()

        resolutionSelectBox.onChange {
            settings.resolution = resolutionSelectBox.selected
            reloadWorldAndOptions()
        }
    }

    private fun addTileSetSelectBox() {
        innerTable.add("Tileset".toLabel())

        val tileSetSelectBox = SelectBox<String>(skin)
        val tileSetArray = Array<String>()
        val tileSets = ImageGetter.atlas.regions.filter { it.name.startsWith("TileSets") }
                .map { it.name.split("/")[1] }.distinct()
        for (tileset in tileSets) tileSetArray.add(tileset)
        tileSetSelectBox.items = tileSetArray
        tileSetSelectBox.selected = settings.tileSet
        innerTable.add(tileSetSelectBox).minWidth(240f).pad(10f).row()

        tileSetSelectBox.onChange {
            settings.tileSet = tileSetSelectBox.selected
            reloadWorldAndOptions()
        }
    }

    private fun addAutosaveTurnsSelectBox() {
        innerTable.add("Turns between autosaves".toLabel())

        val autosaveTurnsSelectBox = SelectBox<Int>(skin)
        val autosaveTurnsArray = Array<Int>()
        autosaveTurnsArray.addAll(1, 2, 5, 10)
        autosaveTurnsSelectBox.items = autosaveTurnsArray
        autosaveTurnsSelectBox.selected = settings.turnsBetweenAutosaves

        innerTable.add(autosaveTurnsSelectBox).pad(10f).row()

        autosaveTurnsSelectBox.onChange {
            settings.turnsBetweenAutosaves = autosaveTurnsSelectBox.selected
            settings.save()
        }
    }

    private fun addMultiplayerTurnCheckerDelayBox() {
        innerTable.add("Time between turn checks out-of-game (in minutes)".toLabel())

        val checkDelaySelectBox = SelectBox<Int>(skin)
        val possibleDelaysArray = Array<Int>()
        possibleDelaysArray.addAll(1, 2, 5, 15)
        checkDelaySelectBox.items = possibleDelaysArray
        checkDelaySelectBox.selected = settings.multiplayerTurnCheckerDelayInMinutes

        innerTable.add(checkDelaySelectBox).pad(10f).row()

        checkDelaySelectBox.onChange {
            settings.multiplayerTurnCheckerDelayInMinutes = checkDelaySelectBox.selected
            settings.save()
        }
    }

    private fun addLanguageSelectBox() {
        val languageSelectBox = SelectBox<Language>(skin)
        val languageArray = Array<Language>()
        worldScreen.game.translations.percentCompleteOfLanguages
                .map { Language(it.key, if (it.key == "English") 100 else it.value) }
                .sortedByDescending { it.percentComplete }
                .forEach { languageArray.add(it) }
        if (languageArray.size == 0) return

        innerTable.add("Language".toLabel())
        languageSelectBox.items = languageArray
        val matchingLanguage = languageArray.firstOrNull { it.language == settings.language }
        languageSelectBox.selected = if (matchingLanguage != null) matchingLanguage else languageArray.first()
        innerTable.add(languageSelectBox).minWidth(240f).pad(10f).row()

        languageSelectBox.onChange {
            // Sometimes the "changed" is triggered even when we didn't choose something
            selectedLanguage = languageSelectBox.selected.language

            if (selectedLanguage != settings.language)
                selectLanguage()
        }
    }

    private fun selectLanguage() {
        settings.language = selectedLanguage
        worldScreen.game.translations.tryReadTranslationForCurrentLanguage()
        reloadWorldAndOptions()
    }

}

/*
        This TextButton subclass helps to keep looks and behaviour of our Yes/No
        in one place, but it also helps keeping context for those action lambdas.

        Usage: YesNoButton(someSetting: Boolean, skin) { someSetting = it; sideEffects() }
 */
private fun Boolean.toYesNo(): String = (if (this) "Yes" else "No").tr()
private class YesNoButton(initialValue: Boolean, skin: Skin, action: (Boolean) -> Unit)
        : TextButton (initialValue.toYesNo(), skin ) {

    var value = initialValue
        private set(value) {
            field = value
            setText(value.toYesNo())
        }

    init {
        color = ImageGetter.getBlue()
        onClick {
            value = !value
            action.invoke(value)
        }
    }
}
