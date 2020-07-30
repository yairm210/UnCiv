package com.unciv.logic.replay

import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.map.Scenario
import com.unciv.models.metadata.GameParameters

/**
 * Stores initial game state and a [List] of state-changing actions for each civ
 */
class Replay {
    lateinit var initialState: GameInfo
    lateinit var finalState: GameInfo
    @Transient lateinit var gameInfo: GameInfo


    var actions = HashMap<String, ArrayList<ActionsPerTurn>>()

    var currentTurnActions = ArrayList<Action>()

    /** for json parsing, we need to have a default constructor */
    constructor()

    constructor(gameInfo: GameInfo) {
        this.gameInfo = gameInfo
        initialState = gameInfo.clone()

        for (civInfo in initialState.civilizations.filter { !it.isSpectator() }) {
            actions[civInfo.civName] = ArrayList()
            updateNextTurn(civInfo)
        }
    }

    fun updateNextTurn(civInfo: CivilizationInfo) {
        if (!civInfo.isSpectator())
            actions[civInfo.civName]!!.add(ActionsPerTurn())
    }

    fun saveAction(civInfo: CivilizationInfo, action: Action) {
        actions[civInfo.civName]!![gameInfo.turns].add(action)
    }

    fun getCivActionsPerTurn(civInfo: CivilizationInfo, turn: Int): ActionsPerTurn {
        val civActions = actions[civInfo.civName]!!
        val actions = civActions[turn]
//        val actions = actions[civInfo.civName]!![turn] as Array<Action>
        return actions
    }
}