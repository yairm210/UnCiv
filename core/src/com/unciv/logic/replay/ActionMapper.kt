package com.unciv.logic.replay

import com.badlogic.gdx.Game
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.map.MapUnit
import com.unciv.logic.map.TileMap

/**
 * Maps all state-changing actions
 */
object ActionMapper {
    lateinit var tileMap: TileMap

    /**
     * applies recorded [action] to civilization
     */
    fun applyAction(civInfo: CivilizationInfo, action: Action) {
        tileMap = civInfo.gameInfo.tileMap

        when (action.type) {
            ActionType.Move -> { moveUnit(action.data as MovementData) }
            ActionType.Disband -> { disbandUnit(action.data as UnitId) }
            ActionType.Build -> { addToConstructionQue(action.data as ConstructionData) }
            ActionType.Research -> { researchTech(civInfo, action.data as String) }
        }
    }

    // region Unit actions
    private fun getUnit(unitId: UnitId): MapUnit {
        val tile = tileMap[unitId.position]
        return if (unitId.unitType.isAirUnit()) tile.airUnits[unitId.airUnitIndex!!]
        else tile.getUnits().first { it.type == unitId.unitType }
    }

    private fun moveUnit(data: MovementData) {
        val unit = getUnit(data.unitId)
        unit.movement.moveToTile(tileMap[data.destination], data.path)
    }

    private fun disbandUnit(unitId: UnitId) {
        val unit = getUnit(unitId)
        unit.disband()
    }
    // endregion

    private fun addToConstructionQue(construction: ConstructionData) {
        val city = tileMap[construction.position].getCity()!!
        city.cityConstructions.constructionQueue.add(construction.constructionName)
    }

    private fun researchTech(civInfo: CivilizationInfo, name: String) {
        civInfo.tech.techsToResearch.add(name)
    }


}