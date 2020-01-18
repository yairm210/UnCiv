package com.unciv.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.utils.Align
import com.unciv.UncivGame
import com.unciv.logic.HexMath
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeOffersList
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.translations.tr
import com.unciv.ui.cityscreen.CityScreen
import com.unciv.ui.utils.*
import java.text.DecimalFormat
import kotlin.math.roundToInt

class EmpireOverviewScreen(val viewingPlayer:CivilizationInfo) : CameraStageBaseScreen(){
    private val topTable = Table().apply { defaults().pad(10f) }
    private val centerTable = Table().apply {  defaults().pad(20f) }

    init {
        onBackButtonClicked { UncivGame.Current.setWorldScreen() }

        val closeButton = TextButton("Close".tr(), skin)
        closeButton.onClick { UncivGame.Current.setWorldScreen() }
        closeButton.y = stage.height - closeButton.height - 5
        topTable.add(closeButton)

        val setCityInfoButton = TextButton("Cities".tr(),skin)
        val setCities = {
            centerTable.clear()
            centerTable.add(getCityInfoTable())
            centerTable.pack()
        }
        setCities()
        setCityInfoButton.onClick(setCities)
        topTable.add(setCityInfoButton)

        val setStatsInfoButton = TextButton("Stats".tr(),skin)
        setStatsInfoButton.onClick {
            game.settings.addCompletedTutorialTask("See your stats breakdown")
            centerTable.clear()
            centerTable.add(ScrollPane(Table().apply {
                defaults().pad(40f)
                add(getHappinessTable()).top()
                add(getGoldTable()).top()
                add(getScienceTable()).top()
                add(getGreatPeopleTable()).top()
            }))
            centerTable.pack()
        }
        topTable.add(setStatsInfoButton)

        val setCurrentTradesButton = TextButton("Trades".tr(),skin)
        setCurrentTradesButton.onClick {
            centerTable.clear()
            centerTable.add(ScrollPane(getTradesTable())).height(stage.height*0.8f) // so it doesn't cover the navigation buttons
            centerTable.pack()
        }
        topTable.add(setCurrentTradesButton)
        if(viewingPlayer.diplomacy.values.all { it.trades.isEmpty() })
            setCurrentTradesButton.disable()

        val setUnitsButton = TextButton("Units".tr(),skin)
        setUnitsButton.onClick {
            centerTable.clear()
            centerTable.add(ScrollPane(getUnitTable())).height(stage.height*0.8f)
            centerTable.pack()
        }
        topTable.add(setUnitsButton )


        val setDiplomacyButton = TextButton("Diplomacy".tr(),skin)
        setDiplomacyButton.onClick {
            centerTable.clear()
            centerTable.add(getDiplomacyGroup()).height(stage.height*0.8f)
            centerTable.pack()
        }
        topTable.add(setDiplomacyButton)

        val setResourcesButton = TextButton("Resources".tr(),skin)
        setResourcesButton.onClick {
            centerTable.clear()
            centerTable.add(ScrollPane(getResourcesTable())).size(stage.width*0.8f, stage.height*0.8f)
            centerTable.pack()
        }
        topTable.add(setResourcesButton)
        if(viewingPlayer.detailedCivResources.isEmpty())
            setResourcesButton.disable()

        topTable.pack()

        val table = Table()
        table.add(topTable).row()
        table.add(centerTable).expand().row()
        table.setFillParent(true)
        stage.addActor(table)
    }


    private fun getTradesTable(): Table {
        val tradesTable = Table().apply { defaults().pad(10f) }
        for(diplomacy in viewingPlayer.diplomacy.values)
            for(trade in diplomacy.trades)
                tradesTable.add(createTradeTable(trade,diplomacy.otherCiv())).row()

        return tradesTable
    }

    private fun createTradeTable(trade: Trade, otherCiv:CivilizationInfo): Table {
        val generalTable = Table(skin)
        generalTable.add(createOffersTable(viewingPlayer,trade.ourOffers, trade.theirOffers.size)).fillY()
        generalTable.add(createOffersTable(otherCiv, trade.theirOffers, trade.ourOffers.size)).fillY()
        return generalTable
    }

    private fun createOffersTable(civ: CivilizationInfo, offersList: TradeOffersList, numberOfOtherSidesOffers: Int): Table {
        val table = Table()
        table.defaults().pad(10f)
        table.background = ImageGetter.getBackground(civ.nation.getOuterColor())
        table.add(civ.civName.toLabel(civ.nation.getInnerColor())).row()
        table.addSeparator()
        for(offer in offersList){
            var offerText = offer.getOfferText()
            if(!offerText.contains("\n")) offerText+="\n"
            table.add(offerText.toLabel(civ.nation.getInnerColor())).row()
        }
        for(i in 1..numberOfOtherSidesOffers - offersList.size)
            table.add("\n".toLabel()).row() // we want both sides of the general table to have the same number of rows
        return table
    }


    private fun getHappinessTable(): Table {
        val happinessTable = Table(skin)
        happinessTable.defaults().pad(5f)
        happinessTable.add("Happiness".toLabel(fontSize = 24)).colspan(2).row()
        happinessTable.addSeparator()

        val happinessBreakdown = viewingPlayer.stats().getHappinessBreakdown()

        for (entry in happinessBreakdown.filterNot { it.value.roundToInt()==0 }) {
            happinessTable.add(entry.key.tr())
            happinessTable.add(entry.value.roundToInt().toString()).row()
        }
        happinessTable.add("Total".tr())
        happinessTable.add(happinessBreakdown.values.sum().roundToInt().toString())
        happinessTable.pack()
        return happinessTable
    }

    private fun getGoldTable(): Table {
        val goldTable = Table(skin)
        goldTable.defaults().pad(5f)
        goldTable.add("Gold".toLabel(fontSize = 24)).colspan(2).row()
        goldTable.addSeparator()
        var total=0f
        for (entry in viewingPlayer.stats().getStatMapForNextTurn()) {
            if(entry.value.gold==0f) continue
            goldTable.add(entry.key.tr())
            goldTable.add(entry.value.gold.roundToInt().toString()).row()
            total += entry.value.gold
        }
        goldTable.add("Total".tr())
        goldTable.add(total.roundToInt().toString())
        goldTable.pack()
        return goldTable
    }


    private fun getScienceTable(): Table {
        val scienceTable = Table(skin)
        scienceTable.defaults().pad(5f)
        scienceTable.add("Science".toLabel(fontSize = 24)).colspan(2).row()
        scienceTable.addSeparator()
        val scienceStats = viewingPlayer.stats().getStatMapForNextTurn()
                .filter { it.value.science!=0f }
        for (entry in scienceStats) {
            scienceTable.add(entry.key.tr())
            scienceTable.add(entry.value.science.roundToInt().toString()).row()
        }
        scienceTable.add("Total".tr())
        scienceTable.add(scienceStats.values.map { it.science }.sum().roundToInt().toString())
        scienceTable.pack()
        return scienceTable
    }


    private fun getGreatPeopleTable(): Table {
        val greatPeopleTable = Table(skin)

        val greatPersonPoints = viewingPlayer.greatPeople.greatPersonPoints.toHashMap()
        val greatPersonPointsPerTurn = viewingPlayer.getGreatPersonPointsForNextTurn().toHashMap()
        val pointsToGreatPerson = viewingPlayer.greatPeople.pointsForNextGreatPerson

        greatPeopleTable.defaults().pad(5f)
        greatPeopleTable.add("Great person points".toLabel(fontSize = 24)).colspan(3).row()
        greatPeopleTable.addSeparator()
        greatPeopleTable.add()
        greatPeopleTable.add("Current points".tr())
        greatPeopleTable.add("Points per turn".tr()).row()

        val mapping = viewingPlayer.greatPeople.statToGreatPersonMapping
        for(entry in mapping){
            greatPeopleTable.add(entry.value.tr())
            greatPeopleTable.add(greatPersonPoints[entry.key]!!.toInt().toString()+"/"+pointsToGreatPerson)
            greatPeopleTable.add(greatPersonPointsPerTurn[entry.key]!!.toInt().toString()).row()
        }
        val pointsForGreatGeneral = viewingPlayer.greatPeople.greatGeneralPoints.toString()
        val pointsForNextGreatGeneral = viewingPlayer.greatPeople.pointsForNextGreatGeneral.toString()
        greatPeopleTable.add("Great General".tr())
        greatPeopleTable.add("$pointsForGreatGeneral/$pointsForNextGreatGeneral").row()
        greatPeopleTable.pack()
        return greatPeopleTable
    }



    private fun getCityInfoTable(): Table {
        val iconSize = 50f//if you set this too low, there is a chance that the tables will be misaligned
        val padding = 5f

        val cityInfoTableIcons = Table(skin)
        cityInfoTableIcons.defaults().pad(padding).align(Align.center)

        cityInfoTableIcons.add("Cities".toLabel(fontSize = 24)).colspan(8).align(Align.center).row()
        cityInfoTableIcons.add()
        cityInfoTableIcons.add(ImageGetter.getStatIcon("Population")).size(iconSize)
        cityInfoTableIcons.add(ImageGetter.getStatIcon("Food")).size(iconSize)
        cityInfoTableIcons.add(ImageGetter.getStatIcon("Gold")).size(iconSize)
        cityInfoTableIcons.add(ImageGetter.getStatIcon("Science")).size(iconSize)
        cityInfoTableIcons.add(ImageGetter.getStatIcon("Production")).size(iconSize)
        cityInfoTableIcons.add(ImageGetter.getStatIcon("Culture")).size(iconSize)
        cityInfoTableIcons.add(ImageGetter.getStatIcon("Happiness")).size(iconSize)
        cityInfoTableIcons.pack()

        val cityInfoTableDetails = Table(skin)
        cityInfoTableDetails.defaults().pad(padding).minWidth(iconSize).align(Align.left)//we need the min width so we can align the different tables

        for (city in viewingPlayer.cities.sortedBy { it.name }) {
            val button = Button(Label(city.name, skin), skin)
            button.onClick {
                UncivGame.Current.setScreen(CityScreen(city))
            }
            cityInfoTableDetails.add(button)
            cityInfoTableDetails.add(city.cityConstructions.getCityProductionTextForCityButton()).actor!!.setAlignment(Align.left)
            cityInfoTableDetails.add(city.population.population.toString()).actor!!.setAlignment(Align.center)
            cityInfoTableDetails.add(city.cityStats.currentCityStats.food.roundToInt().toString()).actor!!.setAlignment(Align.center)
            cityInfoTableDetails.add(city.cityStats.currentCityStats.gold.roundToInt().toString()).actor!!.setAlignment(Align.center)
            cityInfoTableDetails.add(city.cityStats.currentCityStats.science.roundToInt().toString()).actor!!.setAlignment(Align.center)
            cityInfoTableDetails.add(city.cityStats.currentCityStats.production.roundToInt().toString()).actor!!.setAlignment(Align.center)
            cityInfoTableDetails.add(city.cityStats.currentCityStats.culture.roundToInt().toString()).actor!!.setAlignment(Align.center)
            cityInfoTableDetails.add(city.cityStats.currentCityStats.happiness.roundToInt().toString()).actor!!.setAlignment(Align.center)
            cityInfoTableDetails.row()
        }
        cityInfoTableDetails.pack()

        val cityInfoScrollPane = ScrollPane(cityInfoTableDetails)
        cityInfoScrollPane.pack()
        cityInfoScrollPane.setOverscroll(false, false)//I think it feels better with no overscroll

        val cityInfoTableTotal = Table(skin)
        cityInfoTableTotal.defaults().pad(padding).minWidth(iconSize)//we need the min width so we can align the different tables

        cityInfoTableTotal.add("Total".tr())
        cityInfoTableTotal.add(viewingPlayer.cities.sumBy { it.population.population }.toString()).actor!!.setAlignment(Align.center)
        cityInfoTableTotal.add()//an intended empty space
        cityInfoTableTotal.add(viewingPlayer.cities.sumBy { it.cityStats.currentCityStats.gold.toInt() }.toString()).actor!!.setAlignment(Align.center)
        cityInfoTableTotal.add(viewingPlayer.cities.sumBy { it.cityStats.currentCityStats.science.toInt() }.toString()).actor!!.setAlignment(Align.center)
        cityInfoTableTotal.add()//an intended empty space
        cityInfoTableTotal.add(viewingPlayer.cities.sumBy { it.cityStats.currentCityStats.culture.toInt() }.toString()).actor!!.setAlignment(Align.center)
        cityInfoTableTotal.add(viewingPlayer.cities.sumBy {  it.cityStats.currentCityStats.happiness.toInt() }.toString()).actor!!.setAlignment(Align.center)

        cityInfoTableTotal.pack()

        val table = Table(skin)
        //since the names of the cities are on the left, and the length of the names varies
        //we align every row to the right, coz we set the size of the other(number) cells to the image size
        //and thus, we can guarantee that the tables will be aligned
        table.defaults().pad(padding).align(Align.right)

        table.add(cityInfoTableIcons).row()
        table.add(cityInfoScrollPane).width(cityInfoTableDetails.width).row()
        table.add(cityInfoTableTotal)
        table.pack()

        return table
    }

    fun getUnitTable(): Table {
        val table=Table(skin).apply { defaults().pad(5f) }
        table.add("Name".tr())
        table.add("Action".tr())
        table.add("Strength".tr())
        table.add("Ranged strength".tr())
        table.add("Movement".tr())
        table.add("Closest city".tr())
        table.row()
        table.addSeparator()

        for(unit in viewingPlayer.getCivUnits().sortedBy { it.name }){
            val baseUnit = unit.baseUnit()
            val button = TextButton(unit.name.tr(), skin)
            button.onClick {
                UncivGame.Current.setWorldScreen()
                UncivGame.Current.worldScreen.mapHolder.setCenterPosition(unit.currentTile.position)
            }
            table.add(button).left()
            val mapUnitAction = unit.mapUnitAction
            if (mapUnitAction != null) table.add(if(mapUnitAction.name().startsWith("Fortify")) "Fortify".tr() else mapUnitAction.name().tr()) else table.add()
            if(baseUnit.strength>0) table.add(baseUnit.strength.toString()) else table.add()
            if(baseUnit.rangedStrength>0) table.add(baseUnit.rangedStrength.toString()) else table.add()
            table.add(DecimalFormat("0.#").format(unit.currentMovement)+"/"+unit.getMaxMovement())
            val closestCity = unit.getTile().getTilesInDistance(3).firstOrNull{it.isCityCenter()}
            if (closestCity!=null) table.add(closestCity.getCity()!!.name) else table.add()
            table.row()
        }
        table.pack()
        return table
    }


    fun playerKnows(civ:CivilizationInfo) = civ==viewingPlayer ||
            viewingPlayer.diplomacy.containsKey(civ.civName)

    fun getDiplomacyGroup(): Group {
        val relevantCivs = viewingPlayer.gameInfo.civilizations.filter { !it.isBarbarian() && !it.isCityState() }
        val freeWidth = stage.width
        val freeHeight = stage.height - topTable.height
        val groupSize = if (freeWidth > freeHeight) freeHeight else freeWidth
        val group = Group()
        group.setSize(groupSize,groupSize)
        val civGroups = HashMap<String, Actor>()
        for(i in 0..relevantCivs.lastIndex){
            val civ = relevantCivs[i]

            val civGroup = getCivGroup(civ, "", viewingPlayer)

            val vector = HexMath.getVectorForAngle(2 * Math.PI.toFloat() *i / relevantCivs.size)
            civGroup.center(group)
            civGroup.moveBy(vector.x*groupSize/3, vector.y*groupSize/3)

            civGroups[civ.civName]=civGroup
            group.addActor(civGroup)
        }

        for(civ in relevantCivs.filter { playerKnows(it) && !it.isDefeated() })
            for(diplomacy in civ.diplomacy.values.
                    filter { !it.otherCiv().isBarbarian() && !it.otherCiv().isCityState()
                            && playerKnows(it.otherCiv()) && !it.otherCiv().isDefeated()}){
                val civGroup = civGroups[civ.civName]!!
                val otherCivGroup = civGroups[diplomacy.otherCivName]!!

                val statusLine = ImageGetter.getLine(civGroup.x+civGroup.width/2,civGroup.y+civGroup.height/2,
                        otherCivGroup.x+otherCivGroup.width/2,otherCivGroup.y+otherCivGroup.height/2,3f)

                statusLine.color = if(diplomacy.diplomaticStatus== DiplomaticStatus.War) Color.RED
                else Color.GREEN

                group.addActor(statusLine)
                statusLine.toBack()
            }

        return group
    }


    private fun getResourcesTable(): Table {
        val resourcesTable=Table().apply { defaults().pad(10f) }
        val resourceDrilldown = viewingPlayer.detailedCivResources

        // First row of table has all the icons
        resourcesTable.add()
        val resources = resourceDrilldown.map { it.resource }
                .filter { it.resourceType!=ResourceType.Bonus }.distinct().sortedBy { it.resourceType }

        for(resource in resources)
            resourcesTable.add(ImageGetter.getResourceImage(resource.name,50f))
        resourcesTable.addSeparator()

        val origins = resourceDrilldown.map { it.origin }.distinct()
        for(origin in origins){
            resourcesTable.add(origin.toLabel())
            for(resource in resources){
                val resourceSupply = resourceDrilldown.firstOrNull { it.resource==resource && it.origin==origin }
                if(resourceSupply==null) resourcesTable.add()
                else resourcesTable.add(resourceSupply.amount.toString().toLabel())
            }
            resourcesTable.row()
        }

        resourcesTable.add("Total".toLabel())
        for(resource in resources){
            val sum = resourceDrilldown.filter { it.resource==resource }.sumBy { it.amount }
            resourcesTable.add(sum.toString().toLabel())
        }

        return resourcesTable
    }

    companion object {
        fun getCivGroup(civ: CivilizationInfo, afterCivNameText:String,currentPlayer:CivilizationInfo): Table {
            val civGroup = Table()

            var labelText = civ.civName.tr()+afterCivNameText
            var labelColor=Color.WHITE
            val backgroundColor:Color

            if (civ.isDefeated()) {
                civGroup.add(ImageGetter.getImage("OtherIcons/DisbandUnit")).size(30f)
                backgroundColor = Color.LIGHT_GRAY
                labelColor = Color.BLACK
            } else if (currentPlayer==civ || UncivGame.Current.viewEntireMapForDebug || currentPlayer.knows(civ)) {
                civGroup.add(ImageGetter.getNationIndicator(civ.nation, 30f))
                backgroundColor = civ.nation.getOuterColor()
                labelColor = civ.nation.getInnerColor()
            } else {
                backgroundColor = Color.DARK_GRAY
                labelText = "???"
            }

            civGroup.background = ImageGetter.getRoundedEdgeTableBackground(backgroundColor)
            val label = labelText.toLabel(labelColor)
            label.setAlignment(Align.center)

            civGroup.add(label).pad(10f)
            civGroup.pack()
            return civGroup
        }
    }
}