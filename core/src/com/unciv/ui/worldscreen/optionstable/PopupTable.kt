package com.unciv.ui.worldscreen.optionstable

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.UnCivGame
import com.unciv.ui.utils.*

open class PopupTable: Table(){
    init {
        val tileTableBackground = ImageGetter.getBackground(ImageGetter.getBlue().lerp(Color.BLACK, 0.5f))
        background = tileTableBackground

        this.pad(20f)
        this.defaults().pad(5f)
    }

    fun addButton(text:String, action:()->Unit){
        val button = TextButton(text.tr(), CameraStageBaseScreen.skin).apply { color= ImageGetter.getBlue() }
        button.addClickListener(action)
        add(button).row()
    }
}

class YesNoPopupTable(question:String, action:()->Unit,
                      screen: CameraStageBaseScreen = UnCivGame.Current.worldScreen) : PopupTable(){
    init{
        if(!isOpen) {
            isOpen=true
            val skin = CameraStageBaseScreen.skin
            add(Label(question, skin)).colspan(2).row()

            add(TextButton("No".tr(), skin).apply { addClickListener { close() } })
            add(TextButton("Yes".tr(), skin).apply { addClickListener { close(); action() } })
            pack()
            center(screen.stage)
            screen.stage.addActor(this)
        }
    }

    fun close(){
        remove()
        isOpen=false
    }

    companion object {
        var isOpen=false
    }
}