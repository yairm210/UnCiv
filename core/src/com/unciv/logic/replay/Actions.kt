package com.unciv.logic.replay

import com.badlogic.gdx.math.Vector2
import com.unciv.models.ruleset.unit.UnitType


class ActionsPerTurn(): ArrayList<Action>()

data class Action(var type: ActionType = ActionType.Move,
                  var data: Any = 0)

data class UnitId(val unitType: UnitType = UnitType.Melee,
                  val position: Vector2 = Vector2.Zero,
                  val airUnitIndex: Int? = null)

data class MovementData(val unitId: UnitId = UnitId(),
                        val destination: Vector2 = Vector2.Zero,
                        val path: List<Vector2>? = null)

data class ConstructionData(val constructionName: String = "",
                            val position: Vector2 = Vector2.Zero)


