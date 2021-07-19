package com.unciv.ui.pickerscreens

import com.badlogic.gdx.scenes.scene2d.ui.*
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.ui.utils.*
import com.unciv.ui.utils.AutoScrollPane as ScrollPane

open class PickerScreen(disableScroll: Boolean = false) : CameraStageBaseScreen() {

    internal var closeButton: TextButton = Constants.close.toTextButton()
    protected var descriptionLabel: Label
    private var rightSideGroup = VerticalGroup()
    protected var rightSideButton: TextButton
    private val screenSplit = 0.85f
    private val maxBottomTableHeight = 150f     // about 7 lines of normal text

    /**
     * The table displaying the choices from which to pick (usually).
     * Also the element which most of the screen realestate is devoted to displaying.
     */
    protected var topTable: Table
    protected var bottomTable:Table = Table()
    internal var splitPane: SplitPane
    protected var scrollPane: ScrollPane

    init {
        bottomTable.add(closeButton).pad(10f)

        descriptionLabel = "".toLabel()
        descriptionLabel.wrap = true
        val labelScroll = ScrollPane(descriptionLabel,skin)
        bottomTable.add(labelScroll).pad(5f).fill().expand()

        rightSideButton = "".toTextButton()
        rightSideButton.disable()
        rightSideGroup.addActor(rightSideButton)

        bottomTable.add(rightSideGroup).pad(10f).right()
        bottomTable.height = (stage.height * (1 - screenSplit)).coerceAtMost(maxBottomTableHeight)

        topTable = Table()
        scrollPane = ScrollPane(topTable)

        scrollPane.setScrollingDisabled(disableScroll, disableScroll)
        scrollPane.setSize(stage.width, stage.height - bottomTable.height)

        splitPane = SplitPane(scrollPane, bottomTable, true, skin)
        splitPane.splitAmount = scrollPane.height / stage.height
        splitPane.setFillParent(true)
        stage.addActor(splitPane)
    }

    fun setDefaultCloseAction(previousScreen: CameraStageBaseScreen?=null) {
        val closeAction = {
            if (previousScreen != null) game.setScreen(previousScreen)
            else game.setWorldScreen()
            dispose()
        }
        closeButton.onClick(closeAction)
        onBackButtonClicked(closeAction)
    }

    fun setRightSideButtonEnabled(bool: Boolean) {
        if (bool) rightSideButton.enable()
        else rightSideButton.disable()
    }

    protected fun pick(rightButtonText: String) {
        if (UncivGame.Current.worldScreen.isPlayersTurn) rightSideButton.enable()
        rightSideButton.setText(rightButtonText)
    }

    fun removeRightSideClickListeners(){
        rightSideButton.listeners.filter { it != rightSideButton.clickListener }
                .forEach { rightSideButton.removeListener(it) }
    }
}
