package com.unciv.ui.newgamescreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.UncivGame
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSpeed
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.VictoryType
import com.unciv.ui.utils.*

class GameOptionsTable(
    val previousScreen: IPreviousScreen,
    val withoutMods: Boolean = false,
    val updatePlayerPickerTable:(desiredCiv:String)->Unit
) : Table(CameraStageBaseScreen.skin) {
    var gameParameters = previousScreen.gameSetupInfo.gameParameters
    val ruleset = previousScreen.ruleset
    var locked = false

    init {
        getGameOptionsTable()
    }

    fun update() {
        clear()
        getGameOptionsTable()
    }

    private fun getGameOptionsTable() {
        top()
        defaults().pad(5f)

        add(Table().apply {
            defaults().pad(5f)
            addBaseRulesetSelectBox()
            addDifficultySelectBox()
            addGameSpeedSelectBox()
            addEraSelectBox()
            // align left and right edges with other SelectBoxes but allow independent dropdown width
            add(Table().apply {
                addCityStatesSlider()
            }).colspan(2).fillX().row()
        }).row()
        addVictoryTypeCheckboxes()

        val checkboxTable = Table().apply { defaults().left().pad(2.5f) }
        checkboxTable.addBarbariansCheckbox()
        checkboxTable.addOneCityChallengeCheckbox()
        checkboxTable.addNuclearWeaponsCheckbox()
        checkboxTable.addIsOnlineMultiplayerCheckbox()
        if (UncivGame.Current.settings.showExperimentalReligion)
            checkboxTable.addReligionCheckbox()
        add(checkboxTable).center().row()

        if (!withoutMods)
            add(getModCheckboxes()).row()

        pack()
    }

    private fun Table.addCheckbox(text: String, initialState: Boolean, lockable: Boolean = true, onChange: (newValue: Boolean) -> Unit) {
        val checkbox = text.toCheckBox(initialState) { onChange(it) }
        checkbox.isDisabled = lockable && locked
        add(checkbox).colspan(2).row()
    }

    private fun Table.addBarbariansCheckbox() =
            addCheckbox("No Barbarians", gameParameters.noBarbarians)
            { gameParameters.noBarbarians = it }

    private fun Table.addOneCityChallengeCheckbox() =
            addCheckbox("One City Challenge", gameParameters.oneCityChallenge)
            { gameParameters.oneCityChallenge = it }

    private fun Table.addNuclearWeaponsCheckbox() =
            addCheckbox("Enable nuclear weapons", gameParameters.nuclearWeaponsEnabled)
            { gameParameters.nuclearWeaponsEnabled = it }


    private fun Table.addIsOnlineMultiplayerCheckbox() =
            addCheckbox("Online Multiplayer", gameParameters.isOnlineMultiplayer)
            {
                gameParameters.isOnlineMultiplayer = it
                updatePlayerPickerTable("")
            }
    
    private fun Table.addReligionCheckbox() =
            addCheckbox("Enable Religion", gameParameters.religionEnabled)
            { gameParameters.religionEnabled = it }

    private fun Table.addCityStatesSlider() {
        val numberOfCityStates = ruleset.nations.filter { it.value.isCityState() }.size
        if (numberOfCityStates == 0) return

        add("{Number of City-States}:".toLabel()).left().expandX()
        val slider = UncivSlider(0f,numberOfCityStates.toFloat(),1f) {
            gameParameters.numberOfCityStates = it.toInt()
        }
        slider.value = gameParameters.numberOfCityStates.toFloat()
        slider.isDisabled = locked
        add(slider).row()
    }

    private fun Table.addSelectBox(text: String, values: Collection<String>, initialState: String, onChange: (newValue: String) -> Unit) {
        add(text.toLabel()).left()
        val selectBox = TranslatedSelectBox(values, initialState, CameraStageBaseScreen.skin)
        selectBox.isDisabled = locked
        selectBox.onChange { onChange(selectBox.selected.value) }
        onChange(selectBox.selected.value)
        add(selectBox).fillX().row()
    }

    private fun Table.addDifficultySelectBox() {
        addSelectBox("{Difficulty}:", ruleset.difficulties.keys, gameParameters.difficulty)
        { gameParameters.difficulty = it }
    }

    private fun Table.addBaseRulesetSelectBox() {
        if (BaseRuleset.values().size < 2) return
        addSelectBox("{Base Ruleset}:", BaseRuleset.values().map { it.fullName }, gameParameters.baseRuleset.fullName)
        {
            gameParameters.baseRuleset = BaseRuleset.values().first { br -> br.fullName == it }
            reloadRuleset()
        }
    }

    private fun Table.addGameSpeedSelectBox() {
        addSelectBox("{Game Speed}:", GameSpeed.values().map { it.name }, gameParameters.gameSpeed.name)
        { gameParameters.gameSpeed = GameSpeed.valueOf(it) }
    }

    private fun Table.addEraSelectBox() {
        if (ruleset.technologies.isEmpty()) return // mod with no techs
        // Should eventually be changed to use eras.json, but we'll keep it like this for now for mod compatibility
        val eras = ruleset.technologies.values.filter { !it.uniques.contains("Starting tech") }.map { it.era() }.distinct()
        addSelectBox("{Starting Era}:", eras, gameParameters.startingEra)
        { gameParameters.startingEra = it }
    }


    private fun addVictoryTypeCheckboxes() {
        add("{Victory Conditions}:".toLabel()).colspan(2).row()

        // Create a checkbox for each VictoryType existing
        var i = 0
        val victoryConditionsTable = Table().apply { defaults().pad(5f) }
        for (victoryType in VictoryType.values()) {
            if (victoryType == VictoryType.Neutral) continue
            val victoryCheckbox = victoryType.name.toCheckBox(gameParameters.victoryTypes.contains(victoryType)) {
                // If the checkbox is checked, adds the victoryTypes else remove it
                if (it) {
                    gameParameters.victoryTypes.add(victoryType)
                } else {
                    gameParameters.victoryTypes.remove(victoryType)
                }
            }
            victoryCheckbox.name = victoryType.name
            victoryCheckbox.isDisabled = locked
            victoryConditionsTable.add(victoryCheckbox).left()
            if (++i % 2 == 0) victoryConditionsTable.row()
        }
        add(victoryConditionsTable).colspan(2).row()
    }

    fun reloadRuleset() {
        ruleset.clear()
        val newRuleset = RulesetCache.getComplexRuleset(gameParameters.mods)
        ruleset.add(newRuleset)
        ruleset.mods += gameParameters.mods
        ruleset.modOptions = newRuleset.modOptions

        ImageGetter.setNewRuleset(ruleset)
    }

    fun getModCheckboxes(isPortrait: Boolean = false): Table {
        return ModCheckboxTable(gameParameters.mods, previousScreen as CameraStageBaseScreen, isPortrait) {
            UncivGame.Current.translations.translationActiveMods = gameParameters.mods
            reloadRuleset()
            update()

            var desiredCiv = ""
            if (gameParameters.mods.contains(it)) {
                val modNations = RulesetCache[it]?.nations
                if (modNations != null && modNations.size > 0) {
                    desiredCiv = modNations.keys.first()
                }
            }

            updatePlayerPickerTable(desiredCiv)
        }
    }

}

