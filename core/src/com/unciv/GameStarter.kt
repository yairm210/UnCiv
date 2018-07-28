package com.unciv

import com.badlogic.gdx.math.Vector2
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.map.TileMap
import com.unciv.models.gamebasics.GameBasics
import com.unciv.ui.utils.getRandom

class GameStarter(){
    fun startNewGame(mapRadius: Int, numberOfCivs: Int, civilization: String, difficulty:String): GameInfo {
        val gameInfo = GameInfo()

        gameInfo.tileMap = TileMap(mapRadius)
        gameInfo.tileMap.gameInfo = gameInfo // need to set this transient before placing units in the map


        fun vectorIsWithinNTilesOfEdge(vector: Vector2,n:Int): Boolean {
            return vector.x < mapRadius-n
            && vector.x > n-mapRadius
            && vector.y < mapRadius-n
            && vector.y > n-mapRadius
        }

        val distanceAroundStartingPointNoOneElseWillStartIn = 5
        val freeTiles = gameInfo.tileMap.values.toMutableList().filter { vectorIsWithinNTilesOfEdge(it.position,3)}.toMutableList()
        val playerPosition = freeTiles.getRandom().position
        val playerCiv = CivilizationInfo(civilization, playerPosition, gameInfo)
        playerCiv.difficulty=difficulty
        gameInfo.civilizations.add(playerCiv) // first one is player civ

        freeTiles.removeAll(gameInfo.tileMap.getTilesInDistance(playerPosition, distanceAroundStartingPointNoOneElseWillStartIn ))

        val barbarianCivilization = CivilizationInfo()
        gameInfo.civilizations.add(barbarianCivilization)// second is barbarian civ

        for (civname in GameBasics.Civilizations.keys.filterNot { it=="Barbarians" || it==civilization }.take(numberOfCivs)) {
            if(freeTiles.isEmpty()) break // we can't add any more civs.
            val startingLocation = freeTiles.toList().getRandom().position
            val civ = CivilizationInfo(civname, startingLocation, gameInfo)
            civ.tech.techsResearched.addAll(playerCiv.getDifficulty().aiFreeTechs)
            gameInfo.civilizations.add(civ)
            freeTiles.removeAll(gameInfo.tileMap.getTilesInDistance(startingLocation, distanceAroundStartingPointNoOneElseWillStartIn ))
        }

        barbarianCivilization.civName = "Barbarians"

        gameInfo.setTransients() // needs to be before placeBarbarianUnit because it depends on the tilemap having its gameinfo set

        (1..5).forEach {
            val freeTilesList = freeTiles.toList()
            if(freeTilesList.isNotEmpty()){
                val placedTile =  freeTilesList.getRandom()
                gameInfo.placeBarbarianUnit(placedTile)
                freeTiles.remove(placedTile)
            }
        }

        return gameInfo
    }
}