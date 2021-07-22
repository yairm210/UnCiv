package com.unciv.models

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.unciv.ui.utils.KeyCharAndCode
import com.unciv.ui.utils.ImageGetter
import com.unciv.Constants
import com.unciv.models.translations.equalsPlaceholderText
import com.unciv.models.translations.getPlaceholderParameters


/** Unit Actions - class - carries dynamic data and actual execution.
 * Static properties are in [UnitActionType].
 * Note this is for the buttons offering actions, not the ongoing action stored with a [MapUnit][com.unciv.logic.map.MapUnit]
 */
data class UnitAction(
        val type: UnitActionType,
        val title: String = type.value,
        val isCurrentAction: Boolean = false,
        val uncivSound: UncivSound = type.uncivSound,
        val action: (() -> Unit)? = null
) {
    fun getIcon(): Actor {
        if (type.imageGetter != null) return type.imageGetter.invoke()
        return when {
            type == UnitActionType.Upgrade 
                    && title.equalsPlaceholderText("Upgrade to [] ([] gold)") -> {
                ImageGetter.getUnitIcon(title.getPlaceholderParameters()[0])
            }
            type == UnitActionType.Create
                    && title.equalsPlaceholderText("Create []") -> {
                ImageGetter.getImprovementIcon(title.getPlaceholderParameters()[0])
            }
            type == UnitActionType.SpreadReligion
                    && title.equalsPlaceholderText("Spread []") -> {
                ImageGetter.getReligionIcon(title.getPlaceholderParameters()[0])
            }
            type == UnitActionType.Fortify || type == UnitActionType.FortifyUntilHealed -> {
                val match = fortificationRegex.matchEntire(title)
                val percentFortified = match?.groups?.get(1)?.value?.toInt() ?: 0
                ImageGetter.getImage("OtherIcons/Shield").apply { 
                    color = Color.BLACK.cpy().lerp(Color.GREEN, percentFortified / 80f)
                }
            }
            else -> ImageGetter.getImage("OtherIcons/Star")
        }
    }
    companion object {
        private val fortificationRegex = Regex(""".* (\d+)%""")
    }
}

/** Unit Actions - generic enum with static properties
 * 
 * @param value         _default_ label to display, can be overridden in UnitAction instantiation
 * @param imageGetter   optional lambda to get an Icon - `null` if icon is dependent on outside factors and needs special handling
 * @param key           keyboard binding - can be a [KeyCharAndCode], a [Char], or omitted.
 * @param uncivSound    _default_ sound, can be overridden in UnitAction instantiation
 */
enum class UnitActionType(
    val value: String,
    val imageGetter: (()-> Actor)?,
    val key: KeyCharAndCode,
    val uncivSound: UncivSound = UncivSound.Click
) {
    SwapUnits("Swap units",
        { ImageGetter.getImage("OtherIcons/Swap") }, 'y'),
    Automate("Automate",
        { ImageGetter.getUnitIcon("Great Engineer") }, 'm'),
    StopAutomation("Stop automation",
        { ImageGetter.getImage("OtherIcons/Stop") }, 'm'),
    StopMovement("Stop movement",
        { imageGetStopMove() }, '.'),
    Sleep("Sleep",
        { ImageGetter.getImage("OtherIcons/Sleep") }, 'f'),
    SleepUntilHealed("Sleep until healed",
        { ImageGetter.getImage("OtherIcons/Sleep") }, 'h'),
    Fortify("Fortify",
        null, 'f', UncivSound.Fortify),
    FortifyUntilHealed("Fortify until healed",
        null, 'h', UncivSound.Fortify),
    Explore("Explore",
        { ImageGetter.getUnitIcon("Scout") }, 'x'),
    StopExploration("Stop exploration",
        { ImageGetter.getImage("OtherIcons/Stop") }, 'x'),
    Promote("Promote",
        { imageGetPromote() }, 'o', UncivSound.Promote),
    Upgrade("Upgrade",
        null, 'u', UncivSound.Upgrade),
    Pillage("Pillage", 
        { ImageGetter.getImage("OtherIcons/Pillage") }, 'p'),
    Paradrop("Paradrop",
        { ImageGetter.getUnitIcon("Paratrooper") }, 'p'),
    SetUp("Set up",
        { ImageGetter.getUnitIcon("Catapult") }, 't', UncivSound.Setup),
    FoundCity("Found city",
        { ImageGetter.getUnitIcon(Constants.settler) }, 'c', UncivSound.Silent),
    ConstructImprovement("Construct improvement",
        { ImageGetter.getUnitIcon(Constants.worker) }, 'i'),
    // Deprecated since 3.15.4
        ConstructRoad("Construct road", {ImageGetter.getImprovementIcon("Road")}, 'r'),
    //
    Create("Create",
        null, 'i', UncivSound.Chimes),
    SpreadReligion("Spread Religion",
        null, 'g', UncivSound.Choir),
    HurryResearch("Hurry Research",
        { ImageGetter.getUnitIcon("Great Scientist") }, 'g', UncivSound.Chimes),
    StartGoldenAge("Start Golden Age",
        { ImageGetter.getUnitIcon("Great Artist") }, 'g', UncivSound.Chimes),
    HurryWonder("Hurry Wonder",
        { ImageGetter.getUnitIcon("Great Engineer") }, 'g', UncivSound.Chimes),
    ConductTradeMission("Conduct Trade Mission",
        { ImageGetter.getUnitIcon("Great Merchant") }, 'g', UncivSound.Chimes),
    FoundReligion("Found a Religion",
        { ImageGetter.getUnitIcon("Great Prophet") }, 'g', UncivSound.Choir),
    DisbandUnit("Disband unit",
        { ImageGetter.getImage("OtherIcons/DisbandUnit") }, KeyCharAndCode.DEL),
    GiftUnit("Gift unit",
        { ImageGetter.getImage("OtherIcons/Present") }, UncivSound.Silent),
    ShowAdditionalActions("Show more",
        { imageGetShowMore() }, KeyCharAndCode(Input.Keys.PAGE_DOWN)),
    HideAdditionalActions("Back",
        { imageGetHideMore() }, KeyCharAndCode(Input.Keys.PAGE_UP)),
    ;

    // Allow shorter initializations
    constructor(value: String, imageGetter: (() -> Actor)?, key: Char, uncivSound: UncivSound = UncivSound.Click)
            : this(value, imageGetter, KeyCharAndCode(key), uncivSound)
    constructor(value: String, imageGetter: (() -> Actor)?, uncivSound: UncivSound = UncivSound.Click)
            : this(value, imageGetter, KeyCharAndCode.UNKNOWN, uncivSound)

    companion object {
        // readability factories
        private fun imageGetStopMove() = ImageGetter.getStatIcon("Movement").apply { color = Color.RED }
        private fun imageGetPromote() = ImageGetter.getImage("OtherIcons/Star").apply { color = Color.GOLD }
        private fun imageGetShowMore() = ImageGetter.getImage("OtherIcons/ArrowRight").apply { color = Color.BLACK }
        private fun imageGetHideMore() = ImageGetter.getImage("OtherIcons/ArrowLeft").apply { color = Color.BLACK }
    }
}
