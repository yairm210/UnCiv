package com.unciv.logic.replay

import com.unciv.logic.civilization.CivilizationInfo

/**
 * Replays recorded actions for each civilizations per turn
 * Would be used in GameInfo.NextTurn
 */
object NextTurnReplay {
    lateinit var replay: Replay

    fun replayCivMoves(civInfo: CivilizationInfo, turn: Int) {
        val actions = replay.getCivActionsPerTurn(civInfo, turn)
        for (action in actions) {
            ActionMapper.applyAction(civInfo, action)
        }
    }
}