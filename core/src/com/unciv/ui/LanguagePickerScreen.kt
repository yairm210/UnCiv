package com.unciv.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.UncivGame
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.tr
import com.unciv.ui.pickerscreens.PickerScreen
import com.unciv.ui.utils.ImageGetter
import com.unciv.ui.utils.enable
import com.unciv.ui.utils.onClick
import com.unciv.ui.utils.toLabel


class LanguageTable(val language:String, ruleset: Ruleset):Table(){
    private val blue = ImageGetter.getBlue()
    private val darkBlue = blue.cpy().lerp(Color.BLACK,0.5f)!!
    val percentComplete: Int

    init{
        pad(10f)
        defaults().pad(10f)
        if(ImageGetter.imageExists("FlagIcons/$language"))
            add(ImageGetter.getImage("FlagIcons/$language")).size(40f)
        val translations = ruleset.Translations
        val availableTranslations = translations.filter { it.value.containsKey(language) }

        if(language=="English") percentComplete = 100
        else percentComplete = (availableTranslations.size*100 / translations.size) - 1 //-1 so if the user encounters anything not translated he'll be like "OK that's the 1% missing"

        val spaceSplitLang = language.replace("_"," ")
        add("$spaceSplitLang ($percentComplete%)".toLabel())
        update("")
        touchable = Touchable.enabled // so click listener is activated when any part is clicked, not only children
        pack()
    }

    fun update(chosenLanguage:String){
        background = ImageGetter.getBackground( if(chosenLanguage==language) blue else darkBlue)
    }

}

class LanguagePickerScreen: PickerScreen(){
    var chosenLanguage = "English"

    private val languageTables = ArrayList<LanguageTable>()

    fun update(){
        languageTables.forEach { it.update(chosenLanguage) }
    }

    init {
        closeButton.isVisible = false
        topTable.add(Label(
                "Please note that translations are a " +
                    "community-based work in progress and are INCOMPLETE! \n" +
                    "The percentage shown is how much of the language is translated in-game.\n" +
                    "If you want to help translating the game into your language, \n"+
                    "  instructions are in the Github readme! (Menu > Community > Github)",skin)).pad(10f).row()

        val ruleSet = UncivGame.Current.ruleset
        languageTables.addAll(ruleSet.Translations.getLanguages().map { LanguageTable(it,ruleSet) }
                .sortedByDescending { it.percentComplete } )

        languageTables.forEach {
            it.onClick {
                chosenLanguage = it.language
                rightSideButton.enable()
                rightSideButton.clearActions()
                rightSideButton.addAction(Actions.forever(Actions.sequence(
                        Actions.color(Color.GREEN,0.5f),
                        Actions.color(Color.WHITE,0.5f)
                )))
                update()
            }
            topTable.add(it).pad(10f).row()
        }

        rightSideButton.setText("Pick language".tr())


        rightSideButton.onClick {
            pickLanguage()
        }
    }

    fun pickLanguage(){
        UncivGame.Current.settings.language = chosenLanguage
        UncivGame.Current.settings.save()
        resetFonts()
        UncivGame.Current.startNewGame()
        dispose()
    }
}