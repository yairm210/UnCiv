package com.unciv.ui.utils

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.scenes.scene2d.*
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.utils.viewport.ExtendViewport
import com.unciv.UncivGame
import com.unciv.models.Tutorial
import com.unciv.models.UncivSound
import com.unciv.models.translations.tr
import com.unciv.ui.tutorials.TutorialController
import java.util.HashMap
import kotlin.concurrent.thread
import kotlin.random.Random

/*
 * For now, combination keys cannot easily be expressed.
 * Pressing Ctrl-Letter will arrive one event for Input.Keys.CONTROL_LEFT and one for the ASCII control code point
 *      so Ctrl-R can be handled using KeyCharAndCode('\u0012')
 * Pressing Alt-Something likewise will fire once for Alt and once for the unmodified keys with no indication Alt is held
 *      (Exception: international keyboard AltGr-combos)
 * An update supporting easy declarations for any modifier combos would need to use Gdx.input.isKeyPressed()
 * Gdx seems to omit support for a modifier mask (e.g. Ctrl-Alt-Shift) so we would need to reinvent this
 */

/**
 * Represents a key for use in an InputListener keyTyped() handler
 *
 * Example: KeyCharAndCode('R'), KeyCharAndCode(Input.Keys.F1)
 */
data class KeyCharAndCode(val char: Char, val code: Int) {
    // express keys with a Char value
    constructor(char: Char): this(char.toLowerCase(), 0)
    // express keys that only have a keyCode like F1
    constructor(code: Int): this(Char.MIN_VALUE, code)
    // helper for use in InputListener keyTyped()
    constructor(event: InputEvent?, character: Char)
            : this (
                character.toLowerCase(),
                if (character == Char.MIN_VALUE && event!=null) event.keyCode else 0
            )

    @ExperimentalStdlibApi
    override fun toString(): String {
        // debug helper
        return when {
            char == Char.MIN_VALUE -> Input.Keys.toString(code)
            char < ' ' -> "Ctrl-" + Char(char.toInt()+64)
            else -> "\"$char\""
        }
    }
}

class KeyPressDispatcher {
    private val keyMap: HashMap<KeyCharAndCode, (() -> Unit)> = hashMapOf()
    private var checkpoint: Set<KeyCharAndCode> = setOf()

    // access by our data class
    val keys: Set<KeyCharAndCode>
        get() = keyMap.keys
    operator fun get(key: KeyCharAndCode) = keyMap[key]
    operator fun set(key: KeyCharAndCode, action: () -> Unit) {
        keyMap[key] = action
    }
    operator fun contains(key: KeyCharAndCode) = keyMap.contains(key)
    fun remove(key: KeyCharAndCode) = keyMap.remove(key)

    // access by Char
    operator fun get(char: Char) = keyMap[KeyCharAndCode(char)]
    operator fun set(char: Char, action: () -> Unit) {
        keyMap[KeyCharAndCode(char)] = action
    }
    operator fun contains(char: Char) = keyMap.contains(KeyCharAndCode(char))
    fun remove(char: Char) = keyMap.remove(KeyCharAndCode(char))

    // access by Int keyCodes
    operator fun get(code: Int) = keyMap[KeyCharAndCode(code)]
    operator fun set(code: Int, action: () -> Unit) {
        keyMap[KeyCharAndCode(code)] = action
    }
    operator fun contains(code: Int) = keyMap.contains(KeyCharAndCode(code))
    fun remove(code: Int) = keyMap.remove(KeyCharAndCode(code))

    fun clear() {
        checkpoint = setOf()
        keyMap.clear()
    }
    fun setCheckpoint() {
        checkpoint = keyMap.keys.toSet()
    }
    fun revertToCheckPoint() {
        keyMap.keys.minus(checkpoint).forEach { remove(it) }
    }
}

open class CameraStageBaseScreen : Screen {

    var game: UncivGame = UncivGame.Current
    var stage: Stage

    protected val tutorialController by lazy { TutorialController(this) }

    val keyPressDispatcher = KeyPressDispatcher()

    init {
        val resolutions: List<Float> = game.settings.resolution.split("x").map { it.toInt().toFloat() }
        val width = resolutions[0]
        val height = resolutions[1]

        stage = Stage(ExtendViewport(width, height), SpriteBatch())

        stage.addListener(
                object : InputListener() {
                    override fun keyTyped(event: InputEvent?, character: Char): Boolean {
                        val key = KeyCharAndCode(event, character)

                        if (key !in keyPressDispatcher || hasOpenPopups())
                            return super.keyTyped(event, character)

                        //try-catch mainly for debugging. Breakpoints in the vicinity can make the event fire twice in rapid succession, second time the context can be invalid
                        try {
                            keyPressDispatcher[key]?.invoke()
                        } catch (ex: Exception) {}
                        return true
                    }
                }
        )
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

    override fun dispose() {}

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
                add("Button", ImageGetter.getRoundedEdgeTableBackground(), Drawable::class.java)
                addRegions(TextureAtlas("skin/flat-earth-ui.atlas"))
                load(Gdx.files.internal("skin/flat-earth-ui.json"))
            }
            skin.get(TextButton.TextButtonStyle::class.java).font = Fonts.font.apply { data.setScale(20 / ORIGINAL_FONT_SIZE) }
            skin.get(CheckBox.CheckBoxStyle::class.java).font = Fonts.font.apply { data.setScale(20 / ORIGINAL_FONT_SIZE) }
            skin.get(CheckBox.CheckBoxStyle::class.java).fontColor = Color.WHITE
            skin.get(Label.LabelStyle::class.java).font = Fonts.font.apply { data.setScale(18 / ORIGINAL_FONT_SIZE) }
            skin.get(Label.LabelStyle::class.java).fontColor = Color.WHITE
            skin.get(TextField.TextFieldStyle::class.java).font = Fonts.font.apply { data.setScale(18 / ORIGINAL_FONT_SIZE) }
            skin.get(SelectBox.SelectBoxStyle::class.java).font = Fonts.font.apply { data.setScale(20 / ORIGINAL_FONT_SIZE) }
            skin.get(SelectBox.SelectBoxStyle::class.java).listStyle.font = Fonts.font.apply { data.setScale(20 / ORIGINAL_FONT_SIZE) }
            skin
        }
        internal var batch: Batch = SpriteBatch()
    }

    /** It returns the assigned [InputListener] */
    fun onBackButtonClicked(action: () -> Unit): InputListener {
        val listener = object : InputListener() {
            override fun keyDown(event: InputEvent?, keycode: Int): Boolean {
                if (keycode == Input.Keys.BACK || keycode == Input.Keys.ESCAPE) {
                    action()
                    return true
                }
                return false
            }
        }
        stage.addListener(listener)
        return listener
    }

}


fun Button.disable(){
    touchable= Touchable.disabled
    color= Color.GRAY
}
fun Button.enable() {
    color = Color.WHITE
    touchable = Touchable.enabled
}
var Button.isEnabled: Boolean
    //Todo: Use in PromotionPickerScreen, TradeTable, WorldScreen.updateNextTurnButton
    get() = touchable == Touchable.enabled
    set(value) = if (value) enable() else disable()

fun colorFromRGB(r: Int, g: Int, b: Int) = Color(r/255f, g/255f, b/255f, 1f)
fun colorFromRGB(rgb:List<Int>) = colorFromRGB(rgb[0],rgb[1],rgb[2])

fun Actor.centerX(parent:Actor){ x = parent.width/2 - width/2 }
fun Actor.centerY(parent:Actor){ y = parent.height/2- height/2}
fun Actor.center(parent:Actor){ centerX(parent); centerY(parent)}

fun Actor.centerX(parent:Stage){ x = parent.width/2 - width/2 }
fun Actor.centerY(parent:Stage){ y = parent.height/2- height/2}
fun Actor.center(parent:Stage){ centerX(parent); centerY(parent)}


/** same as [onClick], but sends the [InputEvent] and coordinates along */
fun Actor.onClickEvent(sound: UncivSound = UncivSound.Click, function: (event: InputEvent?, x: Float, y: Float) -> Unit) {
    this.addListener(object : ClickListener() {
        override fun clicked(event: InputEvent?, x: Float, y: Float) {
            thread(name="Sound") { Sounds.play(sound) }
            function(event, x, y)
        }
    })
}

// If there are other buttons that require special clicks then we'll have an onclick that will accept a string parameter, no worries
fun Actor.onClick(sound: UncivSound = UncivSound.Click, function: () -> Unit) {
    onClickEvent(sound) { _, _, _ -> function() }
}

fun Actor.onClick(function: () -> Unit): Actor {
    onClick(UncivSound.Click, function)
    return this
}

fun Actor.onChange(function: () -> Unit): Actor {
    this.addListener(object : ChangeListener() {
        override fun changed(event: ChangeEvent?, actor: Actor?) {
            function()
        }
    })
    return this
}

fun Actor.surroundWithCircle(size: Float, resizeActor: Boolean = true, color: Color = Color.WHITE): IconCircleGroup {
    return IconCircleGroup(size,this,resizeActor, color)
}

fun Actor.addBorder(size:Float,color:Color,expandCell:Boolean=false):Table{
    val table = Table()
    table.pad(size)
    table.background = ImageGetter.getBackground(color)
    val cell = table.add(this)
    if (expandCell) cell.expand()
    cell.fill()
    table.pack()
    return table
}

fun Table.addSeparator(): Cell<Image> {
    row()
    val image = ImageGetter.getWhiteDot()
    val cell = add(image).colspan(columns).height(2f).fill()
    row()
    return cell
}

fun Table.addSeparatorVertical(): Cell<Image> {
    val image = ImageGetter.getWhiteDot()
    val cell = add(image).width(2f).fillY()
    return cell
}

fun <T : Actor> Table.addCell(actor: T): Table {
    add(actor)
    return this
}

/**
 * Solves concurrent modification problems - everyone who had a reference to the previous arrayList can keep using it because it hasn't changed
 */
fun <T> ArrayList<T>.withItem(item:T): ArrayList<T> {
    val newArrayList = ArrayList(this)
    newArrayList.add(item)
    return newArrayList
}

/**
 * Solves concurrent modification problems - everyone who had a reference to the previous arrayList can keep using it because it hasn't changed
 */
fun <T> HashSet<T>.withItem(item:T): HashSet<T> {
    val newHashSet = HashSet(this)
    newHashSet.add(item)
    return newHashSet
}

/**
 * Solves concurrent modification problems - everyone who had a reference to the previous arrayList can keep using it because it hasn't changed
 */
fun <T> ArrayList<T>.withoutItem(item:T): ArrayList<T> {
    val newArrayList = ArrayList(this)
    newArrayList.remove(item)
    return newArrayList
}


/**
 * Solves concurrent modification problems - everyone who had a reference to the previous arrayList can keep using it because it hasn't changed
 */
fun <T> HashSet<T>.withoutItem(item:T): HashSet<T> {
    val newHashSet = HashSet(this)
    newHashSet.remove(item)
    return newHashSet
}

fun String.toTextButton() = TextButton(this.tr(), CameraStageBaseScreen.skin)

/** also translates */
fun String.toLabel() = Label(this.tr(),CameraStageBaseScreen.skin)
fun Int.toLabel() = this.toString().toLabel()

/** All text is originally rendered in 50px, and thn scaled to fit the size of the text we need now.
 * This has several advantages: It means we only render each character once (good for both runtime and RAM),
 * AND it means that our 'custom' emojis only need to be once size (50px) and they'll be rescaled for what's needed. */
const val ORIGINAL_FONT_SIZE = 50f

// We don't want to use setFontSize and setFontColor because they set the font,
//  which means we need to rebuild the font cache which means more memory allocation.
fun String.toLabel(fontColor:Color= Color.WHITE, fontSize:Int=18): Label {
    var labelStyle = CameraStageBaseScreen.skin.get(Label.LabelStyle::class.java)
    if(fontColor!= Color.WHITE || fontSize!=18) { // if we want the default we don't need to create another style
        labelStyle = Label.LabelStyle(labelStyle) // clone this to another
        labelStyle.fontColor = fontColor
        if (fontSize != 18) labelStyle.font = Fonts.font
    }
    return Label(this.tr(), labelStyle).apply { setFontScale(fontSize/ORIGINAL_FONT_SIZE) }
}


fun Label.setFontColor(color:Color): Label { style=Label.LabelStyle(style).apply { fontColor=color }; return this }

fun Label.setFontSize(size:Int): Label {
    style = Label.LabelStyle(style)
    style.font = Fonts.font
    style = style // because we need it to call the SetStyle function. Yuk, I know.
    return this.apply { setFontScale(size/ORIGINAL_FONT_SIZE) } // for chaining
}


fun <T> List<T>.randomWeighted(weights: List<Float>, random: Random = Random): T {
    if (this.isEmpty()) throw NoSuchElementException("Empty list.")
    if (this.size != weights.size) throw UnsupportedOperationException("Weights size does not match this list size.")

    val totalWeight = weights.sum()
    val randDouble = random.nextDouble()
    var sum = 0f

    for (i in weights.indices) {
        sum += weights[i] / totalWeight
        if (randDouble <= sum)
            return this[i]
    }
    return this.last()
}
