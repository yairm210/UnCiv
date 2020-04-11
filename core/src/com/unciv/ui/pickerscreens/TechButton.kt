package com.unciv.ui.pickerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.civilization.TechManager
import com.unciv.ui.utils.CameraStageBaseScreen
import com.unciv.ui.utils.ImageGetter
import com.unciv.ui.utils.surroundWithCircle
import com.unciv.ui.utils.toLabel

class TechButton(techName:String, private val techManager: TechManager, isWorldScreen: Boolean = true) : Table(CameraStageBaseScreen.skin) {
    val text= "".toLabel().apply { setAlignment(Align.center) }

    init {
        touchable = Touchable.enabled
        defaults().pad(10f)
        background = ImageGetter.getRoundedEdgeTableBackground()
        if (ImageGetter.techIconExists(techName))
            add(ImageGetter.getTechIconGroup(techName, 60f))

        val rightSide = Table()
        val techCost = techManager.costOfTech(techName)
        val remainingTech = techManager.remainingScienceToTech(techName)
        if (techCost != remainingTech) {
            val percentComplete = (techCost - remainingTech) / techCost.toFloat()
            add(ImageGetter.getProgressBarVertical(2f, 50f, percentComplete, Color.BLUE, Color.WHITE))
        } else add().width(2f)

        if (isWorldScreen) rightSide.add(text).padBottom(5f).row()
        else rightSide.add(text).height(25f).padBottom(5f).row()

        addTechEnabledIcons(techName, isWorldScreen, rightSide)

        add(rightSide)
        pack()
    }

    private fun addTechEnabledIcons(techName: String, isWorldScreen: Boolean, rightSide: Table) {
        val techEnabledIcons = Table()
        techEnabledIcons.defaults().pad(5f)
        val techIconSize = 30f

        val civName = techManager.civInfo.civName
        val gameBasics = techManager.civInfo.gameInfo.ruleSet

        val tech = gameBasics.technologies[techName]!!

        for (unit in tech.getEnabledUnits(techManager.civInfo))
            techEnabledIcons.add(ImageGetter.getConstructionImage(unit.name).surroundWithCircle(techIconSize))

        for (building in tech.getEnabledBuildings(techManager.civInfo))
            techEnabledIcons.add(ImageGetter.getConstructionImage(building.name).surroundWithCircle(techIconSize))

        for (improvement in gameBasics.tileImprovements.values
                .filter { it.techRequired == techName || it.improvingTech == techName }
                .filter { it.uniqueTo==null || it.uniqueTo==civName }) {
            if (improvement.name.startsWith("Remove"))
                techEnabledIcons.add(ImageGetter.getImage("OtherIcons/Stop")).size(techIconSize)
            else techEnabledIcons.add(ImageGetter.getImprovementIcon(improvement.name, techIconSize))
        }

        for (resource in gameBasics.tileResources.values.filter { it.revealedBy == techName })
            techEnabledIcons.add(ImageGetter.getResourceImage(resource.name, techIconSize))

        for (unique in tech.uniques)
            techEnabledIcons.add(ImageGetter.getImage("OtherIcons/Star")
                    .apply { color = Color.BLACK }.surroundWithCircle(techIconSize))

        if (isWorldScreen) rightSide.add(techEnabledIcons)
        else rightSide.add(techEnabledIcons)
                .width(techEnabledIcons.children.size * (techIconSize+6f))
                .minWidth(150f)
    }
}