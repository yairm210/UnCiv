package com.unciv.ui.saves

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.UncivGame
import com.unciv.logic.GameSaver
import com.unciv.logic.UncivShowableException
import com.unciv.models.translations.tr
import com.unciv.ui.pickerscreens.PickerScreen
import com.unciv.ui.utils.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class LoadReplayScreen(previousScreen: CameraStageBaseScreen) : PickerScreen()  {
    lateinit var selectedReplay:String
    private val replayTable = Table()
    private val deleteReplayButton = "Delete replay".toTextButton()

    init {
        setDefaultCloseAction(previousScreen)

        resetWindowState()

        topTable.add(AutoScrollPane(replayTable)).height(stage.height*2/3)

        val rightSideTable = getRightSideTable()

        topTable.add(rightSideTable)

        rightSideButton.onClick {
            try {
                UncivGame.Current.loadReplay(selectedReplay)
            }
            catch (ex:Exception){
                val cantLoadReplayPopup = Popup(this)
                cantLoadReplayPopup.addGoodSizedLabel("It looks like your replay can't be loaded!").row()
                if (ex is UncivShowableException && ex.localizedMessage != null) {
                    // thrown exceptions are our own tests and can be shown to the user
                    cantLoadReplayPopup.addGoodSizedLabel(ex.localizedMessage).row()
                    cantLoadReplayPopup.addCloseButton()
                    cantLoadReplayPopup.open()
                } else {
                    cantLoadReplayPopup.addGoodSizedLabel("It looks like your replay can't be loaded").row()
                    cantLoadReplayPopup.addCloseButton()
                    cantLoadReplayPopup.open()
                    ex.printStackTrace()
                }
            }
        }

    }

    private fun getRightSideTable(): Table {
        val rightSideTable = Table()
        val errorLabel = "".toLabel(Color.RED)

        deleteReplayButton.onClick {
            GameSaver.deleteReplay(selectedReplay)
            resetWindowState()
        }
        deleteReplayButton.disable()
        rightSideTable.add(deleteReplayButton).row()

        return rightSideTable
    }

    private fun resetWindowState() {
        updateLoadableReplays()
        deleteReplayButton.disable()
        rightSideButton.setText("Load replay".tr())
        rightSideButton.disable()
        descriptionLabel.setText("")
    }

    private fun updateLoadableReplays() {
        replayTable.clear()
        for (replay in GameSaver.getReplays().sortedByDescending { GameSaver.getReplay(it).lastModified() }) {
            val textButton = TextButton(replay, skin)
            textButton.onClick {
                selectedReplay = replay
                var textToSet = replay

                val savedAt = Date(GameSaver.getReplay(replay).lastModified())

                descriptionLabel.setText("Loading...".tr())
                textToSet += "\n{Saved at}: ".tr() + SimpleDateFormat("yyyy-MM-dd HH:mm").format(savedAt)
                thread { // Even loading the replay to get its metadata can take a long time on older phones
                    try {
                        val game = GameSaver.loadReplayByName(replay).finalState
                        val playerCivNames = game.civilizations.filter { it.isPlayerCivilization() }.joinToString { it.civName.tr() }
                        textToSet += "\n" + playerCivNames +
                                ", " + game.difficulty.tr() + ", {Turn} ".tr() + game.turns
                    } catch (ex: Exception) {
                        textToSet += "\n{Could not load replay}!".tr()
                    }

                    Gdx.app.postRunnable {
                        descriptionLabel.setText(textToSet)
                        rightSideButton.setText("Load [$replay]".tr())
                        rightSideButton.enable()
                        deleteReplayButton.enable()
                        deleteReplayButton.color = Color.RED
                    }


                }


            }
            replayTable.add(textButton).pad(5f).row()
        }
    }

}