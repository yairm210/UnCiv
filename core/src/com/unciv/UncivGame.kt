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
import com.unciv.logic.civilization.PlayerType
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.tilesets.TileSetCache
import com.unciv.models.translations.Translations
import com.unciv.ui.LanguagePickerScreen
import com.unciv.ui.utils.*
import com.unciv.ui.worldscreen.PlayerReadyScreen
import com.unciv.ui.worldscreen.WorldScreen
import java.util.*
import kotlin.concurrent.thread

class UncivGame(parameters: UncivGameParameters) : Game() {
    // we need this secondary constructor because Java code for iOS can't handle Kotlin lambda parameters
    constructor(version: String) : this(UncivGameParameters(version, null))

    val version = parameters.version
    private val crashReportSender = parameters.crashReportSender
    val cancelDiscordEvent = parameters.cancelDiscordEvent
    val fontImplementation = parameters.fontImplementation
    val consoleMode = parameters.consoleMode
    val customSaveLocationHelper = parameters.customSaveLocationHelper
    val limitOrientationsHelper = parameters.limitOrientationsHelper

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

    /** Console log battles
     */
    val alertBattle = false

    lateinit var worldScreen: WorldScreen

    var music: Music? = null
    val musicLocation = "music/thatched-villagers.mp3"
    var isInitialized = false


    val translations = Translations()

    override fun create() {
        Gdx.input.setCatchKey(Input.Keys.BACK, true)
        if (Gdx.app.type != Application.ApplicationType.Desktop) {
            viewEntireMapForDebug = false
        }
        Current = this
        GameSaver.customSaveLocationHelper = customSaveLocationHelper

        // If this takes too long players, especially with older phones, get ANR problems.
        // Whatever needs graphics needs to be done on the main thread,
        // So it's basically a long set of deferred actions.

        /** When we recreate the GL context for whatever reason (say - we moved to a split screen on Android),
         * ALL objects that were related to the old context - need to be recreated.
         * So far we have:
         * - All textures (hence the texture atlas)
         * - SpriteBatch (hence CameraStageBaseScreen uses a new SpriteBatch for each screen)
         * - Skin (hence CameraStageBaseScreen.setSkin())
         * - Font (hence Fonts.resetFont() inside setSkin())
         */
        ImageGetter.resetAtlases()
        settings = GameSaver.getGeneralSettings() // needed for the screen
        ImageGetter.setNewRuleset(ImageGetter.ruleset)  // This needs to come after the settings, since we may have default visual mods
        if(settings.tileSet !in ImageGetter.getAvailableTilesets()) { // If one of the tilesets is no longer available, default back
            settings.tileSet = "FantasyHex"
        }

        CameraStageBaseScreen.setSkin() // needs to come AFTER the Texture reset, since the buttons depend on it

        Gdx.graphics.isContinuousRendering = settings.continuousRendering
        screen = LoadingScreen()


        thread(name = "LoadJSON") {
            RulesetCache.loadRulesets(printOutput = true)
            translations.tryReadTranslationForCurrentLanguage()
            translations.loadPercentageCompleteOfLanguages()
            TileSetCache.loadTileSetConfigs(printOutput = true)

            if (settings.userId.isEmpty()) { // assign permanent user id
                settings.userId = UUID.randomUUID().toString()
                settings.save()
            }

            // This stuff needs to run on the main thread because it needs the GL context
            Gdx.app.postRunnable {
                ImageGetter.ruleset = RulesetCache.getBaseRuleset() // so that we can enter the map editor without having to load a game first


                thread(name="Music") { startMusic() }

                if (settings.isFreshlyCreated) {
                    setScreen(LanguagePickerScreen())
                } else { setScreen(MainMenuScreen()) }
                isInitialized = true
            }
        }
        crashController = CrashController.Impl(crashReportSender)
    }

    fun loadGame(gameInfo: GameInfo) {
        this.gameInfo = gameInfo
        ImageGetter.setNewRuleset(gameInfo.ruleSet)
        Gdx.input.inputProcessor = null // Since we will set the world screen when we're ready,
        if (gameInfo.civilizations.count { it.playerType == PlayerType.Human } > 1 && !gameInfo.gameParameters.isOnlineMultiplayer)
            setScreen(PlayerReadyScreen(gameInfo, gameInfo.getPlayerToViewAs()))
        else {
            worldScreen = WorldScreen(gameInfo, gameInfo.getPlayerToViewAs())
            setWorldScreen()
        }
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
        if (isGameInfoInitialized()) GameSaver.autoSave(this.gameInfo)
        super.pause()
    }

    override fun resize(width: Int, height: Int) {
        screen.resize(width, height)
    }

    override fun dispose() {
        cancelDiscordEvent?.invoke()
        Sounds.clearCache()

        // Log still running threads (on desktop that should be only this one and "DestroyJavaVM")
        val numThreads = Thread.activeCount()
        val threadList = Array(numThreads) { _ -> Thread() }
        Thread.enumerate(threadList)

        if (isGameInfoInitialized()){
            val autoSaveThread = threadList.firstOrNull { it.name == "Autosave" }
            if (autoSaveThread != null && autoSaveThread.isAlive) {
                // auto save is already in progress (e.g. started by onPause() event)
                // let's allow it to finish and do not try to autosave second time
                autoSaveThread.join()
            } else
                GameSaver.autoSaveSingleThreaded(gameInfo)      // NO new thread
        }
        settings.save()

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

