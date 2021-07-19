package com.unciv.ui.newgamescreen

import com.badlogic.gdx.scenes.scene2d.Stage
import com.unciv.models.ruleset.Ruleset

/**
 * Interface to implement for all screens using [GameOptionsTable] and [PlayerPickerTable]
 * for universal usage of those two tables.
 */
interface IPreviousScreen {
    val gameSetupInfo: GameSetupInfo
    var stage: Stage
    val ruleset: Ruleset

    // Having `fun setRightSideButtonEnabled(boolean: Boolean)` part of this interface gives a warning:
    // "Names of the parameter #1 conflict in the following members of supertypes: 'public abstract fun setRightSideButtonEnabled(boolean: Boolean): Unit defined in com.unciv.ui.newgamescreen.IPreviousScreen, public final fun setRightSideButtonEnabled(bool: Boolean): Unit defined in com.unciv.ui.pickerscreens.PickerScreen'. This may cause problems when calling this function with named arguments."
}