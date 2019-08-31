package com.unciv.ui.cityscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.unciv.UnCivGame
import com.unciv.logic.city.CityInfo
import com.unciv.logic.civilization.GreatPersonManager
import com.unciv.models.gamebasics.Building
import com.unciv.models.gamebasics.tr
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.ui.utils.*
import com.unciv.ui.worldscreen.optionstable.YesNoPopupTable
import java.text.DecimalFormat
import java.util.*


class CityInfoTable(private val cityScreen: CityScreen) : Table(CameraStageBaseScreen.skin) {
    val pad = 5f
    init {
        defaults().pad(pad)
        width = cityScreen.stage.width/4
    }

    internal fun update() {
        clear()
        val cityInfo = cityScreen.city

        addBuildingsInfo(cityInfo)

        addStatInfo()

        addGreatPersonPointInfo(cityInfo)

        pack()
    }

    private fun addCategory(str: String, showHideTable: Table) {
        val titleTable = Table().background(ImageGetter.getBackground(ImageGetter.getBlue()))
        val width = cityScreen.stage.width/4 - 2*pad
        val showHideTableWrapper = Table()
        showHideTableWrapper.add(showHideTable).width(width)
        titleTable.add(str.toLabel().setFontSize(22))
        titleTable.onClick {
            if(showHideTableWrapper.hasChildren()) showHideTableWrapper.clear()
            else showHideTableWrapper.add(showHideTable).width(width)
        }
        add(titleTable).width(width).row()
        add(showHideTableWrapper).row()
    }

    fun addBuildingInfo(building: Building, wondersTable: Table){
        val wonderNameAndIconTable = Table()
        wonderNameAndIconTable.touchable = Touchable.enabled
        wonderNameAndIconTable.add(ImageGetter.getConstructionImage(building.name).surroundWithCircle(30f))
        wonderNameAndIconTable.add(building.name.toLabel()).pad(5f)
        wondersTable.add(wonderNameAndIconTable).pad(5f).fillX().row()

        val wonderDetailsTable = Table()
        wondersTable.add(wonderDetailsTable).pad(5f).align(Align.left).row()

        wonderNameAndIconTable.onClick {
            if(wonderDetailsTable.hasChildren())
                wonderDetailsTable.clear()
            else{
                val detailsString = building.getDescription(true,
                        cityScreen.city.civInfo)
                wonderDetailsTable.add(detailsString.toLabel().apply { setWrap(true)})
                        .width(cityScreen.stage.width/4 - 2*pad ).row() // when you set wrap, then you need to manually set the size of the label
                if(!building.isWonder && !building.isNationalWonder) {
                    val sellAmount = cityScreen.city.getGoldForSellingBuilding(building.name)
                    val sellBuildingButton = TextButton("Sell for [$sellAmount] gold".tr(),skin)
                    wonderDetailsTable.add(sellBuildingButton).pad(5f).row()

                    sellBuildingButton.onClick {
                        YesNoPopupTable("Are you sure you want to sell this [${building.name}]?".tr(),
                            {
                                cityScreen.city.sellBuilding(building.name)
                                cityScreen.city.cityStats.update()
                                cityScreen.update()
                            }, cityScreen)
                    }
                    if(cityScreen.city.hasSoldBuildingThisTurn || sellAmount > cityScreen.city.civInfo.gold
                            || !UnCivGame.Current.worldScreen.isPlayersTurn)
                        sellBuildingButton.disable()
                }
                wonderDetailsTable.addSeparator()
            }
        }
    }

    private fun addBuildingsInfo(cityInfo: CityInfo) {
        val wonders = mutableListOf<Building>()
        val specialistBuildings = mutableListOf<Building>()
        val otherBuildings = mutableListOf<Building>()

        for (building in cityInfo.cityConstructions.getBuiltBuildings()) {
            when {
                building.isWonder || building.isNationalWonder -> wonders.add(building)
                building.specialistSlots != null -> specialistBuildings.add(building)
                else -> otherBuildings.add(building)
            }
        }

        if (wonders.isNotEmpty()) {
            val wondersTable = Table()
            addCategory("Wonders",wondersTable)
            for (building in wonders) addBuildingInfo(building,wondersTable)
        }

        if (specialistBuildings.isNotEmpty()) {
            val specialistBuildingsTable = Table()
            addCategory("Specialist Buildings", specialistBuildingsTable)

            for (building in specialistBuildings) {
                addBuildingInfo(building, specialistBuildingsTable)
                val specialistIcons = Table()
                specialistIcons.row().size(20f).pad(5f)
                for (stat in building.specialistSlots!!.toHashMap())
                    for (i in 0 until stat.value.toInt())
                        specialistIcons.add(getSpecialistIcon(stat.key)).size(20f)

                specialistBuildingsTable.add(specialistIcons).pad(0f).row()
            }

            // specialist allocation
            addSpecialistAllocation(skin, cityInfo)
        }

        if (!otherBuildings.isEmpty()) {
            val regularBuildingsTable = Table()
            addCategory("Buildings", regularBuildingsTable)
            for (building in otherBuildings) addBuildingInfo(building, regularBuildingsTable)
        }
    }

    private fun addStatInfo() {
        val cityStats = cityScreen.city.cityStats
        val unifiedStatList = LinkedHashMap<String, Stats>(cityStats.baseStatList)

        for(stats in unifiedStatList.values) stats.happiness=0f

        // add happiness to stat list
        for(entry in cityStats.happinessList.filter { it.value!=0f }){
            if(!unifiedStatList.containsKey(entry.key))
                unifiedStatList[entry.key]= Stats()
            unifiedStatList[entry.key]!!.happiness=entry.value
        }

        // Add maintenance if relevant

        val maintenance = cityStats.cityInfo.cityConstructions.getMaintenanceCosts()
        if(maintenance>0)
            unifiedStatList["Maintenance"]=Stats().add(Stat.Gold,-maintenance.toFloat())



        for(stat in Stat.values()){
            if(unifiedStatList.all { it.value.get(stat)==0f }) continue

            val statValuesTable = Table().apply { defaults().pad(2f) }
            addCategory(stat.name, statValuesTable)
            for(entry in unifiedStatList) {
                val specificStatValue = entry.value.get(stat)
                if(specificStatValue==0f) continue
                statValuesTable.add(entry.key.toLabel())
                statValuesTable.add(DecimalFormat("0.#").format(specificStatValue).toLabel()).row()
            }
            for(entry in cityStats.statPercentBonusList){
                val specificStatValue = entry.value.toHashMap()[stat]!!
                if(specificStatValue==0f) continue
                statValuesTable.add(entry.key.toLabel())
                val decimal = DecimalFormat("0.#").format(specificStatValue)
                statValuesTable.add("+$decimal%".toLabel()).row()
            }
            if(stat==Stat.Food){
                statValuesTable.add("Food eaten".toLabel())
                statValuesTable.add(("-"+DecimalFormat("0.#").format(cityStats.foodEaten)).toLabel()).row()
                val growthBonus = cityStats.getGrowthBonusFromPolicies()
                if(growthBonus>0){
                    statValuesTable.add("Growth bonus".toLabel())
                    statValuesTable.add(("+"+((growthBonus*100).toInt().toString())+"%").toLabel())
                }
            }
        }
    }

    private fun addGreatPersonPointInfo(cityInfo: CityInfo) {
        val greatPersonPoints = cityInfo.getGreatPersonMap()
        val statToGreatPerson = GreatPersonManager().statToGreatPersonMapping
        for (stat in Stat.values()) {
            if (!statToGreatPerson.containsKey(stat)) continue
            if(greatPersonPoints.all { it.value.get(stat)==0f }) continue

            val expanderName = "[" + statToGreatPerson[stat]!! + "] points"
            val greatPersonTable = Table()
            addCategory(expanderName, greatPersonTable)
            for (entry in greatPersonPoints) {
                val value = entry.value.toHashMap()[stat]!!
                if (value == 0f) continue
                greatPersonTable.add(entry.key.toLabel()).padRight(10f)
                greatPersonTable.add(DecimalFormat("0.#").format(value).toLabel()).row()
            }
        }
    }

    private fun addSpecialistAllocation(skin: Skin, cityInfo: CityInfo) {
        val specialistAllocationTable = Table()
        addCategory("Specialist Allocation", specialistAllocationTable) // todo WRONG, BAD - table should contain all the below specialist stuff

        val currentSpecialists = cityInfo.population.specialists.toHashMap()
        val maximumSpecialists = cityInfo.population.getMaxSpecialists()

        for (statToMaximumSpecialist in maximumSpecialists.toHashMap()) {
            val specialistPickerTable = Table()
            if (statToMaximumSpecialist.value == 0f) continue
            val stat = statToMaximumSpecialist.key
            // these two are conflictingly named compared to above...
            val assignedSpecialists = currentSpecialists[stat]!!.toInt()
            val maxSpecialists = statToMaximumSpecialist.value.toInt()
            if (assignedSpecialists > 0) {
                val unassignButton = TextButton("-", skin)
                unassignButton.label.setFontSize(24)
                unassignButton.onClick {
                    cityInfo.population.specialists.add(stat, -1f)
                    cityInfo.cityStats.update()
                    cityScreen.update()
                }
                if(!UnCivGame.Current.worldScreen.isPlayersTurn) unassignButton.disable()
                specialistPickerTable.add(unassignButton)
            } else specialistPickerTable.add()

            val specialistIconTable = Table()
            for (i in 1..maxSpecialists) {
                val icon = getSpecialistIcon(stat, i <= assignedSpecialists)
                specialistIconTable.add(icon).size(30f)
            }
            specialistPickerTable.add(specialistIconTable)
            if (assignedSpecialists < maxSpecialists) {
                val assignButton = TextButton("+", skin)
                assignButton.label.setFontSize(24)
                assignButton.onClick {
                    cityInfo.population.specialists.add(statToMaximumSpecialist.key, 1f)
                    cityInfo.cityStats.update()
                    cityScreen.update()
                }
                if (cityInfo.population.getFreePopulation() == 0 || !UnCivGame.Current.worldScreen.isPlayersTurn)
                    assignButton.disable()
                specialistPickerTable.add(assignButton)
            } else specialistPickerTable.add()
            specialistAllocationTable.add(specialistPickerTable).row()

            val specialistStatTable = Table().apply { defaults().pad(5f) }
            val specialistStats = cityInfo.cityStats.getStatsOfSpecialist(stat, cityInfo.civInfo.policies.adoptedPolicies).toHashMap()
            for (entry in specialistStats) {
                if (entry.value == 0f) continue
                specialistStatTable.add(ImageGetter.getStatIcon(entry.key.toString())).size(20f)
                specialistStatTable.add(entry.value.toInt().toString().toLabel()).padRight(10f)
            }
            specialistAllocationTable.add(specialistStatTable).row()
        }
    }

    private fun getSpecialistIcon(stat: Stat, isFilled: Boolean =true): Image {
        val specialist = ImageGetter.getImage("StatIcons/Specialist")
        if (!isFilled) specialist.color = Color.GRAY
        else specialist.color=when(stat){
            Stat.Production -> Color.BROWN
            Stat.Gold -> Color.GOLD
            Stat.Science -> Color.BLUE
            Stat.Culture -> Color.PURPLE
            else -> Color.WHITE
        }

        return specialist
    }

}