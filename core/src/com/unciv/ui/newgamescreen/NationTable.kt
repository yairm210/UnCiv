package com.unciv.ui.newgamescreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.models.ruleset.Nation
import com.unciv.models.ruleset.Ruleset
import com.unciv.ui.utils.CameraStageBaseScreen
import com.unciv.ui.utils.ImageGetter
import com.unciv.ui.utils.surroundWithCircle
import com.unciv.ui.utils.toLabel

// The ruleset also acts as a secondary parameter to determine if this is the right or self side of the player picker
class NationTable(val nation: Nation, width: Float, minHeight: Float, ruleset: Ruleset? = null)
    : Table(CameraStageBaseScreen.skin) {
    val innerTable = Table()

    init {
        background = ImageGetter.getBackground(nation.getInnerColor())
        if (ruleset != null) pad(5f)
        innerTable.pad(5f)
        val totalPadding = 20f // pad*2 + innerTable.pad*2

        innerTable.background = ImageGetter.getBackground(nation.getOuterColor())
        val internalWidth = width - totalPadding

        val titleTable = Table()

        val nationIndicator: Actor
        if(nation.name=="Random") nationIndicator = "?".toLabel(Color.WHITE, 30)
                .apply { this.setAlignment(Align.center) }
                .surroundWithCircle(45f).apply { circle.color = Color.BLACK }
                .surroundWithCircle(50f, false).apply { circle.color = Color.WHITE }
        else nationIndicator = ImageGetter.getNationIndicator(nation, 50f)

        titleTable.add(nationIndicator).pad(10f)

        val titleText = if (ruleset == null || nation.name== Constants.random || nation.name==Constants.spectator)
            nation.name else nation.getLeaderDisplayName()
        val leaderDisplayLabel = titleText.toLabel(nation.getInnerColor(), 24)
        val leaderDisplayNameMaxWidth = internalWidth - 90f // 70 for the nation indicator + 20 extra
        if (leaderDisplayLabel.width > leaderDisplayNameMaxWidth) { // for instance Polish has really long [x] of [y] translations
            leaderDisplayLabel.wrap = true
            titleTable.add(leaderDisplayLabel).width(leaderDisplayNameMaxWidth)
        } else titleTable.add(leaderDisplayLabel)
        innerTable.add(titleTable).row()

        if (ruleset != null) {
            val nationUniqueLabel = nation.getUniqueString(ruleset).toLabel(nation.getInnerColor())
            nationUniqueLabel.wrap = true
            innerTable.add(nationUniqueLabel).width(internalWidth)
        }

        touchable = Touchable.enabled
        add(innerTable).width(width).minHeight(minHeight - totalPadding)
    }
}