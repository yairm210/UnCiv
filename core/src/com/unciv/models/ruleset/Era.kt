package com.unciv.models.ruleset

import com.badlogic.gdx.graphics.Color
import com.unciv.models.stats.INamed
import com.unciv.ui.utils.colorFromRGB

class Era : INamed {
    override var name: String = ""
    var researchAgreementCost = 300
    var startingSettlerCount = 1
    var startingSettlerUnit = "Settler" // For mods which have differently named settlers
    var startingWorkerCount = 0
    var startingWorkerUnit = "Worker"
    var startingMilitaryUnitCount = 1
    var startingMilitaryUnit = "Warrior"
    var startingGold = 0
    var startingCulture = 0
    var settlerPopulation = 1
    var settlerBuildings = ArrayList<String>()
    var startingObsoleteWonders = ArrayList<String>()
    var faithUnitCost = 200
    var iconRGB: List<Int>? = null
    
    fun getStartingUnits(): List<String> {
        val startingUnits = mutableListOf<String>()
        repeat(startingSettlerCount) {startingUnits.add(startingSettlerUnit)}
        repeat(startingWorkerCount) {startingUnits.add(startingWorkerUnit)}
        repeat(startingMilitaryUnitCount) {startingUnits.add(startingMilitaryUnit)}
        return startingUnits
    }
    
    fun getColor(): Color {
        if (iconRGB == null) return Color.WHITE.cpy()
        return colorFromRGB(iconRGB!![0], iconRGB!![1], iconRGB!![2])
    }
}