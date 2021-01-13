package com.unciv.ui

import com.unciv.ui.utils.AutoScrollPane as ScrollPane
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.unciv.logic.GameInfo
import com.unciv.logic.GameSaver
import com.unciv.logic.IdChecker
import com.unciv.models.translations.tr
import com.unciv.ui.pickerscreens.PickerScreen
import com.unciv.ui.utils.*
import com.unciv.ui.worldscreen.mainmenu.OnlineMultiplayer
import java.util.*
import kotlin.concurrent.thread

class MultiplayerScreen(previousScreen: CameraStageBaseScreen) : PickerScreen() {

    private lateinit var selectedGameFile: FileHandle
    private var multiplayerGameList = mutableMapOf<String, FileHandle>() //Map<GameId, gameFile>
    private val rightSideTable = Table()
    private val leftSideTable = Table()

    private val editButtonText = "Edit Game Info".tr()
    private val addGameText = "Add Multiplayer Game".tr()
    private val copyGameIdText = "Copy Game ID".tr()
    private val copyUserIdText = "Copy User ID".tr()
    private val refreshText = "Refresh List".tr()

    private val editButton = TextButton(editButtonText, skin).apply { disable() }
    private val addGameButton = TextButton(addGameText, skin)
    private val copyGameIdButton = TextButton(copyGameIdText, skin).apply { disable() }
    private val copyUserIdButton = TextButton(copyUserIdText, skin)
    private val refreshButton = TextButton(refreshText, skin)

    init {
        setDefaultCloseAction(previousScreen)

        //Help Button Setup
        val tab = Table()
        val helpButton = TextButton("?", skin)
        helpButton.onClick {
            val helpPopup = Popup(this)
            helpPopup.addGoodSizedLabel("To create a multiplayer game, check the 'multiplayer' toggle in the New Game screen, and for each human player insert that player's user ID.".tr()).row()
            helpPopup.addGoodSizedLabel("You can assign your own user ID there easily, and other players can copy their user IDs here and send them to you for you to include them in the game.".tr()).row()
            helpPopup.addGoodSizedLabel("").row()

            helpPopup.addGoodSizedLabel("Once you've created your game, the Game ID gets automatically copied to your clipboard so you can send it to the other players.".tr()).row()
            helpPopup.addGoodSizedLabel("Players can enter your game by copying the game ID to the clipboard, and clicking on the 'Add Multiplayer Game' button".tr()).row()
            helpPopup.addGoodSizedLabel("").row()

            helpPopup.addGoodSizedLabel("The symbol of your nation will appear next to the game when it's your turn".tr()).row()

            helpPopup.addCloseButton()
            helpPopup.open()
        }
        tab.add(helpButton)
        tab.x = (stage.width - helpButton.width)
        tab.y = (stage.height - helpButton.height)
        stage.addActor(tab)

        //TopTable Setup
        //Have to put it into a separate Table to be able to add another copyGameID button
        val mainTable = Table()
        mainTable.add(ScrollPane(leftSideTable).apply { setScrollingDisabled(true, false) }).height(stage.height*2/3)
        mainTable.add(rightSideTable)
        topTable.add(mainTable).row()
        scrollPane.setScrollingDisabled(false, true)

        rightSideTable.defaults().uniformX()
        rightSideTable.defaults().fillX()
        rightSideTable.defaults().pad(10.0f)

        // leftTable Setup
        reloadGameListUI()

        // A Button to add the currently running game to multiplayerGameList if not yet done
//        addCurrentGameButton()

        //rightTable Setup
        copyUserIdButton.onClick {
            Gdx.app.clipboard.contents = game.settings.userId
            ToastPopup("UserID copied to clipboard".tr(), this)
        }
        rightSideTable.add(copyUserIdButton).padBottom(30f).row()

        copyGameIdButton.onClick {
            Gdx.app.clipboard.contents = getGameIdFromFile(selectedGameFile)
            ToastPopup("GameID copied to clipboard".tr(), this)
        }
        rightSideTable.add(copyGameIdButton).row()

        editButton.onClick {
            game.setScreen(EditMultiplayerGameInfoScreen(getGameIdFromFile(selectedGameFile)!!, selectedGameFile.name(), this))
            //game must be unselected in case the game gets deleted inside the EditScreen
            unselectGame()
        }
        rightSideTable.add(editButton).row()

        addGameButton.onClick {
            game.setScreen(AddMultiplayerGameScreen(this))
        }
        rightSideTable.add(addGameButton).padBottom(30f).row()

        refreshButton.onClick {
            redownloadAllGames()
        }
        rightSideTable.add(refreshButton).row()

        //RightSideButton Setup
        rightSideButton.setText("Join Game".tr())
        rightSideButton.onClick {
            joinMultiplaerGame()
        }
    }

    //Adds a new Multiplayer game to the List
    //gameId must be nullable because clipboard content could be null
    fun addMultiplayerGame(gameId: String?, gameName: String = ""){
        try {
            //since the gameId is a String it can contain anything and has to be checked
            UUID.fromString(IdChecker.checkAndReturnGameUuid(gameId!!))
        } catch (ex: Exception) {
            val errorPopup = Popup(this)
            errorPopup.addGoodSizedLabel("Invalid game ID!".tr())
            errorPopup.row()
            errorPopup.addCloseButton()
            errorPopup.open()
            return
        }

        if (gameIsAlreadySavedAsMultiplayer(gameId)) {
            ToastPopup("Game is already added".tr(), this)
            return
        }

        addGameButton.setText("Working...".tr())
        addGameButton.disable()
        thread(name="MultiplayerDownload") {
            try {
                // The tryDownload can take more than 500ms. Therefore, to avoid ANRs,
                // we need to run it in a different thread.
                val game = OnlineMultiplayer().tryDownloadGame(gameId.trim())
                if (gameName == "")
                    GameSaver.saveGame(game, game.gameId, true)
                else
                    GameSaver.saveGame(game, gameName, true)

                Gdx.app.postRunnable { reloadGameListUI() }
            } catch (ex: Exception) {
                Gdx.app.postRunnable {
                    val errorPopup = Popup(this)
                    errorPopup.addGoodSizedLabel("Could not download game!".tr())
                    errorPopup.row()
                    errorPopup.addCloseButton()
                    errorPopup.open()
                }
            }
            Gdx.app.postRunnable {
                addGameButton.setText(addGameText)
                addGameButton.enable()
            }
        }
    }

    //just loads the game from savefile
    //the game will be downloaded opon joining it anyway
    private fun joinMultiplaerGame(){
        try {
            game.loadGame(GameSaver.loadGameFromFile(selectedGameFile))
        } catch (ex: Exception) {
            val errorPopup = Popup(this)
            errorPopup.addGoodSizedLabel("Could not download game!".tr())
            errorPopup.row()
            errorPopup.addCloseButton()
            errorPopup.open()
        }
    }

    private fun gameIsAlreadySavedAsMultiplayer(gameId: String) : Boolean{
        return multiplayerGameList.containsKey(gameId)
    }

    //reloads all gameFiles to refresh UI
    fun reloadGameListUI() {
        val leftSubTable = Table()
        val gameSaver = GameSaver
        val savedGames: Sequence<FileHandle>

        try {
            savedGames = gameSaver.getSaves(true)
        } catch (ex: Exception) {
            val errorPopup = Popup(this)
            errorPopup.addGoodSizedLabel("Could not refresh!".tr())
            errorPopup.row()
            errorPopup.addCloseButton()
            errorPopup.open()
            return
        }

        for (gameSaveFile in savedGames) {
            try {
                val gameTable = Table()
                val turnIndicator = Table()
                turnIndicator.add(ImageGetter.getImage("EmojiIcons/Turn"))
                gameTable.add(turnIndicator)

                val lastModifiedMillis = gameSaveFile.lastModified()
                val gameButton = gameSaveFile.name().toTextButton()
                var currentTurnUser = ""

                gameButton.onClick {
                    selectedGameFile = gameSaveFile
                    if(getGameIdFromFile(gameSaveFile) != null){
                        copyGameIdButton.enable()
                        editButton.enable()
                    }
                    rightSideButton.enable()

                    //get Minutes since last modified
                    val lastSavedMinutesAgo = (System.currentTimeMillis() - lastModifiedMillis) / 60000
                    var descriptionText = "Last refresh: [$lastSavedMinutesAgo] minutes ago".tr() + "\r\n"
                    descriptionText += "Current Turn:".tr() + " ${currentTurnUser}\r\n"
                    descriptionLabel.setText(descriptionText)
                }

                thread {
                    val game = gameSaver.loadGameFromFile(gameSaveFile)

                    //Add games to list so saves don't have to be loaded as Files so often
                    if (!gameIsAlreadySavedAsMultiplayer(game.gameId)) {
                        multiplayerGameList[game.gameId] = gameSaveFile
                    }

                    Gdx.app.postRunnable {
                        turnIndicator.clear()
                        if (isUsersTurn(game)) {
                            turnIndicator.add(ImageGetter.getNationIndicator(game.currentPlayerCiv.nation, 50f))
                        }

                        currentTurnUser = game.currentPlayer
                    }
                }

                gameTable.add(gameButton).pad(5f).row()
                leftSubTable.add(gameTable).row()
            } catch (ex: Exception) {
                //skipping one save is not fatal
                ToastPopup("Could not refresh!".tr(), this)
                continue
            }
        }

        leftSideTable.clear()
        leftSideTable.add(leftSubTable)
    }

    //redownload all games to update the list
    //can maybe replaced when notification support gets introduced
    private fun redownloadAllGames(){
        addGameButton.disable()
        refreshButton.setText("Working...".tr())
        refreshButton.disable()

        //One thread for all downloads
        thread (name = "multiplayerGameDownload") {
            for (gameId in multiplayerGameList.keys) {
                try {
                    val game = OnlineMultiplayer().tryDownloadGame(gameId)
                    GameSaver.saveGame(game, multiplayerGameList.getValue(gameId).name(), true)
                } catch (ex: Exception) {
                    //skipping one is not fatal
                    //Trying to use as many prev. used strings as possible
                    Gdx.app.postRunnable {
                        ToastPopup("Could not download game!".tr() + " ${multiplayerGameList.getValue(gameId)}", this)
                    }
                    continue
                }
            }

            //Reset UI
            Gdx.app.postRunnable {
                addGameButton.enable()
                refreshButton.setText(refreshText)
                refreshButton.enable()
                unselectGame()
                reloadGameListUI()
            }
        }
    }

    //Adds a Button to add the currently running game to multiplayerGameList
    private fun addCurrentGameButton(){
        val currentlyRunningGame = game.gameInfo
        if (!currentlyRunningGame.gameParameters.isOnlineMultiplayer || gameIsAlreadySavedAsMultiplayer(currentlyRunningGame.gameId))
            return

        val currentGameButton = "Add Currently Running Game".toTextButton()
        currentGameButton.onClick {
            if (gameIsAlreadySavedAsMultiplayer(currentlyRunningGame.gameId))
                return@onClick
            try {
                GameSaver.saveGame(currentlyRunningGame, currentlyRunningGame.gameId, true)
                reloadGameListUI()
            } catch (ex: Exception) {
                val errorPopup = Popup(this)
                errorPopup.addGoodSizedLabel("Could not save game!".tr())
                errorPopup.row()
                errorPopup.addCloseButton()
                errorPopup.open()
            }
        }

        topTable.add(currentGameButton)
    }

    //It doesn't really unselect the game because selectedGame cant be null
    //It just disables everything a selected game has set
    private fun unselectGame(){
        editButton.disable()
        copyGameIdButton.disable()
        rightSideButton.disable()
        descriptionLabel.setText("")
    }

    //check if its the users turn
    private fun isUsersTurn(gameInfo: GameInfo) : Boolean{
        return (gameInfo.currentPlayerCiv.playerId == game.settings.userId)
    }

    fun removeFromList(gameId: String){
        multiplayerGameList.remove(gameId)
    }

    fun getGameIdFromFile(fileHandle: FileHandle) : String?{
        val gameIds = multiplayerGameList.filterValues { it == fileHandle }.keys
        if (gameIds.isNotEmpty())
            return gameIds.first()
        return null
    }
}

//Subscreen of MultiplayerScreen to edit and delete saves
//backScreen is used for getting back to the MultiplayerScreen so it doesn't have to be created over and over again
class EditMultiplayerGameInfoScreen(gameId: String, gameName: String, backScreen: MultiplayerScreen): PickerScreen(){
    init {
        val textField = TextField(gameName, skin)

        topTable.add("Rename".toLabel()).row()
        topTable.add(textField).pad(10f).padBottom(30f).width(stage.width/2).row()

        //TODO Change delete to "give up"
            //->turn a player into an AI so everyone can still play without the user
                //->should only be possible on the users turn because it has to be uploaded afterwards
        val deleteButton = "Delete save".toTextButton()
        deleteButton.onClick {
            val askPopup = Popup(this)
            askPopup.addGoodSizedLabel("Are you sure you want to delete this map?".tr()).row()
            askPopup.addButton("Yes"){
                try {
                    GameSaver.deleteSave(gameName, true)
                    backScreen.game.setScreen(backScreen)
                    backScreen.reloadGameListUI()
                }catch (ex: Exception) {
                    askPopup.close()
                    ToastPopup("Could not delete game!".tr(), this)
                }
            }
            askPopup.addButton("No"){
                askPopup.close()
            }
            askPopup.open()
        }.apply { color = Color.RED }

        topTable.add(deleteButton)

        //CloseButton Setup
        closeButton.setText("Back".tr())
        closeButton.onClick {
            backScreen.game.setScreen(backScreen)
        }

        //RightSideButton Setup
        rightSideButton.setText("Save game".tr())
        rightSideButton.enable()
        rightSideButton.onClick {
            rightSideButton.setText("Saving...".tr())
            try {
                backScreen.removeFromList(gameId)
                //using addMultiplayerGame will download the game from Dropbox so the descriptionLabel displays the right things
                backScreen.addMultiplayerGame(gameId, textField.text)
                GameSaver.deleteSave(gameName, true)
                backScreen.game.setScreen(backScreen)
                backScreen.reloadGameListUI()
            }catch (ex: Exception) {
                val errorPopup = Popup(this)
                errorPopup.addGoodSizedLabel("Could not save game!".tr())
                errorPopup.row()
                errorPopup.addCloseButton()
                errorPopup.open()
            }
        }
    }
}

class AddMultiplayerGameScreen(backScreen: MultiplayerScreen) : PickerScreen(){
    init {
        val gameNameTextField = TextField("", skin)
        val gameIDTextField = TextField("", skin)
        val pasteGameIDButton = TextButton("Paste gameID from clipboard", skin)
        pasteGameIDButton.onClick {
            gameIDTextField.text = Gdx.app.clipboard.contents
        }

        topTable.add("GameID".toLabel()).row()
        val gameIDTable = Table()
        gameIDTable.add(gameIDTextField).pad(10f).width(2*stage.width/3 - pasteGameIDButton.width)
        gameIDTable.add(pasteGameIDButton)
        topTable.add(gameIDTable).padBottom(30f).row()

        topTable.add("Game name".toLabel()).row()
        topTable.add(gameNameTextField).pad(10f).padBottom(30f).width(stage.width/2).row()

        //CloseButton Setup
        closeButton.setText("Back".tr())
        closeButton.onClick {
            backScreen.game.setScreen(backScreen)
        }

        //RightSideButton Setup
        rightSideButton.setText("Save game".tr())
        rightSideButton.enable()
        rightSideButton.onClick {
            try {
                UUID.fromString(IdChecker.checkAndReturnGameUuid(gameIDTextField.text))
            }catch (ex: Exception){
                ToastPopup("Invalid game ID!".tr(), this)
                return@onClick
            }

            backScreen.addMultiplayerGame(gameIDTextField.text.trim(), gameNameTextField.text.trim())
            backScreen.game.setScreen(backScreen)
        }
    }
}
