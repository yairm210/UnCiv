package com.unciv.ui.utils

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.*
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Timer

/**
 * Modified Gdx [Slider]
 * 
 * Has +/- buttons at the end for easier single steps
 * Shows a timed tip with the actual value every time it changes
 * Disables listeners of any ScrollPanes this is nested in while dragging
 * 
 * Note: No attempt is made to distinguish sources of value changes, so the initial setting
 * of the value when a screen is initialized will also trigger the 'tip'. This is intentional.
 * 
 * @param min           Initializes [Slider.min]
 * @param max           Initializes [Slider.max]
 * @param step          Initializes [Slider.stepSize]
 * @param vertical      Initializes [Slider.vertical]
 * @param plusMinus     Enable +/- buttons
 * @param onChange      Optional lambda gets called with the current value on change
 */
class UncivSlider (
    min: Float,
    max: Float,
    step: Float,
    vertical: Boolean = false,
    plusMinus: Boolean = true,
    onChange: ((Float) -> Unit)? = null
): Table(CameraStageBaseScreen.skin) {
    // constants for geometry tuning
    companion object {
        const val plusMinusFontSize = 18
        const val plusMinusCircleSize = 20f
        const val padding = 5f                  // padding around the Slider, doubled between it and +/- buttons
        const val hideDelay = 3f                // delay in s to hide tooltip
        const val tipAnimationDuration = 0.2f   // tip show/hide duration in s
    }

    // component widgets
    private val slider = Slider(min, max, step, vertical, CameraStageBaseScreen.skin)
    private val minusButton: IconCircleGroup?
    private val plusButton: IconCircleGroup?
    private val tipLabel = "".toLabel(Color.LIGHT_GRAY)
    private val tipContainer: Container<Label> = Container(tipLabel)
    private val tipHideTask: Timer.Task

    // Compatibility with default Slider
    val minValue: Float
        get() = slider.minValue
    val maxValue: Float
        get() = slider.maxValue
    var value: Float
        get() = slider.value
        set(newValue) {
            slider.value = newValue
            valueChanged()
        }
    var stepSize: Float
        get() = slider.stepSize
        set(value) {
            slider.stepSize = value
            stepChanged()
        }
    val isDragging: Boolean
        get() = slider.isDragging
    var isDisabled: Boolean
        get() = slider.isDisabled
        set(value) { slider.isDisabled = value }

    // Value tip format
    var tipFormat = "%.1f"
    
    // Detect changes in isDragging
    private var hasFocus = false

    init {
        tipLabel.setOrigin(Align.center)
        tipContainer.touchable = Touchable.disabled
        tipHideTask = object : Timer.Task() {
            override fun run() {
                hideTip()
            }
        }

        stepChanged()   // Initialize tip formatting

        if (plusMinus) {
            minusButton = "-".toLabel(Color.BLACK, plusMinusFontSize)
                .apply { setAlignment(Align.center) }
                .surroundWithCircle(plusMinusCircleSize)
            minusButton.onClick {
                value -= stepSize
            }
            add(minusButton).apply { 
                if (vertical) padBottom(padding) else padLeft(padding)
            } 
            if (vertical) row()
        } else minusButton = null

        slider.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                if (slider.isDragging != hasFocus) {
                    hasFocus = slider.isDragging
                    if (hasFocus)
                        killScrollPanes()
                    else
                        resurrectScrollPanes()
                }
                valueChanged()
                onChange?.invoke(slider.value)
            }
        })
        add(slider).pad(padding).fill()

        if (plusMinus) {
            if (vertical) row()
            plusButton = "+".toLabel(Color.BLACK, plusMinusFontSize)
                .apply { setAlignment(Align.center) }
                .surroundWithCircle(plusMinusCircleSize)
            plusButton.onClick { 
                value += stepSize
            }
            add(plusButton).apply {
                if (vertical) padTop(padding) else padRight(padding)
            }
        } else plusButton = null
        
        row()
        valueChanged()
    }

    // Visual feedback
    private fun valueChanged() {
        tipLabel.setText(tipFormat.format(slider.value))
        if (!tipHideTask.isScheduled) showTip()
        tipHideTask.cancel()
        Timer.schedule(tipHideTask, hideDelay)

        val enableMinus = slider.value > slider.minValue
        minusButton?.touchable = if(enableMinus) Touchable.enabled else Touchable.disabled
        minusButton?.apply {circle.color.a = if(enableMinus) 1f else 0.5f}
        val enablePlus = slider.value < slider.maxValue
        plusButton?.touchable = if(enablePlus) Touchable.enabled else Touchable.disabled
        plusButton?.apply {circle.color.a = if(enablePlus) 1f else 0.5f}
    }
    
    private fun stepChanged() {
        tipFormat = when {
            stepSize > 0.99f -> "%.0f"
            stepSize > 0.099f -> "%.1f"
            stepSize > 0.0099f -> "%.2f"
            else -> "%.3f"
        }
        tipLabel.setText(tipFormat.format(slider.value))
    }

    // Attempt to prevent ascendant ScrollPane(s) from stealing our focus
    private val killedListeners: MutableMap<ScrollPane, List<EventListener>> = mutableMapOf()
    private val killedCaptureListeners: MutableMap<ScrollPane, List<EventListener>> = mutableMapOf()

    private fun killScrollPanes() {
        var widget: Group = this
        while (widget.parent != null) {
            widget = widget.parent
            if (widget !is ScrollPane) continue
            if (widget.listeners.size != 0)
                killedListeners[widget] = widget.listeners.toList()
            if (widget.captureListeners.size != 0)
                killedCaptureListeners[widget] = widget.captureListeners.toList()
            widget.clearListeners()
        }
    }

    private fun resurrectScrollPanes() {
        var widget: Group = this
        while (widget.parent != null) {
            widget = widget.parent
            if (widget !is ScrollPane) continue
            killedListeners[widget]?.forEach {
                widget.addListener(it)
            }
            killedListeners.remove(widget)
            killedCaptureListeners[widget]?.forEach {
                widget.addCaptureListener(it)
            }
            killedCaptureListeners.remove(widget)
        }
    }
    
    // Helpers to manage the light-weight "tooltip" showing the value
    private fun showTip() {
        if (tipContainer.hasParent()) return
        tipContainer.pack()
        val pos = slider.localToParentCoordinates(Vector2(slider.width / 2, slider.height))
        tipContainer.run {
            setOrigin(Align.bottom)
            setPosition(pos.x, pos.y, Align.bottom)
            isTransform = true
            color.a = 0.2f
            setScale(0.05f)
        }
        addActor(tipContainer)
        tipContainer.addAction(
            Actions.parallel(
                Actions.fadeIn(tipAnimationDuration, Interpolation.fade),
                Actions.scaleTo(1f, 1f, 0.2f, Interpolation.fade)
            )
        )
    }

    private fun hideTip() {
        tipContainer.addAction(
            Actions.sequence(
                Actions.parallel(
                    Actions.alpha(0.2f, 0.2f, Interpolation.fade),
                    Actions.scaleTo(0.05f, 0.05f, 0.2f, Interpolation.fade)
                ),
                Actions.removeActor()
            )
        )
    }
}
