package com.unciv.ui.utils

import kotlin.concurrent.thread

//Its a popUp which will close itself after a given amount of time
//Standard time is one second (in milliseconds)
class ResponsePopup (message: String, screen: CameraStageBaseScreen, time: Long = 1000) : Popup(screen){
    private val visibilityTime = time
    init {
        addGoodSizedLabel(message)
        open()
        //move it to the top so its not in the middle of the screen
        //have to be done after open() because open() centers the popup
        y = screen.stage.height - (height + padTop)
    }

    private fun startTimer(){
        thread (name = "ResponsePopup") {
            Thread.sleep(visibilityTime)
            this.close()
        }
    }

    override fun setVisible(visible: Boolean) {
        if (visible)
            startTimer()
        super.setVisible(visible)
    }
}
