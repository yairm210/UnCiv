package com.unciv.ui.utils

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.utils.viewport.ExtendViewport
import com.unciv.MainMenuScreen
import com.unciv.UncivGame
import com.unciv.models.Tutorial
import com.unciv.ui.tutorials.TutorialController
import com.unciv.ui.worldscreen.WorldScreen
import com.unciv.ui.worldscreen.mainmenu.OptionsPopup
import kotlin.concurrent.thread

open class CameraStageBaseScreen : Screen {

    var game: UncivGame = UncivGame.Current
    var stage: Stage

    protected val tutorialController by lazy { TutorialController(this) }

    val keyPressDispatcher = KeyPressDispatcher(this.javaClass.simpleName)

    init {
        val resolutions: List<Float> = game.settings.resolution.split("x").map { it.toInt().toFloat() }
        val height = resolutions[1]

        /** The ExtendViewport sets the _minimum_(!) world size - the actual world size will be larger, fitted to screen/window aspect ratio. */
        stage = Stage(ExtendViewport(height, height), SpriteBatch())

        keyPressDispatcher.install(stage) { hasOpenPopups() }
    }

    override fun show() {}

    override fun render(delta: Float) {
        Gdx.gl.glClearColor(0f, 0f, 0.2f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        stage.act()
        stage.draw()
    }

    override fun resize(width: Int, height: Int) {
        stage.viewport.update(width, height, true)
    }

    override fun pause() {}

    override fun resume() {}

    override fun hide() {}

    override fun dispose() {
        keyPressDispatcher.uninstall()
    }

    fun displayTutorial(tutorial: Tutorial, test: (() -> Boolean)? = null) {
        if (!game.settings.showTutorials) return
        if (game.settings.tutorialsShown.contains(tutorial.name)) return
        if (test != null && !test()) return
        tutorialController.showTutorial(tutorial)
    }

    companion object {
        lateinit var skin:Skin
        fun setSkin() {
            Fonts.resetFont()
            skin = Skin().apply {
                add("Nativefont", Fonts.font, BitmapFont::class.java)
                add("RoundedEdgeRectangle", ImageGetter.getRoundedEdgeRectangle(), Drawable::class.java)
                add("Rectangle", ImageGetter.getDrawable(""), Drawable::class.java)
                add("Circle", ImageGetter.getDrawable("OtherIcons/Circle").apply { setMinSize(20f, 20f) }, Drawable::class.java)
                add("Scrollbar", ImageGetter.getDrawable("").apply { setMinSize(10f, 10f) }, Drawable::class.java)
                add("RectangleWithOutline", ImageGetter.getRectangleWithOutline(), Drawable::class.java)
                add("Select-box", ImageGetter.getSelectBox(), Drawable::class.java)
                add("Select-box-pressed", ImageGetter.getSelectBoxPressed(), Drawable::class.java)
                add("Checkbox", ImageGetter.getCheckBox(), Drawable::class.java)
                add("Checkbox-pressed", ImageGetter.getCheckBoxPressed(), Drawable::class.java)
                load(Gdx.files.internal("Skin.json"))
            }
            skin.get(TextButton.TextButtonStyle::class.java).font = Fonts.font.apply { data.setScale(20 / Fonts.ORIGINAL_FONT_SIZE) }
            skin.get(CheckBox.CheckBoxStyle::class.java).font = Fonts.font.apply { data.setScale(20 / Fonts.ORIGINAL_FONT_SIZE) }
            skin.get(CheckBox.CheckBoxStyle::class.java).fontColor = Color.WHITE
            skin.get(Label.LabelStyle::class.java).font = Fonts.font.apply { data.setScale(18 / Fonts.ORIGINAL_FONT_SIZE) }
            skin.get(Label.LabelStyle::class.java).fontColor = Color.WHITE
            skin.get(TextField.TextFieldStyle::class.java).font = Fonts.font.apply { data.setScale(18 / Fonts.ORIGINAL_FONT_SIZE) }
            skin.get(SelectBox.SelectBoxStyle::class.java).font = Fonts.font.apply { data.setScale(20 / Fonts.ORIGINAL_FONT_SIZE) }
            skin.get(SelectBox.SelectBoxStyle::class.java).listStyle.font = Fonts.font.apply { data.setScale(20 / Fonts.ORIGINAL_FONT_SIZE) }
            skin
        }
    }

    fun onBackButtonClicked(action: () -> Unit) {
        keyPressDispatcher[KeyCharAndCode.BACK] = action
    }

    /** @return `true` if the screen is higher than it is wide */
    fun isPortrait() = stage.viewport.screenHeight > stage.viewport.screenWidth
    /** @return `true` if the screen is higher than it is wide _and_ resolution is at most 1050x700 */
    fun isCrampedPortrait() = isPortrait() &&
            game.settings.resolution.split("x").map { it.toInt() }.last() <= 700
    /** @return `true` if the screen is narrower than 4:3 landscape */
    fun isNarrowerThan4to3() = stage.viewport.screenHeight * 4 > stage.viewport.screenWidth * 3

    fun openOptionsPopup() {
        val limitOrientationsHelper = game.limitOrientationsHelper
        if (limitOrientationsHelper == null || !game.settings.allowAndroidPortrait || !isCrampedPortrait()) {
            OptionsPopup(this).open(force = true)
            return
        }
        if (!(this is MainMenuScreen || this is WorldScreen)) {
            throw IllegalArgumentException("openOptionsPopup called on wrong derivative class")
        }
        limitOrientationsHelper.allowPortrait(false)
        thread(name="WaitForRotation") {
            var waited = 0
            while (true) {
                val newScreen = (UncivGame.Current.screen as? CameraStageBaseScreen)
                if (waited >= 10000 || newScreen!=null && !newScreen.isPortrait() ) {
                    Gdx.app.postRunnable { OptionsPopup(newScreen ?: this).open(true) }
                    break
                }
                Thread.sleep(200)
                waited += 200
            }
        }
    }
}
