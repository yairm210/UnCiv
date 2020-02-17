package com.unciv.logic

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.utils.Json
import com.unciv.models.metadata.GameSettings
import com.unciv.ui.utils.ImageGetter
import java.io.File
import kotlin.concurrent.thread

class GameSaver {
    private val saveFilesFolder = "SaveFiles"
    private val multiplayerFilesFolder = "MultiplayerGames"

    fun json() = Json().apply { setIgnoreDeprecated(true); ignoreUnknownFields = true } // Json() is NOT THREAD SAFE so we need to create a new one for each function


    fun getSave(GameName: String, multiplayer: Boolean = false): FileHandle {
        if (multiplayer)
            return Gdx.files.local("$multiplayerFilesFolder/$GameName")
        return Gdx.files.local("$saveFilesFolder/$GameName")
    }

    fun getSaves(multiplayer: Boolean = false): List<String> {
        if (multiplayer)
            return Gdx.files.local(multiplayerFilesFolder).list().map { it.name() }
        return Gdx.files.local(saveFilesFolder).list().map { it.name() }
    }

    fun saveGame(game: GameInfo, GameName: String, multiplayer: Boolean = false) {
        json().toJson(game,getSave(GameName, multiplayer))
    }

    fun loadGameByName(GameName: String, multiplayer: Boolean = false) : GameInfo {
        val game = json().fromJson(GameInfo::class.java, getSave(GameName, multiplayer))
        game.setTransients()
        return game
    }

    fun gameInfoFromString(gameData:String): GameInfo {
        val game = json().fromJson(GameInfo::class.java, gameData)
        game.setTransients()
        return game
    }

    fun deleteSave(GameName: String, multiplayer: Boolean = false){
        getSave(GameName, multiplayer).delete()
    }

    fun getGeneralSettingsFile(): FileHandle {
        return Gdx.files.local("GameSettings.json")
    }

    fun getGeneralSettings(): GameSettings {
        val settingsFile = getGeneralSettingsFile()
        if(!settingsFile.exists()) return GameSettings()
        var settings = json().fromJson(GameSettings::class.java, settingsFile)
        // I'm not sure of the circumstances,
        // but some people were getting null settings, even though the file existed??? Very odd.
        if(settings==null) settings = GameSettings()

        val currentTileSets = ImageGetter.atlas.regions.asSequence()
                .filter { it.name.startsWith("TileSets") }
                .map { it.name.split("/")[1] }.distinct()
        if(settings.tileSet !in currentTileSets) settings.tileSet = "Default"
        return settings
    }

    fun setGeneralSettings(gameSettings: GameSettings){
        getGeneralSettingsFile().writeString(json().toJson(gameSettings), false)
    }

    fun autoSave(gameInfo: GameInfo, postRunnable: () -> Unit = {}) {
        // The save takes a long time (up to a few seconds on large games!) and we can do it while the player continues his game.
        // On the other hand if we alter the game data while it's being serialized we could get a concurrent modification exception.
        // So what we do is we clone all the game data and serialize the clone.
        val gameInfoClone = gameInfo.clone()
        thread(name="Autosave") {
            saveGame(gameInfoClone, "Autosave")

            // keep auto-saves for the last 10 turns for debugging purposes
            val newAutosaveFilename = saveFilesFolder + File.separator + "Autosave-${gameInfo.currentPlayer}-${gameInfoClone.turns}"
            getSave("Autosave").copyTo(Gdx.files.local(newAutosaveFilename))

            fun getAutosaves(): List<String> { return getSaves().filter { it.startsWith("Autosave") } }
            while(getAutosaves().size>10){
                val saveToDelete = getAutosaves().minBy { getSave(it).lastModified() }!!
                deleteSave(saveToDelete)
            }

            // do this on main thread
            Gdx.app.postRunnable {
                postRunnable()
            }
        }

    }
}

