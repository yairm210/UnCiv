package com.unciv.models.translations

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.utils.Array
import com.unciv.JsonParser
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.*
import com.unciv.models.ruleset.tech.TechColumn
import com.unciv.models.ruleset.tile.Terrain
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.ruleset.unit.Promotion
import com.unciv.models.ruleset.unit.UnitType
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import java.lang.reflect.Field

object TranslationFileWriter {

    private const val specialNewLineCode = "# This is an empty line "
    const val templateFileLocation = "jsons/translations/template.properties"
    private const val languageFileLocation = "jsons/translations/%s.properties"

    fun writeNewTranslationFiles(translations: Translations) {

        val percentages = generateTranslationFiles(translations)
        writeLanguagePercentages(percentages)

        // try to do the same for the mods
        for (modFolder in Gdx.files.local("mods").list().filter { it.isDirectory })
            generateTranslationFiles(translations, modFolder)
        // write percentages is not needed: for an individual mod it makes no sense

    }

    private fun getFileHandle(modFolder: FileHandle?, fileLocation: String) =
            if (modFolder != null) modFolder.child(fileLocation)
            else Gdx.files.local(fileLocation)

    private fun generateTranslationFiles(translations: Translations, modFolder: FileHandle? = null): HashMap<String, Int> {

        val fileNameToGeneratedStrings = LinkedHashMap<String, MutableSet<String>>()
        val linesFromTemplates = mutableListOf<String>()

        if (modFolder == null) { // base game
            val templateFile = getFileHandle(modFolder, templateFileLocation) // read the template
            if (templateFile.exists())
                linesFromTemplates.addAll(templateFile.reader().readLines())

            for (baseRuleset in BaseRuleset.values()) {
                val generatedStringsFromBaseRuleset =
                        generateStringsFromJSONs(Gdx.files.local("jsons/${baseRuleset.fullName}"))
                for (entry in generatedStringsFromBaseRuleset)
                    fileNameToGeneratedStrings[entry.key + " from " + baseRuleset.fullName] = entry.value
            }

            fileNameToGeneratedStrings["Tutorials"] = generateTutorialsStrings()
        } else fileNameToGeneratedStrings.putAll(generateStringsFromJSONs(modFolder))

        // Tutorials are a bit special
        if (modFolder == null)          // this is for base only, not mods

            for (key in fileNameToGeneratedStrings.keys) {
                linesFromTemplates.add("\n#################### Lines from $key ####################\n")
                linesFromTemplates.addAll(fileNameToGeneratedStrings.getValue(key))
            }

        var countOfTranslatableLines = 0
        val countOfTranslatedLines = HashMap<String, Int>()

        // iterate through all available languages
        for (language in translations.getLanguages()) {
            var translationsOfThisLanguage = 0
            val stringBuilder = StringBuilder()

            // This is so we don't add the same keys twice if we have the same value in both Vanilla and G&K
            val existingTranslationKeys = HashSet<String>()

            for (line in linesFromTemplates) {
                if (!line.contains(" = ")) {
                    // small hack to insert empty lines
                    if (line.startsWith(specialNewLineCode)) {
                        if (!stringBuilder.endsWith("\r\n\r\n")) // don't double-add line breaks -
                        // this stops lots of line breaks between removed translations in G&K
                            stringBuilder.appendLine()
                    } else // copy as-is
                        stringBuilder.appendLine(line)
                    continue
                }

                val translationKey = line.split(" = ")[0].replace("\\n", "\n")
                val hashMapKey = if (translationKey.contains('['))
                    translationKey.replace(squareBraceRegex, "[]")
                else translationKey

                if (existingTranslationKeys.contains(hashMapKey)) continue // don't add it twice
                existingTranslationKeys.add(hashMapKey)

                // count translatable lines only once (e.g. for English)
                if (language == "English") countOfTranslatableLines++

                var translationValue = ""

                val translationEntry = translations[hashMapKey]
                if (translationEntry != null && translationEntry.containsKey(language)) {
                    translationValue = translationEntry[language]!!
                    translationsOfThisLanguage++
                } else stringBuilder.appendLine(" # Requires translation!")

                // THE PROBLEM
                // When we come to change params written in the TranslationFileWriter,
                //  this messes up the param name matching in existing translations.
                // Tests fail and much manual work was required.
                // SO, as a fix, for each translation where a single param is different than in the source line,
                // we try to autocorrect it.
                if (translationValue.contains('[')) {
                    val paramsOfKey = translationKey.getPlaceholderParameters()
                    val paramsOfValue = translationValue.getPlaceholderParameters()
                    val paramsOfKeyNotInValue = paramsOfKey.filterNot { it in paramsOfValue }
                    val paramsOfValueNotInKey = paramsOfValue.filterNot { it in paramsOfKey }
                    if (paramsOfKeyNotInValue.size == 1 && paramsOfValueNotInKey.size == 1)
                        translationValue = translationValue.replace(
                            "[" + paramsOfValueNotInKey.first() + "]",
                            "[" + paramsOfKeyNotInValue.first() + "]"
                        )
                }

                val lineToWrite = translationKey.replace("\n", "\\n") +
                        " = " + translationValue.replace("\n", "\\n")
                stringBuilder.appendLine(lineToWrite)
            }

            countOfTranslatedLines[language] = translationsOfThisLanguage

            val fileWriter = getFileHandle(modFolder, languageFileLocation.format(language))
            fileWriter.writeString(stringBuilder.toString(), false, TranslationFileReader.charset)
        }

        // Calculate the percentages of translations
        // It should be done after the loop of languages, since the countOfTranslatableLines is not known in the 1st iteration
        for (key in countOfTranslatedLines.keys)
            countOfTranslatedLines[key] = if (countOfTranslatableLines > 0) countOfTranslatedLines.getValue(key) * 100 / countOfTranslatableLines
            else 100

        return countOfTranslatedLines
    }

    private fun writeLanguagePercentages(percentages: HashMap<String, Int>) {
        val stringBuilder = StringBuilder()
        for (entry in percentages) {
            stringBuilder.appendLine(entry.key + " = " + entry.value)
        }
        Gdx.files.local(TranslationFileReader.percentagesFileLocation).writeString(stringBuilder.toString(), false)
    }


    private fun generateTutorialsStrings(): MutableSet<String> {

        val tutorialsStrings = mutableSetOf<String>()
        val tutorials = JsonParser().getFromJson(LinkedHashMap<String, Array<String>>().javaClass, "jsons/Tutorials.json")

        var uniqueIndexOfNewLine = 0
        for (tutorial in tutorials) {
            if (!tutorial.key.startsWith('_'))
                tutorialsStrings.add("${tutorial.key.replace('_', ' ')} = ")
            for (str in tutorial.value)
                if (str != "") tutorialsStrings.add("$str = ")
            // This is a small hack to insert multiple /n into the set, which can't contain identical lines
            tutorialsStrings.add("$specialNewLineCode ${uniqueIndexOfNewLine++}")
        }
        return tutorialsStrings
    }

    // used for unit test only
    fun getGeneratedStringsSize(): Int {
        return generateStringsFromJSONs(Gdx.files.local("jsons/Civ V - Vanilla")).values.sumBy { // exclude empty lines
            it.count { line: String -> !line.startsWith(specialNewLineCode) }
        }
    }

    private fun generateStringsFromJSONs(jsonsFolder: FileHandle): LinkedHashMap<String, MutableSet<String>> {

        // Using LinkedHashMap (instead of HashMap) is important to maintain the order of sections in the translation file
        val generatedStrings = LinkedHashMap<String, MutableSet<String>>()

        var uniqueIndexOfNewLine = 0
        val jsonParser = JsonParser()
        val listOfJSONFiles = jsonsFolder
                .list { file -> file.name.endsWith(".json", true) }
                .sortedBy { it.name() }       // generatedStrings maintains order, so let's feed it a predictable one

        for (jsonFile in listOfJSONFiles) {
            val filename = jsonFile.nameWithoutExtension()

            val javaClass = getJavaClassByName(filename)
            if (javaClass == this.javaClass)
                continue // unknown JSON, let's skip it

            val array = jsonParser.getFromJson(javaClass, jsonFile.path())

            generatedStrings[filename] = mutableSetOf()
            val resultStrings = generatedStrings[filename]

            fun submitString(item: Any) {
                val string = item.toString()

                val parameters = string.getPlaceholderParameters()
                var stringToTranslate = string

                val existingParameterNames = HashSet<String>()
                if (parameters.any()) {
                    for (parameter in parameters) {
                        var parameterName = when {
                            parameter.toIntOrNull() != null -> "amount"
                            Stat.values().any { it.name == parameter } -> "stat"
                            RulesetCache.getBaseRuleset().terrains.containsKey(parameter) 
                                    || parameter == "Friendly Land"
                                    || parameter == "Foreign Land"
                                    || parameter == "Fresh water"
                                    || parameter == "non-fresh water"
                                    || parameter == "Open Terrain"
                                    || parameter == "Rough Terrain"
                            -> "tileFilter"
                            RulesetCache.getBaseRuleset().units.containsKey(parameter) -> "unit"
                            RulesetCache.getBaseRuleset().tileImprovements.containsKey(parameter)
                                    || parameter == "Great Improvement"
                            -> "tileImprovement"
                            RulesetCache.getBaseRuleset().tileResources.containsKey(parameter) -> "resource"
                            RulesetCache.getBaseRuleset().technologies.containsKey(parameter) -> "tech"
                            RulesetCache.getBaseRuleset().unitPromotions.containsKey(parameter) -> "promotion"
                            RulesetCache.getBaseRuleset().buildings.containsKey(parameter)
                                    || parameter == "Wonders" 
                                    || parameter == "Wonder"
                                    || parameter == "Buildings"
                                    || parameter == "Building"
                            -> "building"

                            UnitType.values().any { it.name == parameter } 
                                    || parameter == "Military" 
                                    || parameter == "Civilian"
                                    || parameter == "non-air"
                                    || parameter == "relevant"
                                    || parameter == "Nuclear Weapon"
                                    || parameter == "Submarine"
                                // These are up for debate
                                    || parameter == "Air"
                                    || parameter == "land units"
                                    || parameter == "water units"
                                    || parameter == "air units"
                                    || parameter == "military units"
                                    || parameter == "submarine units"
                                // Note: this can't handle combinations of parameters (e.g. [{Military} {Water}])
                            -> "unitType"
                            Stats.isStats(parameter) -> "stats"
                            parameter == "in this city"
                                    || parameter == "in all cities"
                                    || parameter == "in all coastal cities"
                                    || parameter == "in capital"
                                    || parameter == "in all non-occupied cities"
                                    || parameter == "in all cities with a world wonder"
                                    || parameter == "in all cities connected to capital"
                                    || parameter == "in all cities with a garrison"
                                    || parameter == "in all cities in which the majority religion is a major religion"
                            -> "cityFilter"
                            else -> "param"
                        }
                        if (parameterName in existingParameterNames) {
                            var i = 2
                            while (parameterName + i in existingParameterNames) i++
                            parameterName += i
                        }
                        existingParameterNames += parameterName

                        stringToTranslate = stringToTranslate.replaceFirst(parameter, parameterName)
                    }
                }
                resultStrings!!.add("$stringToTranslate = ")
                return
            }

            fun serializeElement(element: Any) {
                val allFields = (element.javaClass.declaredFields + element.javaClass.fields
                        + element.javaClass.superclass.declaredFields) // This is so the main PolicyBranch, which inherits from Policy, will recognize its Uniques and have them translated
                        .filter {
                            it.type == String::class.java ||
                                    it.type == java.util.ArrayList::class.java ||
                                    it.type == java.util.HashSet::class.java
                        }
                for (field in allFields) {
                    field.isAccessible = true
                    val fieldValue = field.get(element)
                    if (isFieldTranslatable(field, fieldValue)) { // skip fields which must not be translated
                        // this field can contain sub-objects, let's serialize them as well
                        if (fieldValue is java.util.AbstractCollection<*>) {
                            for (item in fieldValue)
                                if (item is String) submitString(item) else serializeElement(item!!)
                        } else
                            submitString(fieldValue)
                    }
                }
            }

            if (array is kotlin.Array<*>)
                for (element in array) {
                    serializeElement(element!!) // let's serialize the strings recursively
                    // This is a small hack to insert multiple /n into the set, which can't contain identical lines
                    resultStrings!!.add("$specialNewLineCode ${uniqueIndexOfNewLine++}")
                }
        }
        return generatedStrings
    }

    private val untranslatableFieldSet = setOf(
            "aiFreeTechs", "aiFreeUnits", "attackSound", "building",
            "cannotBeBuiltWith", "cultureBuildings", "improvement", "improvingTech",
            "obsoleteTech", "occursOn", "prerequisites", "promotions",
            "providesFreeBuilding", "replaces", "requiredBuilding", "requiredBuildingInAllCities",
            "requiredNearbyImprovedResources", "requiredResource", "requiredTech", "requires",
            "resourceTerrainAllow", "revealedBy", "startBias", "techRequired",
            "terrainsCanBeBuiltOn", "terrainsCanBeFoundOn", "turnsInto", "uniqueTo", "upgradesTo"
    )

    private fun isFieldTranslatable(field: Field, fieldValue: Any?): Boolean {
        // Exclude fields by name that contain references to items defined elsewhere
        // - the definition should cause the inclusion in our translatables list, not the reference.
        // This prevents duplication within the base game (e.g. Mines were duplicated by being output
        // by both TerrainResources and TerrainImprovements) and duplication of base game items into
        return fieldValue != null &&
                fieldValue != "" &&
                field.name !in untranslatableFieldSet
    }

    private fun getJavaClassByName(name: String): Class<Any> {
        return when (name) {
            "Buildings" -> emptyArray<Building>().javaClass
            "Difficulties" -> emptyArray<Difficulty>().javaClass
            "Nations" -> emptyArray<Nation>().javaClass
            "Policies" -> emptyArray<PolicyBranch>().javaClass
            "Quests" -> emptyArray<Quest>().javaClass
            "Specialists" -> emptyArray<Specialist>().javaClass
            "Techs" -> emptyArray<TechColumn>().javaClass
            "Terrains" -> emptyArray<Terrain>().javaClass
            "TileImprovements" -> emptyArray<TileImprovement>().javaClass
            "TileResources" -> emptyArray<TileResource>().javaClass
            "Tutorials" -> this.javaClass // dummy value
            "UnitPromotions" -> emptyArray<Promotion>().javaClass
            "Units" -> emptyArray<BaseUnit>().javaClass
            else -> this.javaClass // dummy value
        }
    }

}