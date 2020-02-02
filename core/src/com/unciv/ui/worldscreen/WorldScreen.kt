package com.unciv.ui.worldscreen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.GameSaver
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.map.MapGenerator
import com.unciv.logic.map.MapParameters
import com.unciv.models.Tutorial
import com.unciv.models.UncivSound
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unit.UnitType
import com.unciv.models.translations.tr
import com.unciv.ui.VictoryScreen
import com.unciv.ui.cityscreen.CityScreen
import com.unciv.ui.map.TileGroupMap
import com.unciv.ui.pickerscreens.GreatPersonPickerScreen
import com.unciv.ui.pickerscreens.PolicyPickerScreen
import com.unciv.ui.pickerscreens.TechButton
import com.unciv.ui.pickerscreens.TechPickerScreen
import com.unciv.ui.tilegroups.TileGroup
import com.unciv.ui.tilegroups.TileSetStrings
import com.unciv.ui.trade.DiplomacyScreen
import com.unciv.ui.utils.*
import com.unciv.ui.worldscreen.bottombar.BattleTable
import com.unciv.ui.worldscreen.bottombar.TileInfoTable
import com.unciv.ui.worldscreen.mainmenu.OnlineMultiplayer
import com.unciv.ui.worldscreen.unit.UnitActionsTable
import com.unciv.ui.worldscreen.unit.UnitTable
import kotlin.concurrent.thread

class WorldScreen(val viewingCiv:CivilizationInfo) : CameraStageBaseScreen() {
    val gameInfo = game.gameInfo
    var isPlayersTurn = viewingCiv == gameInfo.currentPlayerCiv // todo this should be updated when passing turns
    var waitingForAutosave = false

    val mapHolder = WorldMapHolder(this, gameInfo.tileMap)
    val minimapWrapper = MinimapHolder(mapHolder)

    private val topBar = WorldScreenTopBar(this)
    val bottomUnitTable = UnitTable(this)
    val bottomTileInfoTable = TileInfoTable(this)
    val battleTable = BattleTable(this)
    val unitActionsTable = UnitActionsTable(this)

    private val techPolicyAndVictoryHolder = Table()
    private val techButtonHolder = Table()
    private val diplomacyButtonWrapper = Table()
    private val nextTurnButton = createNextTurnButton()
    private val tutorialTaskTable=Table().apply { background=ImageGetter.getBackground(ImageGetter.getBlue().lerp(Color.BLACK, 0.5f)) }

    private val notificationsScroll: NotificationsScroll
    var shouldUpdate=false

    private var backButtonListener : InputListener

    init {
        topBar.setPosition(0f, stage.height - topBar.height)
        topBar.width = stage.width

        notificationsScroll = NotificationsScroll(this)
        // notifications are right-aligned, they take up only as much space as necessary.
        notificationsScroll.width = stage.width/2

        minimapWrapper.x = stage.width - minimapWrapper.width

        mapHolder.addTiles()

        techButtonHolder.touchable=Touchable.enabled
        techButtonHolder.onClick(UncivSound.Paper) {
            game.setScreen(TechPickerScreen(viewingCiv))
        }
        techPolicyAndVictoryHolder.add(techButtonHolder)

        // Don't show policies until they become relevant
        if(viewingCiv.policies.adoptedPolicies.isNotEmpty() || viewingCiv.policies.canAdoptPolicy()) {
            val policyScreenButton = Button(skin)
            policyScreenButton.add(ImageGetter.getImage("PolicyIcons/Constitution")).size(30f).pad(15f)
            policyScreenButton.onClick { game.setScreen(PolicyPickerScreen(this)) }
            techPolicyAndVictoryHolder.add(policyScreenButton).pad(10f)
        }

        stage.addActor(mapHolder)
        stage.addActor(minimapWrapper)
        stage.addActor(topBar)
        stage.addActor(nextTurnButton)
        stage.addActor(techPolicyAndVictoryHolder)
        stage.addActor(notificationsScroll)
        stage.addActor(tutorialTaskTable)


        diplomacyButtonWrapper.defaults().pad(5f)
        stage.addActor(diplomacyButtonWrapper)
        stage.addActor(bottomUnitTable)
        stage.addActor(bottomTileInfoTable)
        battleTable.width = stage.width/3
        battleTable.x = stage.width/3
        stage.addActor(battleTable)

        stage.addActor(unitActionsTable)

        createNextTurnButton() // needs civ table to be positioned

        val tileToCenterOn: Vector2 =
                when {
                    viewingCiv.cities.isNotEmpty() -> viewingCiv.getCapital().location
                    viewingCiv.getCivUnits().any() -> viewingCiv.getCivUnits().first().getTile().position
                    else -> Vector2.Zero
                }
        mapHolder.setCenterPosition(tileToCenterOn,true)


        if(gameInfo.gameParameters.isOnlineMultiplayer && !gameInfo.isUpToDate)
            isPlayersTurn = false // until we're up to date, don't let the player do anything
        if(gameInfo.gameParameters.isOnlineMultiplayer && !isPlayersTurn) {
            stage.addAction(Actions.forever(Actions.sequence(Actions.run {
                loadLatestMultiplayerState()
            }, Actions.delay(10f)))) // delay is in seconds
        }

        tutorialController.allTutorialsShowedCallback = {
            shouldUpdate = true
        }

        backButtonListener = onBackButtonClicked { exitGamePrompt() }

        // don't run update() directly, because the UncivGame.worldScreen should be set so that the city buttons and tile groups
        //  know what the viewing civ is.
        shouldUpdate = true
    }

    private fun loadLatestMultiplayerState(){
        val loadingGamePopup = Popup(this)
        loadingGamePopup.add("Loading latest game state...")
        loadingGamePopup.open()
        thread(name="MultiplayerLoad") {
            try {
                val latestGame = OnlineMultiplayer().tryDownloadGame(gameInfo.gameId)
                if(gameInfo.isUpToDate && gameInfo.currentPlayer==latestGame.currentPlayer) { // we were trying to download this to see when it's our turn...nothing changed
                    loadingGamePopup.close()
                    return@thread
                }
                latestGame.isUpToDate=true
                // Since we're making a screen this needs to run from the man thread which has a GL context
                Gdx.app.postRunnable { game.loadGame(latestGame) }

            } catch (ex: Exception) {
                loadingGamePopup.close()
                val couldntDownloadLatestGame = Popup(this)
                couldntDownloadLatestGame.addGoodSizedLabel("Couldn't download the latest game state!").row()
                couldntDownloadLatestGame.addCloseButton()
                couldntDownloadLatestGame.addAction(Actions.delay(5f, Actions.run { couldntDownloadLatestGame.close() }))
                couldntDownloadLatestGame.open()
            }
        }
    }

    // This is private so that we will set the shouldUpdate to true instead.
    // That way, not only do we save a lot of unnecessary updates, we also ensure that all updates are called from the main GL thread
    // and we don't get any silly concurrency problems!
    private fun update() {

        displayTutorialsOnUpdate()

        bottomUnitTable.update()
        bottomTileInfoTable.updateTileTable(mapHolder.selectedTile!!)
        bottomTileInfoTable.x = stage.width - bottomTileInfoTable.width
        bottomTileInfoTable.y = if (UncivGame.Current.settings.showMinimap) minimapWrapper.height else 0f
        battleTable.update()

        tutorialTaskTable.clear()
        val tutorialTask = getCurrentTutorialTask()
        if (tutorialTask == "" || !game.settings.showTutorials || viewingCiv.isDefeated()) {
            tutorialTaskTable.isVisible = false
        } else {
            tutorialTaskTable.isVisible = true
            tutorialTaskTable.add(tutorialTask.toLabel()
                    .apply { setAlignment(Align.center) }).pad(10f)
            tutorialTaskTable.pack()
            tutorialTaskTable.centerX(stage)
            tutorialTaskTable.y = topBar.y - tutorialTaskTable.height
        }

        minimapWrapper.update(viewingCiv)
        unitActionsTable.update(bottomUnitTable.selectedUnit)
        unitActionsTable.y = bottomUnitTable.height

        // if we use the clone, then when we update viewable tiles
        // it doesn't update the explored tiles of the civ... need to think about that harder
        // it causes a bug when we move a unit to an unexplored tile (for instance a cavalry unit which can move far)
        mapHolder.updateTiles(viewingCiv)

        topBar.update(viewingCiv)

        updateTechButton()
        techPolicyAndVictoryHolder.pack()
        techPolicyAndVictoryHolder.setPosition(10f, topBar.y - techPolicyAndVictoryHolder.height - 5f)
        updateDiplomacyButton(viewingCiv)

        if (!hasOpenPopups() && isPlayersTurn) {
            when {
                !gameInfo.oneMoreTurnMode && viewingCiv.isDefeated() -> game.setScreen(VictoryScreen())
                !gameInfo.oneMoreTurnMode && gameInfo.civilizations.any { it.victoryManager.hasWon() } -> game.setScreen(VictoryScreen())
                viewingCiv.policies.freePolicies > 0 && viewingCiv.policies.canAdoptPolicy() -> game.setScreen(PolicyPickerScreen(this))
                viewingCiv.greatPeople.freeGreatPeople > 0 -> game.setScreen(GreatPersonPickerScreen(viewingCiv))
                viewingCiv.popupAlerts.any() -> AlertPopup(this, viewingCiv.popupAlerts.first()).open()
                viewingCiv.tradeRequests.isNotEmpty() -> TradePopup(this).open()
            }
        }
        updateNextTurnButton(hasOpenPopups()) // This must be before the notifications update, since its position is based on it
        notificationsScroll.update(viewingCiv.notifications)
        notificationsScroll.setPosition(stage.width - notificationsScroll.width - 5f,
                nextTurnButton.y - notificationsScroll.height - 5f)
    }

    private fun getCurrentTutorialTask(): String {
        val completedTasks = game.settings.tutorialTasksCompleted
        if(!completedTasks.contains("Move unit"))
            return "Move a unit!\nClick on a unit > Click on a destination > Click the arrow popup"
        if(!completedTasks.contains("Found city"))
            return "Found a city!\nSelect the Settler (flag unit) > Click on 'Found city' (bottom-left corner)"
        if(!completedTasks.contains("Enter city screen"))
            return "Enter the city screen!\nClick the city button twice"
        if(!completedTasks.contains("Pick technology"))
            return "Pick a technology to research!\nClick on the tech button (greenish, top left) > " +
                    "\n select technology > click 'Research' (bottom right)"
        if(!completedTasks.contains("Pick construction"))
            return "Pick a construction!\nEnter city screen > Click on a unit or building (bottom left side) >" +
                    " \n click 'add to queue'"
        if(!completedTasks.contains("Pass a turn"))
            return "Pass a turn!\nCycle through units with 'Next unit' > Click 'Next turn'"
        if(!completedTasks.contains("Reassign worked tiles"))
            return "Reassign worked tiles!\nEnter city screen > click the assigned (green) tile to unassign > " +
                    "\n click an unassigned tile to assign population"
        if(!completedTasks.contains("Meet another civilization"))
            return "Meet another civilization!\nExplore the map until you encounter another civilization!"
        if(!completedTasks.contains("Open the options table"))
            return "Open the options table!\nClick the menu button (top left) > click 'Options'"
        if(!completedTasks.contains("Construct an improvement"))
            return "Construct an improvement!\nConstruct a Worker unit > Move to a Plains or Grassland tile > " +
                    "\n Click 'Create improvement' (above the unit table, bottom left)" +
                    "\n > Choose the farm > \n Leave the worker there until it's finished"
        if(!completedTasks.contains("Create a trade route")
                && viewingCiv.citiesConnectedToCapital.any { it.civInfo==viewingCiv })
            game.settings.addCompletedTutorialTask("Create a trade route")
        if(viewingCiv.cities.size>1 && !completedTasks.contains("Create a trade route"))
            return "Create a trade route!\nConstruct roads between your capital and another city" +
                    "\nOr, automate your worker and let him get to that eventually"
        if(viewingCiv.isAtWar() && !completedTasks.contains("Conquer a city"))
            return "Conquer a city!\nBring an enemy city down to low health > " +
                    "\nEnter the city with a melee unit"
        if(viewingCiv.getCivUnits().any { it.type.isAirUnit() } && !completedTasks.contains("Move an air unit"))
            return "Move an air unit!\nSelect an air unit > select another city within range > " +
                    "\nMove the unit to the other city"
        if(!completedTasks.contains("See your stats breakdown"))
            return "See your stats breakdown!\nEnter the Overview screen (top right corner) >" +
                    "\nClick on 'Stats'"

        return ""
    }

    private fun displayTutorialsOnUpdate() {
        UncivGame.Current.crashController.showDialogIfNeeded()

        displayTutorial(Tutorial.Introduction)
        if (!UncivGame.Current.settings.tutorialsShown.contains("_EnemyCityNeedsConqueringWithMeleeUnit")) {
            for (enemyCity in viewingCiv.diplomacy.values.filter { it.diplomaticStatus == DiplomaticStatus.War }
                    .map { it.otherCiv() }.flatMap { it.cities }) {
                if (enemyCity.health == 1 && enemyCity.getCenterTile().getTilesInDistance(2)
                                .any { it.getUnits().any { unit -> unit.civInfo == viewingCiv} })
                    displayTutorial(Tutorial.EnemyCityNeedsConqueringWithMeleeUnit)
            }
        }
        if(viewingCiv.cities.any { it.hasJustBeenConquered })
            displayTutorial(Tutorial.AfterConquering)

        if (gameInfo.getCurrentPlayerCivilization().getCivUnits().any { it.health < 100 })
            displayTutorial(Tutorial.InjuredUnits)

        if (gameInfo.getCurrentPlayerCivilization().getCivUnits().any { it.name == Constants.worker })
            displayTutorial(Tutorial.Workers)
    }

    private fun updateDiplomacyButton(civInfo: CivilizationInfo) {
        diplomacyButtonWrapper.clear()
        if(!civInfo.isDefeated() && civInfo.getKnownCivs()
                        .filterNot {  it==viewingCiv || it.isBarbarian() }
                        .any()) {
            displayTutorial(Tutorial.OtherCivEncountered)
            val btn = TextButton("Diplomacy".tr(), skin)
            btn.onClick { UncivGame.Current.setScreen(DiplomacyScreen(viewingCiv)) }
            btn.label.setFontSize(30)
            btn.labelCell.pad(10f)
            diplomacyButtonWrapper.add(btn)
        }
        diplomacyButtonWrapper.pack()
        diplomacyButtonWrapper.y = techPolicyAndVictoryHolder.y - 20 - diplomacyButtonWrapper.height
    }

    private fun updateTechButton() {
        techButtonHolder.isVisible = viewingCiv.cities.isNotEmpty()
        techButtonHolder.clearChildren()

        val researchableTechs = viewingCiv.gameInfo.ruleSet.technologies.values.filter { !viewingCiv.tech.isResearched(it.name) && viewingCiv.tech.canBeResearched(it.name) }
        if (viewingCiv.tech.currentTechnology() == null && researchableTechs.isEmpty())
            viewingCiv.tech.techsToResearch.add(Constants.futureTech)

        if (viewingCiv.tech.currentTechnology() == null) {
            val buttonPic = Table()
            buttonPic.background = ImageGetter.getRoundedEdgeTableBackground(colorFromRGB(7, 46, 43))
            buttonPic.defaults().pad(20f)
            buttonPic.add("{Pick a tech}!".toLabel(Color.WHITE,30))
            techButtonHolder.add(buttonPic)
        }
        else {
            val currentTech = viewingCiv.tech.currentTechnologyName()!!
            val innerButton = TechButton(currentTech,viewingCiv.tech)
            innerButton.color = colorFromRGB(7, 46, 43)
            techButtonHolder.add(innerButton)
            val turnsToTech = viewingCiv.tech.turnsToTech(currentTech)
            innerButton.text.setText(currentTech.tr() + "\r\n" + turnsToTech
                    + (if(turnsToTech>1) " {turns}".tr() else " {turn}".tr()))
        }

        techButtonHolder.pack() //setSize(techButtonHolder.prefWidth, techButtonHolder.prefHeight)
    }

    private fun createNextTurnButton(): TextButton {

        val nextTurnButton = TextButton("", skin) // text is set in update()
        nextTurnButton.label.setFontSize(30)
        nextTurnButton.labelCell.pad(10f)

        nextTurnButton.onClick {
            // cycle through units not yet done
            if (viewingCiv.shouldGoToDueUnit()) {
                val nextDueUnit = viewingCiv.getNextDueUnit()
                if(nextDueUnit!=null) {
                    mapHolder.setCenterPosition(nextDueUnit.currentTile.position, false, false)
                    bottomUnitTable.selectedCity = null
                    bottomUnitTable.selectedUnit = nextDueUnit
                    shouldUpdate=true
                }
                return@onClick
            }

            val cityWithNoProductionSet = viewingCiv.cities
                    .firstOrNull{it.cityConstructions.currentConstruction==""}
            if(cityWithNoProductionSet!=null){
                game.setScreen(CityScreen(cityWithNoProductionSet))
                return@onClick
            }

            if (viewingCiv.shouldOpenTechPicker()) {
                game.setScreen(TechPickerScreen(viewingCiv.tech.freeTechs != 0, viewingCiv))
                return@onClick
            } else if (viewingCiv.policies.shouldOpenPolicyPicker) {
                game.setScreen(PolicyPickerScreen(this))
                viewingCiv.policies.shouldOpenPolicyPicker = false
                return@onClick
            }

            game.settings.addCompletedTutorialTask("Pass a turn")
            nextTurn() // If none of the above
        }

        return nextTurnButton
    }

    private fun nextTurn() {
        isPlayersTurn = false
        shouldUpdate = true


        thread(name="NextTurn") { // on a separate thread so the user can explore their world while we're passing the turn
            val gameInfoClone = gameInfo.clone()
            gameInfoClone.setTransients()
            try {
                gameInfoClone.nextTurn()

                if(gameInfo.gameParameters.isOnlineMultiplayer) {
                    try {
                        OnlineMultiplayer().tryUploadGame(gameInfoClone)
                    } catch (ex: Exception) {
                        val cantUploadNewGamePopup = Popup(this)
                        cantUploadNewGamePopup.addGoodSizedLabel("Could not upload game!").row()
                        cantUploadNewGamePopup.addCloseButton()
                        cantUploadNewGamePopup.open()
                        isPlayersTurn = true // Since we couldn't push the new game clone, then it's like we never clicked the "next turn" button
                        shouldUpdate = true
                        return@thread
                    }
                }
            } catch (ex: Exception) {
                UncivGame.Current.crashController.crashOccurred()
                throw ex
            }

            game.gameInfo = gameInfoClone

            val shouldAutoSave = gameInfoClone.turns % game.settings.turnsBetweenAutosaves == 0

            // create a new worldscreen to show the new stuff we've changed, and switch out the current screen.
            // do this on main thread - it's the only one that has a GL context to create images from
            Gdx.app.postRunnable {

                fun createNewWorldScreen(){
                    val newWorldScreen = WorldScreen(gameInfoClone.getPlayerToViewAs())
                    newWorldScreen.mapHolder.scrollX = mapHolder.scrollX
                    newWorldScreen.mapHolder.scrollY = mapHolder.scrollY
                    newWorldScreen.mapHolder.scaleX = mapHolder.scaleX
                    newWorldScreen.mapHolder.scaleY = mapHolder.scaleY
                    newWorldScreen.mapHolder.updateVisualScroll()
                    game.worldScreen = newWorldScreen
                    game.setWorldScreen()
                }

                if (gameInfoClone.currentPlayerCiv.civName != viewingCiv.civName
                        && !gameInfoClone.gameParameters.isOnlineMultiplayer)
                    UncivGame.Current.setScreen(PlayerReadyScreen(gameInfoClone.getCurrentPlayerCivilization()))
                else {
                    createNewWorldScreen()
                }

                if(shouldAutoSave) {
                    val newWorldScreen = game.worldScreen
                    newWorldScreen.waitingForAutosave = true
                    newWorldScreen.shouldUpdate = true
                    GameSaver().autoSave(gameInfoClone) {
                        // only enable the user to next turn once we've saved the current one
                        newWorldScreen.waitingForAutosave = false
                        newWorldScreen.shouldUpdate = true
                    }
                }
            }
        }
    }

    private fun updateNextTurnButton(isSomethingOpen: Boolean) {
        val text = when {
            !isPlayersTurn -> "Waiting for other players..."
            viewingCiv.shouldGoToDueUnit() -> "Next unit"
            viewingCiv.cities.any { it.cityConstructions.currentConstruction == "" } -> "Pick construction"
            viewingCiv.shouldOpenTechPicker() -> "Pick a tech"
            viewingCiv.policies.shouldOpenPolicyPicker -> "Pick a policy"
            else -> "Next turn"
        }
        nextTurnButton.setText(text.tr())
        nextTurnButton.color = if (text == "Next turn") Color.WHITE else Color.GRAY
        nextTurnButton.pack()
        if (isSomethingOpen || !isPlayersTurn || waitingForAutosave) nextTurnButton.disable()
        else nextTurnButton.enable()
        nextTurnButton.setPosition(stage.width - nextTurnButton.width - 10f, topBar.y - nextTurnButton.height - 10f)
    }

    override fun resize(width: Int, height: Int) {
        if (stage.viewport.screenWidth != width || stage.viewport.screenHeight != height) {
            super.resize(width, height)
            game.worldScreen = WorldScreen(viewingCiv) // start over.
            game.setWorldScreen()
        }
    }


    override fun render(delta: Float) {
        //  This is so that updates happen in the MAIN THREAD, where there is a GL Context,
        //    otherwise images will not load properly!
        if (shouldUpdate) {
            shouldUpdate = false

            update()
            showTutorialsOnNextTurn()
        }

        super.render(delta)
    }

    private fun showTutorialsOnNextTurn(){
        val shownTutorials = UncivGame.Current.settings.tutorialsShown
        displayTutorial(Tutorial.SlowStart)
        if("_BarbarianEncountered" !in shownTutorials
                && viewingCiv.viewableTiles.any { it.getUnits().any { unit -> unit.civInfo.isBarbarian() } })
            displayTutorial(Tutorial.BarbarianEncountered)
        if(viewingCiv.cities.size > 2) displayTutorial(Tutorial.RoadsAndRailroads)
        if(viewingCiv.getHappiness() < 5) displayTutorial(Tutorial.Happiness)
        if(viewingCiv.getHappiness() < 0) displayTutorial(Tutorial.Unhappiness)
        if(viewingCiv.goldenAges.isGoldenAge()) displayTutorial(Tutorial.GoldenAge)
        if(gameInfo.turns >= 50 && UncivGame.Current.settings.checkForDueUnits) displayTutorial(Tutorial.IdleUnits)
        if(gameInfo.turns >= 100) displayTutorial(Tutorial.ContactMe)
        val resources = viewingCiv.getCivResources()
        if(resources.any { it.resource.resourceType==ResourceType.Luxury }) displayTutorial(Tutorial.LuxuryResource)
        if(resources.any { it.resource.resourceType==ResourceType.Strategic}) displayTutorial(Tutorial.StrategicResource)
        if("Enemy_City" !in shownTutorials
                && viewingCiv.getKnownCivs().filter { viewingCiv.isAtWarWith(it) }
                        .flatMap { it.cities }.any { viewingCiv.exploredTiles.contains(it.location) })
            displayTutorial(Tutorial.EnemyCity)
        if(viewingCiv.containsBuildingUnique("Enables construction of Spaceship parts"))
            displayTutorial(Tutorial.ApolloProgram)
        if(viewingCiv.getCivUnits().any { it.type == UnitType.Siege })
            displayTutorial(Tutorial.SiegeUnits)
        if(viewingCiv.tech.getTechUniques().contains("Enables embarkation for land units"))
            displayTutorial(Tutorial.Embarking)
    }

    private fun exitGamePrompt() {

        // don't show a dialog, if it can't exit the game
        if (game.exitEvent == null)
            return

        // remove current listener for the "BACK" button to avoid showing the dialog twice
        stage.removeListener( backButtonListener )

        var promptWindow = Popup(this)
        promptWindow.addGoodSizedLabel("Do you want to exit the game?".tr())
        promptWindow.row()
        promptWindow.addButton("Yes"){game.exitEvent?.invoke()}
        promptWindow.addButton("No") {
            // restore the listener back
            stage.addListener(backButtonListener)
            promptWindow.close()
        }
        // show the dialog
        promptWindow.open()
    }
}



class TotemWorldScreen : CameraStageBaseScreen(){
    val tileMap = MapGenerator().generateMap(MapParameters(),RulesetCache.getBaseRuleset())
    val tileSetStrings = TileSetStrings()
    val tileMapHolder = TileGroupMap(tileMap.values.map { TileGroup(it,tileSetStrings) },500f)
    val newCiv = CivilizationInfo("Babylon")
    init{
        newCiv.playerType=PlayerType.Human
        val gameInfo = GameInfo()
        gameInfo.tileMap = tileMap
        gameInfo.civilizations.add(newCiv)
        gameInfo.setTransients()
        ImageGetter.ruleset = RulesetCache.getBaseRuleset()
        val scrollPane = ScrollPane(tileMapHolder)
        scrollPane.setSize(stage.width,stage.height)
        scrollPane.center(stage)
        scrollPane.scrollPercentX=0.5f
        scrollPane.scrollPercentY=0.5f
        stage.addActor(scrollPane)
        newCiv.placeUnitNearTile(Vector2.Zero, Constants.worker)
        update()
    }

    fun update(){
        for(tileGroup in tileMapHolder.tileGroups)
            tileGroup.update(newCiv)
    }
}