package com.unciv.ui.cityscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.UncivGame
import com.unciv.logic.city.CityInfo
import com.unciv.logic.map.TileInfo
import com.unciv.models.UncivSound
import com.unciv.models.stats.Stats
import com.unciv.models.translations.tr
import com.unciv.ui.utils.*
import kotlin.math.roundToInt

class CityScreenTileTable(val city: CityInfo): Table(){
    val innerTable = Table()
    init{
        innerTable.background = ImageGetter.getBackground(ImageGetter.getBlue().lerp(Color.BLACK, 0.5f))
        add(innerTable).pad(2f).fill()
        background = ImageGetter.getBackground(Color.WHITE)
    }

    fun update(selectedTile: TileInfo?) {
        innerTable.clear()
        if (selectedTile == null){
            isVisible=false
            return
        }
        isVisible=true
        innerTable.clearChildren()

        val stats = selectedTile.getTileStats(city, city.civInfo)
        innerTable.pad(20f)

        innerTable.add(selectedTile.toString(city.civInfo).toLabel()).colspan(2)
        innerTable.row()
        innerTable.add(getTileStatsTable(stats)).row()


        if(selectedTile.getOwner()==null && selectedTile.neighbors.any {it.getCity()==city}
            && selectedTile in city.tilesInRange){
            val goldCostOfTile = city.expansion.getGoldCostOfTile(selectedTile)

            val buyTileButton = TextButton("Buy for [$goldCostOfTile] gold".tr(), CameraStageBaseScreen.skin)
            buyTileButton.onClick(UncivSound.Coin) {
                city.expansion.buyTile(selectedTile)
                UncivGame.Current.setScreen(CityScreen(city))
            }
            if(goldCostOfTile>city.civInfo.gold || city.isPuppet || !UncivGame.Current.worldScreen.isPlayersTurn)
                buyTileButton.disable()

            innerTable.add(buyTileButton)
        }
        if(city.canAcquireTile(selectedTile)) {
            val acquireTileButton = TextButton("Acquire".tr(), CameraStageBaseScreen.skin)
            acquireTileButton.onClick {
                city.expansion.takeOwnership(selectedTile)
                UncivGame.Current.setScreen(CityScreen(city))
            }
            innerTable.add(acquireTileButton)
        }
        innerTable.pack()
        pack()
    }

    private fun getTileStatsTable(stats: Stats): Table {
        val statsTable = Table()
        statsTable.defaults().pad(2f)
        for (entry in stats.toHashMap().filterNot { it.value == 0f }) {
            statsTable.add(ImageGetter.getStatIcon(entry.key.toString())).size(20f)
            statsTable.add(entry.value.roundToInt().toString().toLabel())
            statsTable.row()
        }
        return statsTable
    }
}