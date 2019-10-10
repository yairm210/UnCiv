package com.unciv.ui.saves

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.unciv.UnCivGame
import com.unciv.logic.GameSaver
import com.unciv.models.gamebasics.tr
import com.unciv.ui.pickerscreens.PickerScreen
import com.unciv.ui.utils.*
import com.unciv.ui.worldscreen.optionstable.PopupTable
import java.text.SimpleDateFormat
import java.util.*

class LoadGameScreen : PickerScreen() {
    lateinit var selectedSave:String
    val copySavedGameToClipboardButton = TextButton("Copy saved game to clipboard".tr(),skin)
    val saveTable = Table()
    val deleteSaveButton = TextButton("Delete save".tr(), skin)

    init {
        setDefaultCloseAction()

        rightSideButton.setText("Load game".tr())
        updateLoadableGames(false)
        topTable.add(ScrollPane(saveTable)).height(stage.height*2/3)

        val rightSideTable = getRightSideTable()

        topTable.add(rightSideTable)

        rightSideButton.onClick {
            try {
                UnCivGame.Current.loadGame(selectedSave)
            }
            catch (ex:Exception){
                val cantLoadGamePopup = PopupTable(this)
                cantLoadGamePopup.addGoodSizedLabel("It looks like your saved game can't be loaded!").row()
                cantLoadGamePopup.addGoodSizedLabel("If you could copy your game data (\"Copy saved game to clipboard\" - ").row()
                cantLoadGamePopup.addGoodSizedLabel("  paste into an email to yairm210@hotmail.com)").row()
                cantLoadGamePopup.addGoodSizedLabel("I could maybe help you figure out what went wrong, since this isn't supposed to happen!").row()
                cantLoadGamePopup.open()
                ex.printStackTrace()
            }
        }

    }

    private fun getRightSideTable(): Table {
        val rightSideTable = Table()

        val errorLabel = "".toLabel().setFontColor(Color.RED)
        val loadFromClipboardButton = TextButton("Load copied data".tr(), skin)
        loadFromClipboardButton.onClick {
            try {
                val clipboardContentsString = Gdx.app.clipboard.contents.trim()
                val decoded = Gzip.unzip(clipboardContentsString)
                val loadedGame = GameSaver().gameInfoFromString(decoded)
                UnCivGame.Current.loadGame(loadedGame)
            } catch (ex: Exception) {
                errorLabel.setText("Could not load game from clipboard!".tr())
                ex.printStackTrace()
            }
        }
        rightSideTable.add(loadFromClipboardButton).row()
        rightSideTable.add(errorLabel).row()

        deleteSaveButton.onClick {
            GameSaver().deleteSave(selectedSave)
            UnCivGame.Current.setScreen(LoadGameScreen())
        }
        deleteSaveButton.disable()
        rightSideTable.add(deleteSaveButton).row()


        copySavedGameToClipboardButton.disable()
        copySavedGameToClipboardButton.onClick {
            val gameText = GameSaver().getSave(selectedSave).readString()
            val gzippedGameText = Gzip.zip(gameText)
            Gdx.app.clipboard.contents = gzippedGameText
        }
        rightSideTable.add(copySavedGameToClipboardButton).row()


        val showAutosavesCheckbox = CheckBox("Show autosaves".tr(), skin)
        showAutosavesCheckbox.isChecked = false
        showAutosavesCheckbox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                updateLoadableGames(showAutosavesCheckbox.isChecked)
            }
        })
        rightSideTable.add(showAutosavesCheckbox).row()
        return rightSideTable
    }

    private fun updateLoadableGames(showAutosaves:Boolean) {
        saveTable.clear()
        for (save in GameSaver().getSaves().sortedByDescending { GameSaver().getSave(it).lastModified() }) {
            if(save.startsWith("Autosave") && !showAutosaves) continue
            val textButton = TextButton(save, skin)
            textButton.onClick {
                selectedSave = save
                copySavedGameToClipboardButton.enable()
                var textToSet = save

                val savedAt = Date(GameSaver().getSave(save).lastModified())
                textToSet += "\n{Saved at}: ".tr() + SimpleDateFormat("dd-MM-yy HH.mm").format(savedAt)
                try {
                    val game = GameSaver().loadGameByName(save)
                    val playerCivNames = game.civilizations.filter { it.isPlayerCivilization() }.joinToString { it.civName.tr() }
                    textToSet += "\n" + playerCivNames +
                            ", " + game.difficulty.tr() + ", {Turn} ".tr() + game.turns
                } catch (ex: Exception) {
                    textToSet += "\n{Could not load game}!".tr()
                }
                descriptionLabel.setText(textToSet)
                rightSideButton.setText("Load [$save]".tr())
                rightSideButton.enable()
                deleteSaveButton.enable()
                deleteSaveButton.color = Color.RED
            }
            saveTable.add(textButton).pad(5f).row()
        }
    }

}

