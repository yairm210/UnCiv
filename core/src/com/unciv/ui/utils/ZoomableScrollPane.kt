package com.unciv.ui.utils

import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.utils.ActorGestureListener
import kotlin.math.sqrt


open class ZoomableScrollPane: ScrollPane(null) {
    var continousScrollingX = false

    init{
        // Remove the existing inputListener
        // which defines that mouse scroll = vertical movement
        val zoomListener = listeners.last { it is InputListener && it !in captureListeners }
        removeListener(zoomListener)
        addZoomListeners()
    }

    open fun zoom(zoomScale: Float) {
        if (zoomScale < 0.5f || zoomScale > 2) return
        setScale(zoomScale)
    }

    private fun addZoomListeners() {

        addListener(object : InputListener() {
            override fun scrolled(event: InputEvent?, x: Float, y: Float, amountX: Float, amountY: Float): Boolean {
                if (amountX > 0 || amountY > 0) zoom(scaleX * 0.8f)
                else zoom(scaleX / 0.8f)
                return false
            }
        })

        addListener(object : ActorGestureListener() {
            var lastScale = 1f
            var lastInitialDistance = 0f

            override fun zoom(event: InputEvent?, initialDistance: Float, distance: Float) {
                if (lastInitialDistance != initialDistance) {
                    lastInitialDistance = initialDistance
                    lastScale = scaleX
                }
                val scale: Float = sqrt((distance / initialDistance).toDouble()).toFloat() * lastScale
                zoom(scale)
            }
        })
    }
}