package com.unciv

import com.badlogic.gdx.Application
import com.badlogic.gdx.Game
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.audio.Music
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.utils.Align
import com.unciv.logic.GameInfo
import com.unciv.logic.GameSaver
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.replay.ActionMapper
import com.unciv.logic.replay.NextTurnReplay
import com.unciv.logic.replay.Replay
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.translations.Translations
import com.unciv.ui.LanguagePickerScreen
import com.unciv.ui.utils.*
import com.unciv.ui.worldscreen.WorldScreen
import java.util.*
import kotlin.concurrent.thread

class UncivGame(parameters: UncivGameParameters) : Game() {
    // we need this secondary constructor because Java code for iOS can't handle Kotlin lambda parameters
    constructor(version: String) : this(UncivGameParameters(version, null))

    val version = parameters.version
    private val crashReportSender = parameters.crashReportSender
    val exitEvent = parameters.exitEvent
    val cancelDiscordEvent = parameters.cancelDiscordEvent
    val fontImplementation = parameters.fontImplementation
    val consoleMode = parameters.consoleMode

    lateinit var gameInfo: GameInfo
    fun isGameInfoInitialized() = this::gameInfo.isInitialized
    lateinit var settings: GameSettings
    lateinit var crashController: CrashController
    /**
     * This exists so that when debugging we can see the entire map.
     * Remember to turn this to false before commit and upload!
     */
    var viewEntireMapForDebug = false
    /** For when you need to test something in an advanced game and don't have time to faff around */
    val superchargedForDebug = false

    /** Simulate until this turn on the first "Next turn" button press.
     *  Does not update World View changes until finished.
     *  Set to 0 to disable.
     */
    val simulateUntilTurnForDebug: Int = 0

    /** Toggles console battle log
     */
    val alertBattle = false
    /** Debug new replay functionality
     */
    val replayDebugSwitch = false

    lateinit var worldScreen: WorldScreen

    var music: Music? = null
    val musicLocation = "music/thatched-villagers.mp3"
    private var isSizeRestored = false
    var isInitialized = false


    val translations = Translations()

    override fun create() {
        Gdx.input.setCatchKey(Input.Keys.BACK, true)
        if (Gdx.app.type != Application.ApplicationType.Desktop) {
            viewEntireMapForDebug = false
        }
        Current = this


        // If this takes too long players, especially with older phones, get ANR problems.
        // Whatever needs graphics needs to be done on the main thread,
        // So it's basically a long set of deferred actions.
        settings = GameSaver.getGeneralSettings() // needed for the screen
        screen = LoadingScreen()

        Gdx.graphics.isContinuousRendering = settings.continuousRendering

        thread(name = "LoadJSON") {
            RulesetCache.loadRulesets()
            translations.tryReadTranslationForCurrentLanguage()
            translations.loadPercentageCompleteOfLanguages()

            if (settings.userId.isEmpty()) { // assign permanent user id
                settings.userId = UUID.randomUUID().toString()
                settings.save()
            }

            // This stuff needs to run on the main thread because it needs the GL context
            Gdx.app.postRunnable {
                ImageGetter.ruleset = RulesetCache.getBaseRuleset() // so that we can enter the map editor without having to load a game first
                thread(name="Music") { startMusic() }
                restoreSize()

                if (settings.isFreshlyCreated) {
                    setScreen(LanguagePickerScreen())
                } else { setScreen(MainMenuScreen()) }
                isInitialized = true
            }
        }
        crashController = CrashController.Impl(crashReportSender)
    }

    fun restoreSize() {
        if (!isSizeRestored && Gdx.app.type == Application.ApplicationType.Desktop && settings.windowState.height>39 && settings.windowState.width>39) {
            isSizeRestored = true
            Gdx.graphics.setWindowedMode(settings.windowState.width, settings.windowState.height)
        }
    }


    fun loadGame(gameInfo: GameInfo, replay: Replay? = null) {
        this.gameInfo = gameInfo

        if (replayDebugSwitch && replay != null) {
            this.gameInfo.replay = replay
            this.gameInfo.replayMode = true
        } else if (replayDebugSwitch)
            this.gameInfo.replay = Replay(gameInfo)

        ImageGetter.ruleset = gameInfo.ruleSet
        Gdx.input.inputProcessor = null // Since we will set the world screen when we're ready,
                                        // This is to avoid ANRs when loading.
        ImageGetter.refreshAtlas()
        worldScreen = WorldScreen(gameInfo.getPlayerToViewAs())
        setWorldScreen()
    }

    fun loadGame(gameName: String) {
        loadGame(GameSaver.loadGameByName(gameName))
    }

    fun loadReplay(replayName: String) {
        val replay = GameSaver.loadReplayByName(replayName)
        val newGame = replay.initialState

        fun setFirstSpectator() {
            var spectator = newGame.gameParameters.players.find { it.chosenCiv == Constants.spectator }

            if (spectator != null) {
                newGame.gameParameters.players.remove(spectator)
                newGame.civilizations.remove(newGame.civilizations.find { it.isSpectator() })
            }

            spectator = Player(Constants.spectator).apply { playerType = PlayerType.Human }
            newGame.gameParameters.players.add(0, spectator)

            val spectatorCiv = CivilizationInfo(Constants.spectator)
            spectatorCiv.playerType = PlayerType.Human

            newGame.civilizations.add(0, spectatorCiv)
            newGame.currentPlayer = Constants.spectator
        }

        setFirstSpectator()

        newGame.setTransients()
        NextTurnReplay.replay = replay
        NextTurnReplay.replay.gameInfo = newGame
        loadGame(newGame, replay)
    }

    fun startMusic() {
        if (settings.musicVolume < 0.01) return

        val musicFile = Gdx.files.local(musicLocation)
        if (musicFile.exists()) {
            music = Gdx.audio.newMusic(musicFile)
            music!!.isLooping = true
            music!!.volume = 0.4f * settings.musicVolume
            music!!.play()
        }
    }

    fun setScreen(screen: CameraStageBaseScreen) {
        Gdx.input.inputProcessor = screen.stage
        super.setScreen(screen)
    }

    fun setWorldScreen() {
        if (screen != null && screen != worldScreen) screen.dispose()
        setScreen(worldScreen)
        worldScreen.shouldUpdate = true // This can set the screen to the policy picker or tech picker screen, so the input processor must come before
        Gdx.graphics.requestRendering()
    }

    // This is ALWAYS called after create() on Android - google "Android life cycle"
    override fun resume() {
        super.resume()
        if (!isInitialized) return // The stuff from Create() is still happening, so the main screen will load eventually
    }

    override fun pause() {
        if (this::gameInfo.isInitialized) GameSaver.autoSave(this.gameInfo)
        super.pause()
    }

    override fun resize(width: Int, height: Int) {
        screen.resize(width, height)
    }

    override fun dispose() {
        cancelDiscordEvent?.invoke()

        // Log still running threads (should be only this one and "DestroyJavaVM")
        val numThreads = Thread.activeCount()
        val threadList = Array(numThreads) { _ -> Thread() }
        Thread.enumerate(threadList)

        if (::gameInfo.isInitialized){
            val autoSaveThread = threadList.firstOrNull { it.name == "Autosave" }
            if (autoSaveThread != null && autoSaveThread.isAlive) {
                // auto save is already in progress (e.g. started by onPause() event)
                // let's allow it to finish and do not try to autosave second time
                autoSaveThread.join()
            } else
                GameSaver.autoSaveSingleThreaded(gameInfo)      // NO new thread
            settings.save()
        }

        threadList.filter { it !== Thread.currentThread() && it.name != "DestroyJavaVM"}.forEach {
            println ("    Thread ${it.name} still running in UncivGame.dispose().")
        }
    }

    companion object {
        lateinit var Current: UncivGame
        fun isCurrentInitialized() = this::Current.isInitialized
    }
}

class LoadingScreen:CameraStageBaseScreen() {
    init {
        val happinessImage = ImageGetter.getImage("StatIcons/Happiness")
        happinessImage.center(stage)
        happinessImage.setOrigin(Align.center)
        happinessImage.addAction(Actions.sequence(
                Actions.delay(1f),
                Actions.rotateBy(360f, 0.5f)))
        stage.addActor(happinessImage)
    }
}

