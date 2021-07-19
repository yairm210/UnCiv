package com.unciv.ui.newgamescreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.Ruleset.CheckModLinksResult
import com.unciv.models.ruleset.RulesetCache
import com.unciv.ui.utils.*

class ModCheckboxTable(
    private val mods:LinkedHashSet<String>,
    private val screen: CameraStageBaseScreen,
    isPortrait: Boolean = false,
    onUpdate: (String) -> Unit
): Table(){
    private val modRulesets = RulesetCache.values.filter { it.name != "" }

    init {
        val baseRulesetCheckboxes = ArrayList<CheckBox>()
        val extensionRulesetModButtons = ArrayList<CheckBox>()

        for (mod in modRulesets.sortedBy { it.name }) {
            val checkBox = mod.name.toCheckBox(mod.name in mods)
            checkBox.onChange {
                if (checkBoxChanged(checkBox, mod)) {
                    //todo: persist ExpanderTab states here
                    onUpdate(mod.name)
                }
            }
            if (mod.modOptions.isBaseRuleset) baseRulesetCheckboxes.add(checkBox)
            else extensionRulesetModButtons.add(checkBox)
        }

        val padTop = if (isPortrait) 0f else 16f

        if (baseRulesetCheckboxes.any()) {
            add(ExpanderTab("Base ruleset mods:") {
                it.defaults().pad(5f,0f)
                for (checkbox in baseRulesetCheckboxes) it.add(checkbox).row()
            }).padTop(padTop).growX().row()
        }

        if (isPortrait && baseRulesetCheckboxes.any() && extensionRulesetModButtons.any())
            addSeparator(Color.DARK_GRAY, height = 1f)

        if (extensionRulesetModButtons.any()) {
            add(ExpanderTab("Extension mods:") {
                it.defaults().pad(5f,0f)
                for (checkbox in extensionRulesetModButtons) it.add(checkbox).row()
            }).padTop(padTop).growX().row()
        }
    }
    
    private fun checkBoxChanged(checkBox: CheckBox, mod: Ruleset): Boolean {
        if (checkBox.isChecked) {
            val modLinkErrors = mod.checkModLinks()
            if (modLinkErrors.isNotOK()) {
                ToastPopup("The mod you selected is incorrectly defined!\n\n$modLinkErrors", screen)
                if (modLinkErrors.isError()) {
                    checkBox.isChecked = false
                    return false
                }
            }

            val previousMods = mods.toList()

            if (mod.modOptions.isBaseRuleset)
                for (oldBaseRuleset in previousMods) // so we don't get concurrent modification exceptions
                    if (modRulesets.firstOrNull { it.name == oldBaseRuleset }?.modOptions?.isBaseRuleset == true)
                        mods.remove(oldBaseRuleset)
            mods.add(mod.name)

            var complexModLinkCheck: CheckModLinksResult
            try {
                val newRuleset = RulesetCache.getComplexRuleset(mods)
                newRuleset.modOptions.isBaseRuleset = true // This is so the checkModLinks finds all connections
                complexModLinkCheck = newRuleset.checkModLinks()
            } catch (ex: Exception) {
                // This happens if a building is dependent on a tech not in the base ruleset
                //  because newRuleset.updateBuildingCosts() in getComplexRuleset() throws an error
                complexModLinkCheck = CheckModLinksResult(Ruleset.CheckModLinksStatus.Error, ex.localizedMessage)
            }

            if (complexModLinkCheck.isError()) {
                ToastPopup("{The mod you selected is incompatible with the defined ruleset!}\n\n{$complexModLinkCheck}", screen)
                checkBox.isChecked = false
                mods.clear()
                mods.addAll(previousMods)
                return false
            }

        } else {
            mods.remove(mod.name)
        }

        return true      
    }
}