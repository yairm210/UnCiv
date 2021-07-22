package com.unciv.ui.utils

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.*
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.utils.Align
import com.unciv.models.translations.tr

/**
 * A **Replacement** for Gdx [Tooltip], placement does not follow the mouse.
 * 
 * Usage: [group][Group].addStaticTip([text][String], size) builds a [Label] as tip actor and attaches it to your [Group].
 *
 * @param target        The widget the tooltip will be added to - take care this is the same for which addListener is called
 * @param content       The actor to display as Tooltip
 * @param targetAlign   Point on the [target] widget to align the Tooltip to
 * @param tipAlign      Point on the Tooltip to align with the given point on the [target] 
 * @param offset        Additional offset for Tooltip position after alignment
 * @param animate       Use show/hide animations
 * @param forceContentSize  Force virtual [content] width/height for alignment calculation
 *                      - because Gdx auto layout reports wrong dimensions on scaled actors.
 */
@Suppress("unused") // reported incorrectly even when a use is right here in the Companion
class UncivTooltip <T: Actor>(
    val target: Group,
    val content: T,
    val targetAlign: Int = Align.topRight,
    val tipAlign: Int = Align.topRight,
    val offset: Vector2 = Vector2.Zero,
    val animate: Boolean = true,
    forceContentSize: Vector2? = null,
) : InputListener() {

    // region fields
    private val container: Container<T> = Container(content)
    enum class TipState { Hidden, Showing, Shown, Hiding }
    /** current visibility state of the Tooltip */
    var state: TipState = TipState.Hidden
        private set
    private val contentWidth: Float
    private val contentHeight: Float

    init {
        content.touchable = Touchable.disabled
        container.pack()
        contentWidth = forceContentSize?.x ?: content.width
        contentHeight = forceContentSize?.y ?: content.height
    }

     //region show, hide and positioning
    /** Show the Tooltip ([immediate]ly or begin the animation). _Can_ be called programmatically. */
    fun show(immediate: Boolean = false) {
        val useAnimation = animate && !immediate
        if (state == TipState.Shown || state == TipState.Showing && useAnimation || !target.hasParent()) return
        if (state == TipState.Showing || state == TipState.Hiding) {
            container.clearActions()
            state = TipState.Hidden
            container.remove()
        }
        val pos = target.localToParentCoordinates(target.getEdgePoint(targetAlign)).add(offset)
        container.run {
            val originX = getOriginX(contentWidth,tipAlign)
            val originY = getOriginY(contentHeight,tipAlign)
            setOrigin(originX, originY)
            setPosition(pos.x - originX, pos.y - originY)
            if (useAnimation) {
                isTransform = true
                color.a = 0.2f
                setScale(0.05f)
            } else {
                isTransform = false
                color.a = 1f
                setScale(1f)
            }
        }
        target.parent.addActor(container)
        if (useAnimation) {
            state = TipState.Showing
            container.addAction(Actions.sequence(
                Actions.parallel(
                    Actions.fadeIn(UncivSlider.tipAnimationDuration, Interpolation.fade),
                    Actions.scaleTo(1f, 1f, 0.2f, Interpolation.fade)
                ),
                Actions.run { if (state == TipState.Showing) state = TipState.Shown }
            ))
        } else
            state = TipState.Shown
    }

    /** Hide the Tooltip ([immediate]ly or begin the animation). _Can_ be called programmatically. */
    fun hide(immediate: Boolean = false) {
        val useAnimation = animate && !immediate
        if (state == TipState.Hidden || state == TipState.Hiding && useAnimation) return
        if (state == TipState.Showing || state == TipState.Hiding) {
            container.clearActions()
            state = TipState.Shown  // edge case. may actually only be partially 'shown' - animate hide anyway
        }
        if (useAnimation) {
            state = TipState.Hiding
            container.addAction(Actions.sequence(
                Actions.parallel(
                    Actions.alpha(0.2f, 0.2f, Interpolation.fade),
                    Actions.scaleTo(0.05f, 0.05f, 0.2f, Interpolation.fade)
                ),
                Actions.removeActor(),
                Actions.run { if (state == TipState.Hiding) state = TipState.Hidden }
            ))
        } else {
            container.remove()
            state = TipState.Hidden
        }
    }

    private fun getOriginX(width: Float, align: Int) = when {
        (align and Align.left) != 0 -> 0f
        (align and Align.right) != 0 -> width
        else -> width / 2
    }
    private fun getOriginY(height: Float, align: Int) = when {
        (align and Align.bottom) != 0 -> 0f
        (align and Align.top) != 0 -> height
        else -> height / 2
    }
    private fun Actor.getEdgePoint(align: Int) =
        Vector2(getOriginX(width,align),getOriginY(height,align))
    //endregion

    //region events
    override fun enter(event: InputEvent?, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
        // assert(event?.listenerActor == target) - tested - holds true
        if (fromActor != null && fromActor.isDescendantOf(target)) return
        show()
    }

    override fun exit(event: InputEvent?, x: Float, y: Float, pointer: Int, toActor: Actor?) {
        if (toActor != null && toActor.isDescendantOf(target)) return
        hide()
    }

    override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int ): Boolean {
        container.toFront()     // this is a no-op if it has no parent
        return super.touchDown(event, x, y, pointer, button)
    }
    //endregion
    
    companion object {
        /**
         * Add a [Label]-based Tooltip with a rounded-corner background to a [Table] or other [Group].
         * 
         * Tip is positioned over top right corner, slightly overshooting the receiver widget, longer tip [text]s will extend to the left.
         * 
         * @param size _Vertical_ size of the entire Tooltip including background
         * @param always override requirement: presence of physical keyboard
         */
        fun Group.addTooltip(text: String, size: Float = 26f, always: Boolean = false) {
            if (!(always || KeyPressDispatcher.keyboardAvailable) || text.isEmpty()) return

            val label = text.toLabel(ImageGetter.getBlue(), 38)
            label.setAlignment(Align.center)

            val background = ImageGetter.getRoundedEdgeRectangle(Color.LIGHT_GRAY)
            // This controls text positioning relative to the background.
            // The minute fiddling makes both single caps and longer text look centered. 
            @Suppress("SpellCheckingInspection")
            val skewPadDescenders = if (",;gjpqy".any { it in text }) 0f else 2.5f
            val horizontalPad = if (text.length > 1) 10f else 6f
            background.setPadding(4f+skewPadDescenders, horizontalPad, 8f-skewPadDescenders, horizontalPad)

            val widthHeightRatio: Float
            val labelWithBackground = Container(label).apply {
                setBackground(background)
                pack()
                widthHeightRatio = width / height
                isTransform = true  // otherwise setScale is ignored
                setScale(size / height)
            }

            addListener(UncivTooltip(this,
                labelWithBackground,
                forceContentSize = Vector2(size * widthHeightRatio, size),
                offset = Vector2(-size/4, size/4),
                tipAlign = Align.top
            ))
        }

        /**
         * Add a single Char [Label]-based Tooltip with a rounded-corner background to a [Table] or other [Group].
         *
         * Tip is positioned over top right corner, slightly overshooting the receiver widget.
         *
         * @param size _Vertical_ size of the entire Tooltip including background
         * @param always override requirement: presence of physical keyboard
         */
        fun Group.addTooltip(char: Char, size: Float = 26f, always: Boolean = false) {
            addTooltip((if (char in "Ii") 'i' else char.toUpperCase()).toString(), size, always)
        }

        /**
         * Add a [Label]-based Tooltip for a keyboard binding with a rounded-corner background to a [Table] or other [Group].
         *
         * Tip is positioned over top right corner, slightly overshooting the receiver widget.
         *
         * @param size _Vertical_ size of the entire Tooltip including background
         * @param always override requirement: presence of physical keyboard
         */
        fun Group.addTooltip(key: KeyCharAndCode, size: Float = 26f, always: Boolean = false) {
            if (key != KeyCharAndCode.UNKNOWN)
                addTooltip(key.toString().tr(), size, always)
        }
    }
}
