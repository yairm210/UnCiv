package com.unciv.models.tilesets

import com.badlogic.gdx.graphics.Color

class TileSetConfig {
    var useColorAsBaseTerrain = true
    var unexploredTileColor: Color = Color.DARK_GRAY
    var fogOfWarColor: Color = Color.BLACK
    var ruleVariants: LinkedHashMap<String, List<String>> = LinkedHashMap() // Must be a LinkedHashMap to retain order on deserialization
    private var templateIndicator: Char = '?'

    @Transient
    private val templatedRuleVariants: LinkedHashMap<TileComposition, List<String>> = LinkedHashMap()
    @Transient
    private val templateDictionary = HashMap<String, Sequence<String?>>()

    fun updateConfig(other: TileSetConfig){
        useColorAsBaseTerrain = other.useColorAsBaseTerrain
        unexploredTileColor = other.unexploredTileColor
        fogOfWarColor = other.fogOfWarColor
        templateIndicator = other.templateIndicator
        for ((tileSetString, renderOrder) in other.ruleVariants){
            ruleVariants[tileSetString] = renderOrder
        }
        setTransients()
    }

    fun setTransients(){
        val ruleVariantsToRemove = ArrayList<String>()

        for ((tileCompositionString, renderOrder) in ruleVariants){
            if (tileCompositionString.contains(templateIndicator)){
                ruleVariantsToRemove.add(tileCompositionString)
                val splitTileCompString = tileCompositionString.split('+')
                if (splitTileCompString.size < 5)
                    continue // forcing the existence of a full composition makes handling templated rule variants easier
                templatedRuleVariants[toTileComposition(splitTileCompString)] = renderOrder
            }
        }

        //remove all templatedRuleVariants from ruleVariants
        for (ruleVariant in ruleVariantsToRemove)
            ruleVariants.remove(ruleVariant)
    }

    /**
     * If a template is found the tile composition will be added to ruleVariants. The sequences must contain null
     * for each element which is not existing.
     * @param[resAndImpSequence] should contain exactly two elements. Everything in between the first and the last will not be considered
     * @return true if a templated rule variant exists which matches the given sequences.
     */
    fun generateRuleVariant(terrainSequence: Sequence<String?>, resAndImpSequence: Sequence<String?>): Boolean {
        val baseTerrain = terrainSequence.first()
        val terrainFeatures = terrainSequence.drop(1)
        val resource = resAndImpSequence.first()
        val improvement = resAndImpSequence.last()

        for ((tileComposition, renderOrder) in templatedRuleVariants){
            templateDictionary.clear()

            //check if template matches
            if (!ruleMatches(tileComposition.baseTerrain, baseTerrain) ||
                    !ruleMatches(tileComposition.terrainFeatures, terrainFeatures) ||
                    !ruleMatches(tileComposition.resource, resource) ||
                    !ruleMatches(tileComposition.improvement, improvement))
                continue

            //generate map output for composition
            val finalRenderOrder = ArrayList<String>()
            for (element in renderOrder){
                if (!isTemplate(element))
                    finalRenderOrder.add(element)
                else if (templateDictionary[element] != null)
                    finalRenderOrder.addAll(templateDictionary[element]!!.filterNotNull())
            }

            // We add it to ruleVariants to save time next time we search for this composition.
            ruleVariants[(terrainSequence + resAndImpSequence).filterNotNull().joinToString("+")] = finalRenderOrder

            return true
        }

        return false
    }

    /**
     * Checks if tileElements matches ruleElements and adds them to the template dictionary, if necessary.
     * @param[ruleElements] must contain exactly two template strings(starting with template indicator). One at the front and one at the end.
     */
    private fun ruleMatches(ruleElements: List<String>, tileElements: Sequence<String?>): Boolean {
        var startTemplateStrings = sequenceOf<String?>()
        var endTemplateStrings = sequenceOf<String?>()
        val iterator = tileElements.iterator()
        var nextMustMatch = false

        //We look at all rule elements which are no templates
        nextRuleElement@for (ruleIndex in 1 .. ruleElements.size - 2){
            //we check all tileElements until we find a match for this rule element
            while (iterator.hasNext()){
                val next = iterator.next()
                if (next != ruleElements[ruleIndex]) {
                    // we had a match before but this one is non -> return false
                    if (nextMustMatch)
                        return false
                    // if non matched yet -> add to start template
                    startTemplateStrings += next
                } else {
                    //After the first match all following once must match
                    nextMustMatch = true
                    continue@nextRuleElement
                }
            }
            // if there are still rule elements but no tile elements this template can not match
            return false
        }

        //Add all remaining elements to end template
        while (iterator.hasNext())
            endTemplateStrings += iterator.next()

        templateDictionary[ruleElements.first()] = startTemplateStrings
        templateDictionary[ruleElements.last()] = endTemplateStrings

        return true
    }

    /**
     * Checks if tileElement matches the ruleElement and adds it to the template dictionary, if necessary.
     */
    private fun ruleMatches(ruleElement: String, tileElement: String?): Boolean {
        if (isTemplate(ruleElement)){
            templateDictionary[ruleElement] = sequenceOf(tileElement)
            return true
        }
        return ruleElement == tileElement
    }

    private fun isTemplate(string: String) = string.startsWith(templateIndicator)


    private data class TileComposition(val baseTerrain: String, val terrainFeatures: List<String>, val resource: String, val improvement: String)
    private fun toTileComposition(input: List<String>) =
            TileComposition(
                    input.first(),
                    input.slice(1..input.size-3),
                    input[input.size-2],
                    input.last()
            )
}