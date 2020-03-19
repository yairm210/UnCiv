package com.unciv.models.translations

import com.badlogic.gdx.Gdx
import com.unciv.JsonParser
import com.unciv.models.ruleset.Nation
import java.nio.charset.Charset
import kotlin.collections.set
import com.badlogic.gdx.utils.Array

object TranslationFileReader {

    private const val percentagesFileLocation = "jsons/translations/completionPercentages.properties"
    const val templateFileLocation = "jsons/translations/template.properties"
    private val charset = Charset.forName("UTF-8").name()

    fun read(translationFile: String): LinkedHashMap<String, String> {
        val translations = LinkedHashMap<String, String>()
        val text = Gdx.files.internal(translationFile)
        text.reader(charset).forEachLine { line ->
            if(!line.contains(" = ")) return@forEachLine
            val splitLine = line.split(" = ")
            if(splitLine[1]!="") { // the value is empty, this means this wasn't translated yet
                val value = splitLine[1].replace("\\n","\n")
                val key = splitLine[0].replace("\\n","\n")
                translations[key] = value
            }
        }
        return translations
    }

    private fun writeByTemplate(language:String, translations: HashMap<String, String>){

        val templateFile = Gdx.files.internal(templateFileLocation)
        val linesFromTemplates = mutableListOf<String>()
        linesFromTemplates.addAll(templateFile.reader().readLines())
        linesFromTemplates.add("\n#################### Lines from Nations.json ####################\n")
        linesFromTemplates.addAll(generateNationsStrings())
        linesFromTemplates.add("\n#################### Lines from Tutorials.json ####################\n")
        linesFromTemplates.addAll(generateTutorialsStrings())

        val stringBuilder = StringBuilder()
        for(line in linesFromTemplates){
            if(!line.contains(" = ")){ // copy as-is
                stringBuilder.appendln(line)
                continue
            }
            val translationKey = line.split(" = ")[0].replace("\\n","\n")
            var translationValue = ""
            if(translations.containsKey(translationKey)) translationValue = translations[translationKey]!!
            else stringBuilder.appendln(" # Requires translation!")
            val lineToWrite = translationKey.replace("\n","\\n") +
                    " = "+ translationValue.replace("\n","\\n")
            stringBuilder.appendln(lineToWrite)
        }
        Gdx.files.local("jsons/translations/$language.properties")
                .writeString(stringBuilder.toString(),false,charset)
    }


    fun writeNewTranslationFiles(translations: Translations) {

        for (language in translations.getLanguages()) {
            val languageHashmap = HashMap<String, String>()

            for (translation in translations.values) {
                if (translation.containsKey(language))
                    languageHashmap[translation.entry] = translation[language]!!
            }
            writeByTemplate(language, languageHashmap)
        }
        writeLanguagePercentages(translations)
    }

    private fun writeLanguagePercentages(translations: Translations){
        val percentages = translations.calculatePercentageCompleteOfLanguages()
        val stringBuilder = StringBuilder()
        for(entry in percentages){
            stringBuilder.appendln(entry.key+" = "+entry.value)
        }
        Gdx.files.local(percentagesFileLocation).writeString(stringBuilder.toString(),false)
    }

    fun readLanguagePercentages():HashMap<String,Int>{

        val hashmap = HashMap<String,Int>()
        val percentageFile = Gdx.files.internal(percentagesFileLocation)
        if(!percentageFile.exists()) return hashmap
        for(line in percentageFile.reader().readLines()){
            val splitLine = line.split(" = ")
            hashmap[splitLine[0]]=splitLine[1].toInt()
        }
        return hashmap
    }

    fun generateNationsStrings(): Collection<String> {

        val nations = JsonParser().getFromJson(emptyArray<Nation>().javaClass, "jsons/Nations.json")
        val strings = mutableSetOf<String>() // using set to avoid duplicates

        for (nation in nations) {
            for (field in nation.javaClass.declaredFields
                    .filter { it.type == String::class.java || it.type == java.util.ArrayList::class.java }) {
                field.isAccessible = true
                val fieldValue = field.get(nation)
                if (field.name != "startBias" && // skip fields which must not be translated
                        fieldValue != null && fieldValue != "") {

                    if (fieldValue is ArrayList<*>) {
                        for (item in fieldValue)
                            strings.add("$item = ")
                    } else
                        strings.add("$fieldValue = ")
                }
            }
        }
        return strings
    }

    fun generateTutorialsStrings(): Collection<String> {

        val tutorials = JsonParser().getFromJson(LinkedHashMap<String, Array<String>>().javaClass, "jsons/Tutorials.json")
        val strings = mutableSetOf<String>() // using set to avoid duplicates

        for (tutorial in tutorials) {
            for (str in tutorial.value)
                    if (str != "") strings.add("$str = ")
        }
        return strings
    }

}