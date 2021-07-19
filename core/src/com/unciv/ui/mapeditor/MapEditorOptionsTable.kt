package com.unciv.ui.mapeditor

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Slider
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.map.MapUnit
import com.unciv.logic.map.RoadStatus
import com.unciv.logic.map.TileInfo
import com.unciv.logic.map.TileMap
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.Nation
import com.unciv.models.ruleset.tile.TerrainType
import com.unciv.models.translations.tr
import com.unciv.ui.tilegroups.TileGroup
import com.unciv.ui.tilegroups.TileSetStrings
import com.unciv.ui.utils.*

class MapEditorOptionsTable(val mapEditorScreen: MapEditorScreen): Table(CameraStageBaseScreen.skin) {
    private val tileSetLocation = "TileSets/" + UncivGame.Current.settings.tileSet + "/"

    var tileAction: (TileInfo) -> Unit = {}

    private val editorPickTable = Table()

    var brushSize = 1
    private var currentHex: Actor = Group()

    private val ruleset = mapEditorScreen.ruleset
    private val gameParameters = mapEditorScreen.gameSetupInfo.gameParameters

    private val scrollPanelHeight = mapEditorScreen.stage.height * 0.7f - 100f // -100 reserved for currentHex table

    init {
        update()
        touchable = Touchable.enabled
    }

    fun update() {
        clear()
        height = mapEditorScreen.stage.height
        width = mapEditorScreen.stage.width / 3

        setTerrainsAndResources()

        val tabPickerTable = Table().apply { defaults().pad(10f) }
        val terrainsAndResourcesTabButton = "Terrains & Resources".toTextButton()
                .onClick { setTerrainsAndResources() }
        tabPickerTable.add(terrainsAndResourcesTabButton)

        val improvementsButton = "Improvements".toTextButton()
                .onClick { setImprovements() }
        tabPickerTable.add(improvementsButton)

        tabPickerTable.pack()

        val sliderTab = Table()

        val slider = Slider(1f, 5f, 1f, false, skin)
        val sliderLabel = "{Brush Size} $brushSize".toLabel()

        slider.onChange {
            brushSize = slider.value.toInt()
            sliderLabel.setText("{Brush Size} $brushSize".tr())
        }

        sliderTab.defaults().pad(5f)
        sliderTab.add(sliderLabel)
        sliderTab.add(slider)

        add(sliderTab).row()
        add(AutoScrollPane(tabPickerTable).apply { this.width = mapEditorScreen.stage.width / 3 }).row()

        add(editorPickTable).row()
    }

    private fun setTerrainsAndResources() {

        val baseTerrainTable = Table().apply { defaults().pad(20f) }
        val terrainFeaturesTable = Table().apply { defaults().pad(20f) }

        terrainFeaturesTable.add(getHex(getRedCross(50f, 0.6f)).apply {
            onClick {
                tileAction = {
                    it.terrainFeatures.clear()
                    it.naturalWonder = null
                    it.hasBottomRiver = false
                    it.hasBottomLeftRiver = false
                    it.hasBottomRightRiver = false
                }
                setCurrentHex(getHex(getRedCross(40f, 0.6f)), "Clear terrain features")
            }
        }).row()


        addTerrainOptions(terrainFeaturesTable, baseTerrainTable)
        addRiverToggleOptions(baseTerrainTable)


        val resources = getResourceActors()

        background = ImageGetter.getBackground(Color.GRAY.cpy().apply { a = 0.7f })

        val terrainsAndResourcesTable = Table()
        terrainsAndResourcesTable.add(AutoScrollPane(baseTerrainTable).apply { setScrollingDisabled(true, false) }).height(scrollPanelHeight)

        terrainsAndResourcesTable.add(AutoScrollPane(terrainFeaturesTable).apply { setScrollingDisabled(true, false) }).height(scrollPanelHeight)

        val resourcesTable = Table()
        for (resource in resources) resourcesTable.add(resource).row()
        resourcesTable.pack()
        terrainsAndResourcesTable.add(AutoScrollPane(resourcesTable).apply { setScrollingDisabled(true, false) }).height(scrollPanelHeight).row()

        terrainsAndResourcesTable.pack()

        editorPickTable.clear()
        editorPickTable.add(terrainsAndResourcesTable)
    }

    private fun setImprovements() {

        editorPickTable.clear()

        val improvementsTable = Table()
        improvementsTable.add(getHex(getRedCross(40f, 0.6f)).apply {
            onClick {
                tileAction = { it.improvement = null; it.roadStatus = RoadStatus.None }
                setCurrentHex(getHex(getRedCross(40f, 0.6f)), "Clear improvements")
            }
        }).row()

        for (improvement in ruleset.tileImprovements.values) {
            if (improvement.name.startsWith("Remove")) continue
            if (improvement.name == Constants.cancelImprovementOrder) continue
            val improvementImage = getHex(ImageGetter.getImprovementIcon(improvement.name, 40f))
            improvementImage.onClick {
                tileAction = {
                    when (improvement.name) {
                        RoadStatus.Road.name -> it.roadStatus = RoadStatus.Road
                        RoadStatus.Railroad.name -> it.roadStatus = RoadStatus.Railroad
                        else -> it.improvement = improvement.name
                    }
                }
                val improvementIcon = getHex(ImageGetter.getImprovementIcon(improvement.name, 40f))
                setCurrentHex(improvementIcon, improvement.name.tr() + "\n" + improvement.clone().toString())
            }
            improvementsTable.add(improvementImage).row()
        }
        editorPickTable.add(AutoScrollPane(improvementsTable).apply { setScrollingDisabled(true, false) }).height(scrollPanelHeight)

        val nationTable = Table()

        /** old way improvements for all civs
         * */
        for (nation in ruleset.nations.values) {
            if (nation.isSpectator()) continue  // no improvements for spectator

            val nationImage = getHex(ImageGetter.getNationIndicator(nation, 40f))
            nationImage.onClick {
                val improvementName = "StartingLocation " + nation.name
                tileAction = {
                    it.improvement = improvementName
                    for ((tileInfo, tileGroups) in mapEditorScreen.mapHolder.tileGroups) {
                        if (tileInfo.improvement == improvementName && tileInfo != it)
                            tileInfo.improvement = null
                        tileInfo.setTerrainTransients()
                        tileGroups.forEach { it.update() }
                    }
                }

                val nationIcon = getHex(ImageGetter.getNationIndicator(nation, 40f))
                setCurrentHex(nationIcon, "[${nation.name}] starting location")
            }
            nationTable.add(nationImage).row()
        }

        editorPickTable.add(AutoScrollPane(nationTable).apply { setScrollingDisabled(true, false) }).height(scrollPanelHeight)
    }

    fun setUnits() {
        editorPickTable.clear()

        val nationsTable = Table()

        // default player - first MajorCiv player
        val defaultPlayer = gameParameters.players.first {
            it.chosenCiv != Constants.spectator && it.chosenCiv != Constants.random
        }
        var currentPlayer = getPlayerIndexString(defaultPlayer)
        var currentNation: Nation = ruleset.nations[defaultPlayer.chosenCiv]!!
        var currentUnit = ruleset.units.values.first()

        fun setUnitTileAction() {
            val unitImage = ImageGetter.getUnitIcon(currentUnit.name, currentNation.getInnerColor())
                    .surroundWithCircle(40f * 0.9f).apply { circle.color = currentNation.getOuterColor() }
                    .surroundWithCircle(40f, false).apply { circle.color = currentNation.getInnerColor() }

            setCurrentHex(unitImage, currentUnit.name.tr() + " - $currentPlayer (" + currentNation.name.tr() + ")")
            tileAction = {
                val unit = MapUnit()
                unit.baseUnit = currentUnit
                unit.name = currentUnit.name
                unit.owner = currentNation.name
                unit.civInfo = CivilizationInfo(currentNation.name).apply { nation = currentNation } // needed for the unit icon to render correctly
                unit.updateUniques()
                if (unit.movement.canMoveTo(it)) {
                    when {
                        unit.baseUnit.movesLikeAirUnits() -> {
                            it.airUnits.add(unit)
                            if (!it.isCityCenter()) unit.isTransported = true  // if not city - air unit enters carrier
                        }
                        unit.type.isCivilian() -> it.civilianUnit = unit
                        else -> it.militaryUnit = unit
                    }
                    unit.currentTile = it // needed for unit icon - unit needs to know if it's embarked or not...
                }
            }
        }

        // delete units icon
        nationsTable.add(getCrossedIcon().onClick {
            tileAction = { it.stripUnits() }
            setCurrentHex(getCrossedIcon(), "Remove units")
        }).row()

        // player icons
        for (player in gameParameters.players) {
            if (player.chosenCiv == Constants.random || player.chosenCiv == Constants.spectator)
                continue
            val nation = ruleset.nations[player.chosenCiv]!!
            val nationImage = ImageGetter.getNationIndicator(nation, 40f)
            nationsTable.add(nationImage).row()
            nationImage.onClick {
                currentNation = nation
                currentPlayer = getPlayerIndexString(player)
                setUnitTileAction()
            }
        }

        // barbarians icon
        if (!gameParameters.noBarbarians) {
            val barbarians = ruleset.nations.values.filter { it.isBarbarian() }
            for (nation in barbarians) {
                val nationImage = ImageGetter.getNationIndicator(nation, 40f)
                nationsTable.add(nationImage).row()
                nationImage.onClick {
                    currentNation = nation
                    currentPlayer = ""
                    setUnitTileAction()
                }
            }
        }

        editorPickTable.add(AutoScrollPane(nationsTable)).height(scrollPanelHeight)

        val unitsTable = Table()
        for (unit in ruleset.units.values) {
            val unitImage = ImageGetter.getUnitIcon(unit.name).surroundWithCircle(40f)
            unitsTable.add(unitImage).row()
            unitImage.onClick { currentUnit = unit; setUnitTileAction() }
        }
        editorPickTable.add(AutoScrollPane(unitsTable)).height(scrollPanelHeight)
    }

    private fun nationsFromMap(tileMap: TileMap): ArrayList<Nation> {
        val tilesWithStartingLocations = tileMap.values
                .filter { it.improvement != null && it.improvement!!.startsWith("StartingLocation ") }
        var nations = ArrayList<Nation>()
        for (tile in tilesWithStartingLocations) {
            var civName = tile.improvement!!.removePrefix("StartingLocation ")
            nations.add(ruleset.nations[civName]!!)
        }
        return nations
    }

    private fun getPlayerIndexString(player: Player): String {
        val index = gameParameters.players.indexOf(player) + 1
        return "Player [$index]".tr()
    }

    private fun getCrossedIcon(): Actor {
        return getRedCross(20f, 0.6f)
                .surroundWithCircle(40f, false)
                .apply { circle.color = Color.WHITE }
    }

    private fun getRedCross(size: Float, alpha: Float): Actor {
        val redCross = ImageGetter.getImage("OtherIcons/Close")
        redCross.setSize(size, size)
        redCross.color = Color.RED.cpy().apply { a = alpha }
        return redCross
    }

    private fun getCrossedResource(): Actor {
        val redCross = getRedCross(45f, 0.5f)
        val group = IconCircleGroup(40f, redCross, false)
        group.circle.color = ImageGetter.foodCircleColor
        return group
    }

    private fun getResourceActors(): ArrayList<Actor> {
        val resources = ArrayList<Actor>()
        resources.add(getHex(getCrossedResource()).apply {
            onClick {
                tileAction = { it.resource = null }
                setCurrentHex(getHex(getCrossedResource()), "Clear resource")
            }
        })

        for (resource in ruleset.tileResources.values) {
            if (resource.terrainsCanBeFoundOn.none { ruleset.terrains.containsKey(it) }) continue // This resource can't be placed
            val resourceHex = getHex(ImageGetter.getResourceImage(resource.name, 40f))
            resourceHex.onClick {
                tileAction = { it.resource = resource.name }

                // for the tile image
                val tileInfo = TileInfo()
                tileInfo.ruleset = mapEditorScreen.ruleset
                val terrain = resource.terrainsCanBeFoundOn.first { ruleset.terrains.containsKey(it) }
                val terrainObject = ruleset.terrains[terrain]!!
                if (terrainObject.type == TerrainType.TerrainFeature) {
                    tileInfo.baseTerrain =
                            if (terrainObject.occursOn.isNotEmpty()) terrainObject.occursOn.first()
                            else "Grassland"
                    tileInfo.terrainFeatures.add(terrain)
                } else tileInfo.baseTerrain = terrain

                tileInfo.resource = resource.name
                tileInfo.setTerrainTransients()

                setCurrentHex(tileInfo, resource.name.tr() + "\n" + resource.clone().toString())
            }
            resources.add(resourceHex)
        }
        return resources
    }

    private fun addTerrainOptions(terrainFeaturesTable: Table, baseTerrainTable: Table) {
        for (terrain in ruleset.terrains.values) {
            val tileInfo = TileInfo()
            if (terrain.type == TerrainType.TerrainFeature) {
                tileInfo.baseTerrain = when {
                    terrain.occursOn.isNotEmpty() -> terrain.occursOn.first()
                    else -> "Grassland"
                }
                tileInfo.terrainFeatures.add(terrain.name)
            } else tileInfo.baseTerrain = terrain.name
            val group = makeTileGroup(tileInfo)

            group.onClick {
                tileAction = {
                    it.naturalWonder = null // If we're setting a base terrain it should remove the nat wonder
                    when (terrain.type) {
                        TerrainType.TerrainFeature -> {
                            if (terrain.occursOn.contains(it.getLastTerrain().name))
                                it.terrainFeatures.add(terrain.name)
                        }
                        TerrainType.NaturalWonder -> it.naturalWonder = terrain.name
                        else -> it.baseTerrain = terrain.name
                    }
                }
                setCurrentHex(tileInfo, terrain.name.tr() + "\n" + terrain.clone().toString())
            }

            if (terrain.type == TerrainType.TerrainFeature)
                terrainFeaturesTable.add(group).row()
            else baseTerrainTable.add(group).row()
        }


        baseTerrainTable.pack()
        terrainFeaturesTable.pack()
    }

    private fun addRiverToggleOptions(baseTerrainTable: Table) {
        baseTerrainTable.addSeparator()

        val tileInfoBottomRightRiver = TileInfo()
        tileInfoBottomRightRiver.baseTerrain = Constants.plains
        tileInfoBottomRightRiver.hasBottomRightRiver = true
        val tileGroupBottomRightRiver = makeTileGroup(tileInfoBottomRightRiver)
        tileGroupBottomRightRiver.onClick {
            tileAction = { it.hasBottomRightRiver = !it.hasBottomRightRiver }

            setCurrentHex(tileInfoBottomRightRiver, "Bottom right river")
        }
        baseTerrainTable.add(tileGroupBottomRightRiver).row()


        val tileInfoBottomRiver = TileInfo()
        tileInfoBottomRiver.baseTerrain = Constants.plains
        tileInfoBottomRiver.hasBottomRiver = true
        val tileGroupBottomRiver = makeTileGroup(tileInfoBottomRiver)
        tileGroupBottomRiver.onClick {
            tileAction = { it.hasBottomRiver = !it.hasBottomRiver }
            setCurrentHex(tileInfoBottomRiver, "Bottom river")
        }
        baseTerrainTable.add(tileGroupBottomRiver).row()


        val tileInfoBottomLeftRiver = TileInfo()
        tileInfoBottomLeftRiver.hasBottomLeftRiver = true
        tileInfoBottomLeftRiver.baseTerrain = Constants.plains
        val tileGroupBottomLeftRiver = makeTileGroup(tileInfoBottomLeftRiver)
        tileGroupBottomLeftRiver.onClick {
            tileAction = { it.hasBottomLeftRiver = !it.hasBottomLeftRiver }
            setCurrentHex(tileInfoBottomLeftRiver, "Bottom left river")
        }
        baseTerrainTable.add(tileGroupBottomLeftRiver).row()

        baseTerrainTable.pack()
    }

    private fun makeTileGroup(tileInfo: TileInfo): TileGroup {
        tileInfo.ruleset = mapEditorScreen.ruleset
        tileInfo.setTerrainTransients()
        val group = TileGroup(tileInfo, TileSetStrings())
        group.showEntireMap = true
        group.forMapEditorIcon = true
        group.update()
        return group
    }


    private fun getHex(image: Actor? = null): Group {
        val hex = ImageGetter.getImage(tileSetLocation + "Hexagon")
        hex.color = Color.WHITE
        hex.width *= 0.3f
        hex.height *= 0.3f
        val group = Group()
        group.setSize(hex.width, hex.height)
        hex.center(group)
        group.addActor(hex)

        if (image != null) {
            image.setSize(40f, 40f)
            image.center(group)
            group.addActor(image)
        }
        return group
    }


    fun updateTileWhenClicked(tileInfo: TileInfo) {
        tileAction(tileInfo)
        tileInfo.normalizeToRuleset(ruleset)
    }


    private fun setCurrentHex(tileInfo: TileInfo, text: String) {
        val tileGroup = TileGroup(tileInfo, TileSetStrings())
                .apply {
                    showEntireMap = true
                    forMapEditorIcon = true
                    update()
                }
        tileGroup.baseLayerGroup.moveBy(-10f, 10f)
        setCurrentHex(tileGroup, text)
    }

    private fun setCurrentHex(actor: Actor, text: String) {
        currentHex.remove()
        val currentHexTable = Table()
        currentHexTable.add(text.toLabel()).padRight(30f)
        currentHexTable.add(actor)
        currentHexTable.pack()
        currentHex = currentHexTable
        currentHex.setPosition(stage.width - currentHex.width - 10, 10f)
        stage.addActor(currentHex)
    }

}