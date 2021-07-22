package com.unciv.ui.utils

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.EventListener
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Stage

/*
 * For now, many combination keys cannot easily be expressed.
 * Pressing Ctrl-Letter will arrive one event for Input.Keys.CONTROL_LEFT and one for the ASCII control code point
 *      so Ctrl-R can be handled using KeyCharAndCode('\u0012') or KeyCharAndCode.ctrl('R')
 * Pressing Alt-Something likewise will fire once for Alt and once for the unmodified keys with no indication Alt is held
 *      (Exception: international keyboard AltGr-combos)
 * An update supporting easy declarations for any modifier combos would need to use Gdx.input.isKeyPressed()
 * Gdx seems to omit support for a modifier mask (e.g. Ctrl-Alt-Shift) so we would need to reinvent this
 * 
 * Note: It is important that KeyCharAndCode is an immutable data class to support usage as HashMap key
 */

/**
 * Represents a key for use in an InputListener keyTyped() handler
 *
 * Example: KeyCharAndCode('R'), KeyCharAndCode(Input.Keys.F1)
 */
data class KeyCharAndCode(val char: Char, val code: Int) {
    /** helper 'cloning constructor' to allow feeding both fields from a factory function */
    private constructor(from: KeyCharAndCode): this(from.char, from.code)
    /** Map keys from a Char - will detect by keycode if one can be mapped, by character otherwise */
    constructor(char: Char): this(mapChar(char))
    /** express keys that only have a keyCode like F1 */
    constructor(code: Int): this(Char.MIN_VALUE, code)

    // From Kotlin 1.5? on the Ctrl- line will need Char(char.code+64)
    // see https://github.com/Kotlin/KEEP/blob/master/proposals/stdlib/char-int-conversions.md
    override fun toString(): String {
        // debug helper, but also used for tooltips
        fun fixedKeysToString(code: Int) = when (code) {
            Input.Keys.BACKSPACE -> "Backspace"  // Gdx displaying this as "Delete" is Bullshit!
            Input.Keys.FORWARD_DEL -> "Del"      // Likewise
            else -> Input.Keys.toString(code)
        }
        return when {
            char == Char.MIN_VALUE -> fixedKeysToString(code)
            this == ESC -> "ESC"
            char < ' ' -> "Ctrl-" + (char.toInt()+64).toChar()
            else -> "\"$char\""
        }
    }
    
    companion object {
        // Convenience shortcuts for frequently used constants
        val BACK = KeyCharAndCode(Input.Keys.BACK)
        val ESC = KeyCharAndCode(Input.Keys.ESCAPE)
        val RETURN = KeyCharAndCode(Input.Keys.ENTER)
        val NUMPAD_ENTER = KeyCharAndCode(Input.Keys.NUMPAD_ENTER)
        val SPACE = KeyCharAndCode(Input.Keys.SPACE)
        val BACKSPACE= KeyCharAndCode(Input.Keys.BACKSPACE)
        val DEL = KeyCharAndCode(Input.Keys.FORWARD_DEL)        // Gdx "DEL" is just plain wrong!
        /** Guaranteed to be ignored by [KeyPressDispatcher.set] and never to be generated for an actual event, used as fallback to ensure no action is taken */
        val UNKNOWN = KeyCharAndCode(Input.Keys.UNKNOWN)

        /** mini-factory for control codes - case insensitive */
        fun ctrl(letter: Char) = KeyCharAndCode((letter.toInt() and 31).toChar(), 0)

        /** mini-factory for KeyCharAndCode values to be compared by character, not by code */
        fun ascii(char: Char) = KeyCharAndCode(char.toLowerCase(), 0)
        
        /** factory maps a Char to a keyCode if possible, returns a Char-based instance otherwise */
        fun mapChar(char: Char): KeyCharAndCode {
            val code = Input.Keys.valueOf(char.toUpperCase().toString())
            return if (code == -1) KeyCharAndCode(char,0) else KeyCharAndCode(Char.MIN_VALUE, code)
        }
    }
}


/** A manager for a [keyTyped][InputListener.keyTyped] [InputListener], based on [HashMap].
 *  Uses [KeyCharAndCode] as keys to express bindings for both Ascii and function keys.
 *
 *  [install] and [uninstall] handle adding the listener to a [Stage].
 *  Use indexed assignments to react to specific keys, e.g.:
 *  ```
 *      keyPressDispatcher[Input.Keys.F1] = { showHelp() }
 *      keyPressDispatcher['+'] = { zoomIn() }
 *  ```
 *  Optionally use [setCheckpoint] and [revertToCheckPoint] to remember and restore one state.
 *  
 *  @param name Optional name of the container screen or popup for debugging
 */
class KeyPressDispatcher(val name: String? = null) : HashMap<KeyCharAndCode, (() -> Unit)>() {
    private var checkpoint: Set<KeyCharAndCode> = setOf()       // set of keys marking a checkpoint
    private var listener: EventListener? = null     // holds listener code, captures its params in install() function
    private var listenerInstalled = false           // flag for lazy Stage.addListener()
    private var installStage: Stage? = null         // Keep stage passed by install() for lazy addListener and uninstall

    // access by Char
    operator fun get(char: Char) = this[KeyCharAndCode(char)]
    operator fun set(char: Char, action: () -> Unit) {
        this[KeyCharAndCode(char)] = action
    }
    operator fun contains(char: Char) = this.contains(KeyCharAndCode(char))
    fun remove(char: Char) = this.remove(KeyCharAndCode(char))

    // access by Int keyCodes
    operator fun get(code: Int) = this[KeyCharAndCode(code)]
    operator fun set(code: Int, action: () -> Unit) {
        this[KeyCharAndCode(code)] = action
    }
    operator fun contains(code: Int) = this.contains(KeyCharAndCode(code))
    fun remove(code: Int) = this.remove(KeyCharAndCode(code))

    // access by KeyCharAndCode
    operator fun set(key: KeyCharAndCode, action: () -> Unit) {
        if (key == KeyCharAndCode.UNKNOWN) return
        super.put(key, action)
        // Make both Enter keys equivalent
        if (key == KeyCharAndCode.RETURN)
            super.put(KeyCharAndCode.NUMPAD_ENTER, action)
        // Likewise always match Back to ESC
        if (key == KeyCharAndCode.BACK)
            super.put(KeyCharAndCode.ESC, action)
        checkInstall()
    }
    override fun remove(key: KeyCharAndCode): (() -> Unit)? {
        if (key == KeyCharAndCode.UNKNOWN) return null
        val result = super.remove(key)
        if (key == KeyCharAndCode.RETURN)
            super.remove(KeyCharAndCode.NUMPAD_ENTER)
        if (key == KeyCharAndCode.BACK)
            super.remove(KeyCharAndCode.ESC)
        checkInstall()
        return result
    }

    override fun toString(): String {
        return (if (name==null) "" else "$name.") +
            "KeyPressDispatcher(" + keys.joinToString(limit = 6){ it.toString() } + ")"
    }

    /** Removes all of the mappings, including a checkpoint if set. */
    override fun clear() {
        checkpoint = setOf()
        super.clear()
        checkInstall()
    }

    /** Set a checkpoint: The current set of keys will not be removed on a subsequent [revertToCheckPoint] */
    fun setCheckpoint() {
        checkpoint = keys.toSet()
    }
    /** Revert to a checkpoint: Remove all mappings except those that existed on a previous [setCheckpoint] call.
     * If no checkpoint has been set, this is equivalent to [clear] */
    fun revertToCheckPoint() {
        keys.minus(checkpoint).forEach { remove(it) }
        checkInstall()
    }

    /** install our [EventListener] on a stage with optional inhibitor
     * @param   stage The [Stage] to add the listener to
     * @param   checkIgnoreKeys An optional lambda - when it returns true all keys are ignored
     */
    fun install(stage: Stage, checkIgnoreKeys: (() -> Boolean)? = null) {
        if (installStage != null) uninstall()
        listener =
            object : InputListener() {
                override fun keyTyped(event: InputEvent?, character: Char): Boolean {
                    // look for both key code and ascii entries - ascii first as the
                    // Char constructor of KeyCharAndCode generates keyCode based instances
                    // preferentially but we would miss Ctrl- combos otherwise
                    val key = when {
                        contains(KeyCharAndCode.ascii(character)) ->
                            KeyCharAndCode.ascii(character)
                        event == null ->
                            KeyCharAndCode.UNKNOWN
                        else ->
                            KeyCharAndCode(event.keyCode)
                    }

                    // see if we want to handle this key, and if not, let it propagate
                    if (!contains(key) || (checkIgnoreKeys?.invoke() == true))
                        return super.keyTyped(event, character)
                    
                    // try-catch mainly for debugging. Breakpoints in the vicinity can make the event fire twice in rapid succession, second time the context can be invalid
                    try {
                        this@KeyPressDispatcher[key]?.invoke()
                    } catch (ex: Exception) {}
                    return true
                }
            }
        installStage = stage
        checkInstall()
    }

    /** uninstall our [EventListener] from the stage it was installed on. */
    fun uninstall() {
        checkInstall(forceRemove = true)
        listener = null
        installStage = null
    }

    /** Implements lazy hooking of the listener into the stage.
     *
     *  The listener will be added to the stage's listeners only when - and as soon as -
     *  [this][KeyPressDispatcher] contains mappings.
     *  When all mappings are removed or cleared the listener is removed from the stage.
     */
    private fun checkInstall(forceRemove: Boolean = false) {
        if (listener == null || installStage == null) return
        if (listenerInstalled && (isEmpty() || isPaused || forceRemove)) {
            listenerInstalled = false
            installStage!!.removeListener(listener)
        } else if (!listenerInstalled && !(isEmpty() || isPaused)) {
            installStage!!.addListener(listener)
            listenerInstalled = true
        }
    }

    /** Allows temporarily suspending this [KeyPressDispatcher] */
    var isPaused: Boolean = false
        set(value) {
            field = value
            checkInstall()
        }

    companion object {
        /** Tests presence of a physical keyboard - static here as convenience shortcut only */
        val keyboardAvailable = Gdx.input.isPeripheralAvailable(Input.Peripheral.HardwareKeyboard)
    }
}
