package com.unciv.ui.mapeditor

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.Slider
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.map.RoadStatus
import com.unciv.logic.map.TileInfo
import com.unciv.models.ruleset.tile.Terrain
import com.unciv.models.ruleset.tile.TerrainType
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.translations.tr
import com.unciv.ui.tilegroups.TileGroup
import com.unciv.ui.tilegroups.TileSetStrings
import com.unciv.ui.utils.*

class TileEditorOptionsTable(val mapEditorScreen: MapEditorScreen): Table(CameraStageBaseScreen.skin){
    private val tileSetLocation = "TileSets/"+ UncivGame.Current.settings.tileSet +"/"

    var tileAction:(TileInfo)->Unit = {}

    private val editorPickTable = Table()

    var brushSize = 1
    private var currentHex: Actor = Group()

    private val ruleset = mapEditorScreen.ruleset

    private val scrollPanelHeight = mapEditorScreen.stage.height*0.7f - 100f // -100 reserved for currentHex table

    init{
        height = mapEditorScreen.stage.height
        width = mapEditorScreen.stage.width/3

        setTerrainsAndResources()

        val tabPickerTable = Table().apply { defaults().pad(10f) }
        val terrainsAndResourcesTabButton = "Terrains & Resources".toTextButton()
                .onClick { setTerrainsAndResources() }
        tabPickerTable.add(terrainsAndResourcesTabButton)

        val civLocationsButton = "Improvements".toTextButton()
                .onClick { setImprovements() }
        tabPickerTable.add(civLocationsButton)
        tabPickerTable.pack()

        val sliderTab = Table()

        val slider = Slider(1f, 5f, 1f, false, skin)
        val sliderLabel = "{Brush Size} $brushSize".toLabel()

        slider.onChange {
            brushSize = slider.getValue().toInt()
            sliderLabel.setText("{Brush Size} $brushSize".tr())
        }

        sliderTab.defaults().pad(5f)
        sliderTab.add(sliderLabel)
        sliderTab.add(slider)

        add(sliderTab).row()
        add(AutoScrollPane(tabPickerTable).apply { this.width= mapEditorScreen.stage.width/3}).row()

        add(editorPickTable).row()
    }

    private fun setImprovements() {

        editorPickTable.clear()

        val improvementsTable = Table()
        improvementsTable.add(getHex(Color.WHITE).apply {
            onClick {
                tileAction = {it.improvement=null}
                setCurrentHex(getHex(Color.WHITE), "Clear improvements")
            }
        }).row()

        for(improvement in ruleset.tileImprovements.values){
            if(improvement.name.startsWith("Remove")) continue
            val improvementImage = getHex(Color.WHITE, ImageGetter.getImprovementIcon(improvement.name, 40f))
            improvementImage.onClick {
                tileAction = {
                    when (improvement.name) {
                        "Road" -> it.roadStatus = RoadStatus.Road
                        "Railroad" -> it.roadStatus = RoadStatus.Railroad
                        else -> it.improvement = improvement.name
                    }

                }
                val improvementIcon = getHex(Color.WHITE, ImageGetter.getImprovementIcon(improvement.name, 40f))
                setCurrentHex(improvementIcon, improvement.name.tr()+"\n"+improvement.clone().toString())
            }
            improvementsTable.add(improvementImage).row()
        }
        editorPickTable.add(AutoScrollPane(improvementsTable)).height(scrollPanelHeight)

        val nationsTable = Table()
        for(nation in ruleset.nations.values){
            val nationImage = getHex(Color.WHITE, ImageGetter.getNationIndicator(nation, 40f))
            nationImage.onClick {
                val improvementName = "StartingLocation "+nation.name

                tileAction = {
                    it.improvement = improvementName
                    for (tileGroup in mapEditorScreen.mapHolder.tileGroups.values) {
                        val tile = tileGroup.tileInfo
                        if (tile.improvement == improvementName && tile != it)
                            tile.improvement = null
                        tile.setTransients()
                        tileGroup.update()
                    }
                }

                val nationIcon = getHex(Color.WHITE, ImageGetter.getNationIndicator(nation, 40f))
                setCurrentHex(nationIcon, "[${nation.name}] starting location")
            }
            nationsTable.add(nationImage).row()
        }

        editorPickTable.add(AutoScrollPane(nationsTable)).height(scrollPanelHeight)
    }

    private fun getRedCross(size: Float, alpha: Float): Actor {
        val redCross = ImageGetter.getImage("OtherIcons/Close")
        redCross.setSize( size, size)
        redCross.color = Color.RED.cpy().apply { a = alpha }
        return redCross
    }

    private fun setTerrainsAndResources(){

        val baseTerrainTable = Table().apply { defaults().pad(20f) }
        val terrainFeaturesTable = Table().apply { defaults().pad(20f) }

        terrainFeaturesTable.add(getHex(Color.WHITE, getRedCross(50f, 0.6f)).apply {
            onClick {
                tileAction = { it.terrainFeature=null; it.naturalWonder = null }
                setCurrentHex(getHex(Color.WHITE, getRedCross(40f, 0.6f)), "Clear terrain features")
            }
        }).row()


        addTerrainOptions(terrainFeaturesTable, baseTerrainTable)
//        addRiverToggleOptions(baseTerrainTable)


        val resources = getResourceActors()

        background = ImageGetter.getBackground(Color.GRAY.cpy().apply { a = 0.7f })

        val terrainsAndResourcesTable = Table()
        terrainsAndResourcesTable.add(AutoScrollPane(baseTerrainTable).apply { setScrollingDisabled(true,false) }).height(scrollPanelHeight)

        terrainsAndResourcesTable.add(AutoScrollPane(terrainFeaturesTable).apply { setScrollingDisabled(true,false) }).height(scrollPanelHeight)

        val resourcesTable = Table()
        for(resource in resources) resourcesTable.add(resource).row()
        resourcesTable.pack()
        terrainsAndResourcesTable.add(AutoScrollPane(resourcesTable).apply { setScrollingDisabled(true,false) }).height(scrollPanelHeight).row()

        terrainsAndResourcesTable.pack()

        editorPickTable.clear()
        editorPickTable.add(terrainsAndResourcesTable)
    }

    private fun getCrossedResource() : Actor {
        val redCross = getRedCross(45f, 0.5f)
        val group = IconCircleGroup(40f, redCross, false)
        group.circle.color = ImageGetter.foodCircleColor
        return group
    }


    private fun getResourceActors(): ArrayList<Actor> {
        val resources = ArrayList<Actor>()
        resources.add(getHex(Color.WHITE, getCrossedResource()).apply {
            onClick {
                tileAction = {it.resource=null}
                setCurrentHex(getHex(Color.WHITE, getCrossedResource()), "Clear resource")
            }
        })

        for (resource in ruleset.tileResources.values) {
            val resourceHex = getHex(Color.WHITE, ImageGetter.getResourceImage(resource.name, 40f))
            resourceHex.onClick {
                tileAction = {it.resource = resource.name}

                // for the tile image
                val tileInfo = TileInfo()
                tileInfo.ruleset = mapEditorScreen.ruleset
                val terrain = resource.terrainsCanBeFoundOn.first()
                val terrainObject = ruleset.terrains[terrain]!!
                if (terrainObject.type == TerrainType.TerrainFeature) {
                    tileInfo.baseTerrain =
                            if (terrainObject.occursOn != null) terrainObject.occursOn.first()
                            else "Grassland"
                    tileInfo.terrainFeature = terrain
                } else tileInfo.baseTerrain = terrain

                tileInfo.resource = resource.name
                tileInfo.setTransients()

                setCurrentHex(tileInfo, resource.name.tr() + "\n" + resource.clone().toString())
            }
            resources.add(resourceHex)
        }
        return resources
    }

    private fun addTerrainOptions(terrainFeaturesTable: Table, baseTerrainTable: Table) {
        for (terrain in ruleset.terrains.values) {
            val tileInfo = TileInfo()
            tileInfo.ruleset = mapEditorScreen.ruleset
            if (terrain.type == TerrainType.TerrainFeature) {
                tileInfo.baseTerrain = when {
                    terrain.occursOn != null -> terrain.occursOn.first()
                    else -> "Grassland"
                }
                tileInfo.terrainFeature = terrain.name
            } else tileInfo.baseTerrain = terrain.name
            val group = makeTileGroup(tileInfo)

            group.onClick {
                tileAction = {
                    when (terrain.type) {
                        TerrainType.TerrainFeature -> it.terrainFeature = terrain.name
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
            tileAction = {it.hasBottomRightRiver = !it.hasBottomRightRiver}

            setCurrentHex(tileInfoBottomRightRiver, "Bottom right river")
        }
        baseTerrainTable.add(tileGroupBottomRightRiver).row()


        val tileInfoBottomRiver = TileInfo()
        tileInfoBottomRiver.baseTerrain = Constants.plains
        tileInfoBottomRiver.hasBottomRiver = true
        val tileGroupBottomRiver = makeTileGroup(tileInfoBottomRiver)
        tileGroupBottomRiver.onClick {
            tileAction = {it.hasBottomRiver = !it.hasBottomRiver}
            setCurrentHex(tileInfoBottomRiver, "Bottom river")
        }
        baseTerrainTable.add(tileGroupBottomRiver).row()


        val tileInfoBottomLeftRiver = TileInfo()
        tileInfoBottomLeftRiver.hasBottomLeftRiver = true
        tileInfoBottomLeftRiver.baseTerrain = Constants.plains
        val tileGroupBottomLeftRiver = makeTileGroup(tileInfoBottomLeftRiver)
        tileGroupBottomLeftRiver.onClick {
            tileAction = {it.hasBottomLeftRiver = !it.hasBottomLeftRiver}
            setCurrentHex(tileInfoBottomLeftRiver, "Bottom left river")
        }
        baseTerrainTable.add(tileGroupBottomLeftRiver).row()

        baseTerrainTable.pack()
    }

    private fun makeTileGroup(tileInfo: TileInfo): TileGroup {
        tileInfo.setTransients()
        val group = TileGroup(tileInfo, TileSetStrings())
        group.showEntireMap = true
        group.forMapEditorIcon = true
        group.update()
        return group
    }


    private fun getHex(color: Color, image: Actor?=null): Group {
        val hex = ImageGetter.getImage(tileSetLocation + "Hexagon")
        hex.color = color
        hex.width*=0.3f
        hex.height*=0.3f
        val group = Group()
        group.setSize(hex.width,hex.height)
        hex.center(group)
        group.addActor(hex)

        if(image!=null) {
            image.setSize(40f, 40f)
            image.center(group)
            group.addActor(image)
        }
        return group
    }


    fun updateTileWhenClicked(tileInfo: TileInfo) {
        tileAction(tileInfo)
        normalizeTile(tileInfo)
    }

    fun normalizeTile(tileInfo: TileInfo){
        /*Natural Wonder superpowers! */
        if (tileInfo.naturalWonder != null) {
            val naturalWonder = tileInfo.getNaturalWonder()
            tileInfo.baseTerrain = naturalWonder.turnsInto!!
            tileInfo.terrainFeature = null
            tileInfo.resource = null
            tileInfo.improvement = null
        }

        if(tileInfo.terrainFeature!=null){
            val terrainFeature = tileInfo.getTerrainFeature()!!
            if(terrainFeature.occursOn!=null && !terrainFeature.occursOn.contains(tileInfo.baseTerrain))
                tileInfo.terrainFeature=null
        }
        if(tileInfo.resource!=null){
            val resource = tileInfo.getTileResource()
            if(resource.terrainsCanBeFoundOn.none { it==tileInfo.baseTerrain || it==tileInfo.terrainFeature })
                tileInfo.resource=null
        }
        if(tileInfo.improvement!=null) {
            if (tileInfo.improvement!!.startsWith("StartingLocation")) {
                if (tileInfo.isWater || tileInfo.getBaseTerrain().impassable)
                    tileInfo.improvement = null
            } else {
                val improvement = tileInfo.getTileImprovement()!!
                if (tileInfo.getBaseTerrain().impassable) tileInfo.improvement = null
                if (improvement.terrainsCanBeBuiltOn.isEmpty() && tileInfo.isWater)
                    tileInfo.improvement = null
                if (improvement.terrainsCanBeBuiltOn.isNotEmpty() // for "everywhere" improvements like city ruins, encampments, ancient ruins
                        && improvement.terrainsCanBeBuiltOn.none { it == tileInfo.baseTerrain || it == tileInfo.terrainFeature })
                    tileInfo.improvement = null
            }
        }
        if(tileInfo.getBaseTerrain().impassable || tileInfo.isWater)
            tileInfo.roadStatus= RoadStatus.None
    }


    private fun setCurrentHex(tileInfo: TileInfo, text:String){
        val tileGroup = TileGroup(tileInfo, TileSetStrings())
                .apply {
                    showEntireMap=true
                    forMapEditorIcon=true
                    update()
                }
        tileGroup.baseLayerGroup.moveBy(-10f, 10f)
        setCurrentHex(tileGroup,text)
    }

    private fun setCurrentHex(actor: Actor, text:String){
        currentHex.remove()
        val currentHexTable = Table()
        currentHexTable.add(text.toLabel()).padRight(30f)
        currentHexTable.add(actor)
        currentHexTable.pack()
        currentHex=currentHexTable
        currentHex.setPosition(stage.width - currentHex.width-10, 10f)
        stage.addActor(currentHex)
    }


}