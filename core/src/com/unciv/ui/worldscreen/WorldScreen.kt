package com.unciv.ui.worldscreen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.actions.RepeatAction
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.logic.GameSaver
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.replay.ActionMapper
import com.unciv.models.Tutorial
import com.unciv.models.UncivSound
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unit.UnitType
import com.unciv.models.translations.tr
import com.unciv.ui.cityscreen.CityScreen
import com.unciv.ui.pickerscreens.GreatPersonPickerScreen
import com.unciv.ui.pickerscreens.PolicyPickerScreen
import com.unciv.ui.pickerscreens.TechButton
import com.unciv.ui.pickerscreens.TechPickerScreen
import com.unciv.ui.trade.DiplomacyScreen
import com.unciv.ui.utils.*
import com.unciv.ui.victoryscreen.VictoryScreen
import com.unciv.ui.worldscreen.bottombar.BattleTable
import com.unciv.ui.worldscreen.bottombar.TileInfoTable
import com.unciv.ui.worldscreen.mainmenu.OnlineMultiplayer
import com.unciv.ui.worldscreen.unit.UnitActionsTable
import com.unciv.ui.worldscreen.unit.UnitTable
import java.util.*
import kotlin.concurrent.thread

class WorldScreen(val viewingCiv:CivilizationInfo) : CameraStageBaseScreen() {
    val gameInfo = game.gameInfo
    val replayMode = gameInfo.replayMode
    var isPlayersTurn = viewingCiv == gameInfo.currentPlayerCiv // todo this should be updated when passing turns
    var selectedCiv = viewingCiv // Selected civilization, used only in spectator mode
    val canChangeState = isPlayersTurn && !gameInfo.currentPlayerCiv.isSpectator() && !replayMode
    private var waitingForAutosave = false

    val mapHolder = WorldMapHolder(this, gameInfo.tileMap)
    private val minimapWrapper = MinimapHolder(mapHolder)

    private val topBar = WorldScreenTopBar(this)
    val bottomUnitTable = UnitTable(this)
    private val bottomTileInfoTable = TileInfoTable(viewingCiv)
    private val battleTable = BattleTable(this)
    private val unitActionsTable = UnitActionsTable(this)

    private val techPolicyAndVictoryHolder = Table()
    private val techButtonHolder = Table()
    private val diplomacyButtonWrapper = Table()
    private val nextTurnButton = createNextTurnButton()
    private var nextTurnAction:()->Unit= {}
    private val tutorialTaskTable = Table().apply { background=ImageGetter.getBackground(ImageGetter.getBlue().lerp(Color.BLACK, 0.5f)) }

    private val notificationsScroll: NotificationsScroll
    var shouldUpdate = false

    private var backButtonListener : InputListener

    // An initialized val always turned out to illegally be null...
    lateinit var keyPressDispatcher: HashMap<Char,(() -> Unit)>

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
        stage.scrollFocus = mapHolder
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

        // still a zombie: createNextTurnButton() // needs civ table to be positioned

        val tileToCenterOn: Vector2 =
                when {
                    viewingCiv.cities.isNotEmpty() -> viewingCiv.getCapital().location
                    viewingCiv.getCivUnits().any() -> viewingCiv.getCivUnits().first().getTile().position
                    else -> Vector2.Zero
                }

        // Don't select unit and change selectedCiv when centering as spectator
        mapHolder.setCenterPosition(tileToCenterOn,true, !viewingCiv.isSpectator())


        if(gameInfo.gameParameters.isOnlineMultiplayer && !gameInfo.isUpToDate)
            isPlayersTurn = false // until we're up to date, don't let the player do anything
        
        if(gameInfo.gameParameters.isOnlineMultiplayer && !isPlayersTurn) {
            // restart the timer
            stopMultiPlayerRefresher()
            // isDaemon = true, in order to not block the app closing
            multiPlayerRefresher = Timer("multiPlayerRefresh", true).apply {
                schedule(object : TimerTask() { //todo maybe not use timer for web request, from timer docs "Timer tasks should complete quickly."
                    override fun run() { loadLatestMultiplayerState() }
                }, 0, 10000) // 10 seconds
            }
        }

        tutorialController.allTutorialsShowedCallback = {
            shouldUpdate = true
        }

        backButtonListener = onBackButtonClicked { backButtonAndESCHandler() }

        addKeyboardListener() // for map panning by W,S,A,D

        // don't run update() directly, because the UncivGame.worldScreen should be set so that the city buttons and tile groups
        //  know what the viewing civ is.
        shouldUpdate = true
    }

    private fun stopMultiPlayerRefresher() {
        if (multiPlayerRefresher != null) {
            multiPlayerRefresher?.cancel()
            multiPlayerRefresher?.purge()
        }
    }

    private fun cleanupKeyDispatcher() {
        val delKeys = keyPressDispatcher.keys.filter { it!=' ' && it!='n' }
        delKeys.forEach { keyPressDispatcher.remove(it) }
    }

    private fun addKeyboardListener() {
        stage.addListener(
            object : InputListener() {
                private val pressedKeys = mutableSetOf<Int>()
                private var infiniteAction : RepeatAction? = null
                private val amountToMove = 30 / mapHolder.scaleX
                private val ALLOWED_KEYS = setOf(Input.Keys.W,Input.Keys.S,Input.Keys.A,Input.Keys.D)

                override fun keyDown(event: InputEvent?, keycode: Int): Boolean {
                    if (keycode !in ALLOWED_KEYS) return false

                    pressedKeys.add(keycode)
                    if (infiniteAction == null) {
                        // create a copy of the action, because removeAction() will destroy this instance
                        infiniteAction = Actions.forever(Actions.delay(0.05f, Actions.run { whileKeyPressedLoop() }))
                        mapHolder.addAction(infiniteAction)
                    }
                    return true
                }

                fun whileKeyPressedLoop() {
                    for (keycode in pressedKeys) {
                        when (keycode) {
                            Input.Keys.W -> mapHolder.scrollY -= amountToMove
                            Input.Keys.S -> mapHolder.scrollY += amountToMove
                            Input.Keys.A -> mapHolder.scrollX -= amountToMove
                            Input.Keys.D -> mapHolder.scrollX += amountToMove
                        }
                    }
                    mapHolder.updateVisualScroll()
                }

                override fun keyUp(event: InputEvent?, keycode: Int): Boolean {
                    if (keycode !in ALLOWED_KEYS) return false

                    pressedKeys.remove(keycode)
                    if (infiniteAction != null && pressedKeys.isEmpty()) {
                        // stop the loop otherwise it keeps going even after removal
                        infiniteAction?.finish()
                        // remove and nil the action
                        mapHolder.removeAction(infiniteAction)
                        infiniteAction = null
                    }
                    return true
                }

                override fun keyTyped(event: InputEvent?, character: Char): Boolean {
                    if (character.toLowerCase() in keyPressDispatcher && !hasOpenPopups()) {
                        //try-catch mainly for debugging. Breakpoints in the vicinity can make the event fire twice in rapid succession, second time the context can be invalid
                        try {
                            keyPressDispatcher[character.toLowerCase()]?.invoke()
                        } catch (ex: Exception) {}
                        return true
                    }
                    return super.keyTyped(event, character)
                }
            }
        )
    }

    private fun loadLatestMultiplayerState(){

        // Since we're on a background thread, all the UI calls in this func need to run from the
        // main thread which has a GL context
        val loadingGamePopup = Popup(this)
        Gdx.app.postRunnable {
            loadingGamePopup.add("Loading latest game state...".tr())
            loadingGamePopup.open()
        }

        try {
            val latestGame = OnlineMultiplayer().tryDownloadGame(gameInfo.gameId)

            // if we find it still isn't player's turn...nothing changed
            if(gameInfo.isUpToDate && gameInfo.currentPlayer==latestGame.currentPlayer) {
                Gdx.app.postRunnable { loadingGamePopup.close() }
                return
            }
            else{ //else we found it is the player's turn again, turn off polling and load turn
                stopMultiPlayerRefresher()

                latestGame.isUpToDate=true
                Gdx.app.postRunnable { game.loadGame(latestGame) }
            }

        } catch (ex: Exception) {
            val couldntDownloadLatestGame = Popup(this)
            couldntDownloadLatestGame.addGoodSizedLabel("Couldn't download the latest game state!").row()
            couldntDownloadLatestGame.addCloseButton()
            couldntDownloadLatestGame.addAction(Actions.delay(5f, Actions.run { couldntDownloadLatestGame.close() }))

            Gdx.app.postRunnable {
                loadingGamePopup.close()
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
        bottomTileInfoTable.updateTileTable(mapHolder.selectedTile)
        bottomTileInfoTable.x = stage.width - bottomTileInfoTable.width
        bottomTileInfoTable.y = if (game.settings.showMinimap) minimapWrapper.height else 0f
        battleTable.update()

        updateSelectedCiv()

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
        cleanupKeyDispatcher()
        unitActionsTable.update(bottomUnitTable.selectedUnit)
        unitActionsTable.y = bottomUnitTable.height

        // if we use the clone, then when we update viewable tiles
        // it doesn't update the explored tiles of the civ... need to think about that harder
        // it causes a bug when we move a unit to an unexplored tile (for instance a cavalry unit which can move far)
        mapHolder.updateTiles(viewingCiv)

        if (viewingCiv.isSpectator())
            topBar.update(selectedCiv)
        else
            topBar.update(viewingCiv)

        updateTechButton()
        techPolicyAndVictoryHolder.pack()
        techPolicyAndVictoryHolder.setPosition(10f, topBar.y - techPolicyAndVictoryHolder.height - 5f)
        updateDiplomacyButton(viewingCiv)

        if (!hasOpenPopups() && isPlayersTurn) {
            when {
                !gameInfo.oneMoreTurnMode
                        && (viewingCiv.isDefeated() || gameInfo.civilizations.any { it.victoryManager.hasWon() }) -> {
                    game.setScreen(VictoryScreen(this))
                    if (game.settings.autoSaveReplay && game.replayDebugSwitch) {
                        gameInfo.replay!!.finalState = gameInfo
                        GameSaver.saveReplay(gameInfo.replay!!, "LastReplay")
                    }
                }

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
                && viewingCiv.citiesConnectedToCapitalToMediums.any { it.key.civInfo==viewingCiv })
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
        game.crashController.showDialogIfNeeded()

        displayTutorial(Tutorial.Introduction)

        displayTutorial(Tutorial.EnemyCityNeedsConqueringWithMeleeUnit) {
            // diplomacy is a HashMap, cities a List - so sequences should help
            // .flatMap { it.getUnits().asSequence() }  is not a good idea because getUnits constructs an ArrayList dynamically
            viewingCiv.diplomacy.values.asSequence()
                    .filter { it.diplomaticStatus == DiplomaticStatus.War }
                    .map { it.otherCiv() }
                    // we're now lazily enumerating over CivilizationInfo's we're at war with
                    .flatMap { it.cities.asSequence() }
                    // ... all *their* cities
                    .filter { it.health == 1 }
                    // ... those ripe for conquering
                    .flatMap { it.getCenterTile().getTilesInDistance(2).asSequence() }
                    // ... all tiles around those in range of an average melee unit
                    // -> and now we look for a unit that could do the conquering because it's ours
                    //    no matter whether civilian, air or ranged, tell user he needs melee
                    .any { it.getUnits().any { unit -> unit.civInfo == viewingCiv} }
        }
        displayTutorial(Tutorial.AfterConquering) { viewingCiv.cities.any{it.hasJustBeenConquered} }

        displayTutorial(Tutorial.InjuredUnits) { gameInfo.getCurrentPlayerCivilization().getCivUnits().any { it.health < 100 } }

        displayTutorial(Tutorial.Workers) { gameInfo.getCurrentPlayerCivilization().getCivUnits().any { it.hasUnique(Constants.workerUnique) } }
    }

    private fun updateDiplomacyButton(civInfo: CivilizationInfo) {
        diplomacyButtonWrapper.clear()
        if(!civInfo.isDefeated() && !civInfo.isSpectator() && civInfo.getKnownCivs()
                        .filterNot {  it==viewingCiv || it.isBarbarian() }
                        .any()) {
            displayTutorial(Tutorial.OtherCivEncountered)
            val btn = "Diplomacy".toTextButton()
            btn.onClick { game.setScreen(DiplomacyScreen(viewingCiv)) }
            btn.label.setFontSize(30)
            btn.labelCell.pad(10f)
            diplomacyButtonWrapper.add(btn)
        }
        diplomacyButtonWrapper.pack()
        diplomacyButtonWrapper.y = techPolicyAndVictoryHolder.y - 20 - diplomacyButtonWrapper.height
    }

    private fun updateTechButton() {
        if(gameInfo.ruleSet.technologies.isEmpty()) return
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

    private fun updateSelectedCiv() {
        if (bottomUnitTable.selectedUnit != null)
            selectedCiv = bottomUnitTable.selectedUnit!!.civInfo
        else if (bottomUnitTable.selectedCity != null)
            selectedCiv = bottomUnitTable.selectedCity!!.civInfo
        else viewingCiv
    }

    private fun createNextTurnButton(): TextButton {

        val nextTurnButton = TextButton("", skin) // text is set in update()
        nextTurnButton.label.setFontSize(30)
        nextTurnButton.labelCell.pad(10f)
        val nextTurnActionWrapped = { nextTurnAction() }
        nextTurnButton.onClick (nextTurnActionWrapped)
        if (!::keyPressDispatcher.isInitialized) keyPressDispatcher = hashMapOf()
        keyPressDispatcher[' '] = nextTurnActionWrapped
        keyPressDispatcher['n'] = nextTurnActionWrapped

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
                        Gdx.app.postRunnable { // Since we're changing the UI, that should be done on the main thread
                            val cantUploadNewGamePopup = Popup(this)
                            cantUploadNewGamePopup.addGoodSizedLabel("Could not upload game!").row()
                            cantUploadNewGamePopup.addCloseButton()
                            cantUploadNewGamePopup.open()
                        }
                        isPlayersTurn = true // Since we couldn't push the new game clone, then it's like we never clicked the "next turn" button
                        shouldUpdate = true
                        return@thread
                    }
                }
            } catch (ex: Exception) {
                Gdx.app.postRunnable { game.crashController.crashOccurred() }
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
                    newWorldScreen.selectedCiv = gameInfoClone.getCivilization(selectedCiv.civName)
                    game.worldScreen = newWorldScreen
                    game.setWorldScreen()
                }

                if (gameInfoClone.currentPlayerCiv.civName != viewingCiv.civName
                        && !gameInfoClone.gameParameters.isOnlineMultiplayer)
                    game.setScreen(PlayerReadyScreen(gameInfoClone.getCurrentPlayerCivilization()))
                else {
                    createNewWorldScreen()
                }

                if(shouldAutoSave) {
                    val newWorldScreen = game.worldScreen
                    newWorldScreen.waitingForAutosave = true
                    newWorldScreen.shouldUpdate = true
                    GameSaver.autoSave(gameInfoClone) {
                        // only enable the user to next turn once we've saved the current one
                        newWorldScreen.waitingForAutosave = false
                        newWorldScreen.shouldUpdate = true
                    }
                }
            }
        }
    }

    private class NextTurnAction(val text:String, val color:Color, val action:()->Unit)

    private fun updateNextTurnButton(isSomethingOpen: Boolean) {
        val action:NextTurnAction = getNextTurnAction()
        nextTurnAction = action.action

        nextTurnButton.setText(action.text.tr())
        nextTurnButton.label.color = action.color
        nextTurnButton.pack()
        nextTurnButton.isEnabled = !isSomethingOpen && isPlayersTurn && !waitingForAutosave
        nextTurnButton.setPosition(stage.width - nextTurnButton.width - 10f, topBar.y - nextTurnButton.height - 10f)
    }
    fun enableNextTurnButtonAfterOptions() {
        nextTurnButton.isEnabled = isPlayersTurn && !waitingForAutosave
    }

    private fun getNextTurnAction(): NextTurnAction {
        return when {
            replayMode && gameInfo.oneMoreTurnMode -> NextTurnAction("Replay finished", Color.GRAY) {}

            !canChangeState -> NextTurnAction("Next turn", Color.WHITE) { nextTurn() }

            !isPlayersTurn -> NextTurnAction("Waiting for other players...", Color.GRAY) {}

            viewingCiv.shouldGoToDueUnit() ->
                NextTurnAction("Next unit", Color.LIGHT_GRAY) {
                    val nextDueUnit = viewingCiv.getNextDueUnit()
                    if (nextDueUnit != null) {
                        mapHolder.setCenterPosition(nextDueUnit.currentTile.position, false, false)
                        bottomUnitTable.selectedCity = null
                        bottomUnitTable.selectedUnit = nextDueUnit
                        shouldUpdate = true
                    }
                }

            viewingCiv.cities.any { it.cityConstructions.currentConstructionFromQueue == "" } ->
                NextTurnAction("Pick construction", Color.CORAL) {
                    val cityWithNoProductionSet = viewingCiv.cities
                            .firstOrNull { it.cityConstructions.currentConstructionFromQueue == "" }
                    if (cityWithNoProductionSet != null) game.setScreen(CityScreen(cityWithNoProductionSet))
                }

            viewingCiv.shouldOpenTechPicker() ->
                NextTurnAction("Pick a tech", Color.SKY) {
                    game.setScreen(TechPickerScreen(viewingCiv.tech.freeTechs != 0, viewingCiv))
                }

            viewingCiv.policies.shouldOpenPolicyPicker || (viewingCiv.policies.freePolicies > 0 && viewingCiv.policies.canAdoptPolicy())  ->
                NextTurnAction("Pick a policy", Color.VIOLET) {
                    game.setScreen(PolicyPickerScreen(this))
                    viewingCiv.policies.shouldOpenPolicyPicker = false
                }

            else ->
                NextTurnAction("Next turn", Color.WHITE) {
                    game.settings.addCompletedTutorialTask("Pass a turn")
                    nextTurn()
                }
        }
    }

    override fun resize(width: Int, height: Int) {
        if (stage.viewport.screenWidth != width || stage.viewport.screenHeight != height) {
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
        if (!game.settings.showTutorials) return
        displayTutorial(Tutorial.SlowStart)
        displayTutorial(Tutorial.CityExpansion){ viewingCiv.cities.any { it.expansion.tilesClaimed()>0 } }
        displayTutorial(Tutorial.BarbarianEncountered) { viewingCiv.viewableTiles.any { it.getUnits().any { unit -> unit.civInfo.isBarbarian() } } }
        displayTutorial(Tutorial.RoadsAndRailroads) { viewingCiv.cities.size > 2 }
        displayTutorial(Tutorial.Happiness) { viewingCiv.getHappiness() < 5 }
        displayTutorial(Tutorial.Unhappiness) { viewingCiv.getHappiness() < 0 }
        displayTutorial(Tutorial.GoldenAge) { viewingCiv.goldenAges.isGoldenAge() }
        displayTutorial(Tutorial.IdleUnits) { gameInfo.turns >= 50 && game.settings.checkForDueUnits }
        displayTutorial(Tutorial.ContactMe) { gameInfo.turns >= 100 }
        val resources = viewingCiv.detailedCivResources.asSequence().filter { it.origin == "All" }  // Avoid full list copy
        displayTutorial(Tutorial.LuxuryResource) { resources.any { it.resource.resourceType==ResourceType.Luxury } }
        displayTutorial(Tutorial.StrategicResource) { resources.any { it.resource.resourceType==ResourceType.Strategic} }
        displayTutorial(Tutorial.EnemyCity) {
            viewingCiv.getKnownCivs().asSequence().filter { viewingCiv.isAtWarWith(it) }
                .flatMap { it.cities.asSequence() }.any { viewingCiv.exploredTiles.contains(it.location) }
        }
        displayTutorial(Tutorial.ApolloProgram) { viewingCiv.hasUnique("Enables construction of Spaceship parts") }
        displayTutorial(Tutorial.SiegeUnits) { viewingCiv.getCivUnits().any { it.type == UnitType.Siege } }
        displayTutorial(Tutorial.Embarking) { viewingCiv.tech.getTechUniques().contains("Enables embarkation for land units") }
        displayTutorial(Tutorial.NaturalWonders) { viewingCiv.naturalWonders.size > 0 }
    }

    private fun backButtonAndESCHandler() {

        // Since Popups including the Main Menu and the Options screen have no own back button
        // listener and no trivial way to set one, back/esc with one of them open ends up here.
        // Also, the reaction of other popups like 'disband this unit' to back/esc feels nicer this way.
        // After removeListener just in case this is slow (enumerating all stage actors)
        if (hasOpenPopups()) {
            val closedName = closeOneVisiblePopup() ?: return
            if (closedName.startsWith(Constants.tutorialPopupNamePrefix)) {
                closedName.removePrefix(Constants.tutorialPopupNamePrefix)
                tutorialController.removeTutorial(closedName)
            }
            return
        }

        // Deselect Unit
        if (bottomUnitTable.selectedUnit != null) {
            bottomUnitTable.selectedUnit = null
            bottomUnitTable.isVisible = false
            shouldUpdate = true
            return
        }

        // Deselect city
        if (bottomUnitTable.selectedCity != null) {
            bottomUnitTable.selectedCity = null
            bottomUnitTable.isVisible = false
            shouldUpdate = true
            return
        }

        // don't show a dialog, if it can't exit the game
        if (game.exitEvent == null) {
            return
        }

        val promptWindow = Popup(this)
        promptWindow.addGoodSizedLabel("Do you want to exit the game?".tr())
        promptWindow.row()
        promptWindow.addButton("Yes") { game.exitEvent?.invoke() }
        promptWindow.addButton("No") {
            promptWindow.close()
        }
        // show the dialog
        promptWindow.open (true)     // true = always on top
    }


    companion object {
        // this object must not be created multiple times
        private var multiPlayerRefresher : Timer? = null
    }
}

