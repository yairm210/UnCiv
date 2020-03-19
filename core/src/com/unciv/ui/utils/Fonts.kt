package com.unciv.ui.utils

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.unciv.UncivGame
import core.java.nativefont.NativeFont
import core.java.nativefont.NativeFontPaint

class Fonts {
    // caches for memory and time saving
    companion object {
        val characterSetCache = HashMap<String, String>()
        val fontCache = HashMap<String, BitmapFont>()
    }

    fun getCharactersForFont(language:String=""): String {
        if (characterSetCache.containsKey(language)) return characterSetCache[language]!!

        val startTime = System.currentTimeMillis()

        val defaultText = "AÀÁBCČĆDĐEÈÉFGHIÌÍÏJKLMNOÒÓÖPQRSŠTUÙÚÜVWXYZŽaäàâăbcčćçdđeéèfghiìîjklmnoòöpqrsșštțuùüvwxyzž" +
                "АБВГҐДЂЕЁЄЖЗЅИІЇЙЈКЛЉМНЊОПРСТЋУЎФХЦЧЏШЩЪЫЬЭЮЯабвгґдђеёєжзѕиіїйјклљмнњопрстћуўфхцчџшщъыьэюя" + // Russian
                "ΑΒΓΔΕΖΗΘΙΚΛΜΝΞΟΠΡΣΤΥΦΧΨΩαβγδεζηθικλμνξοπρστυφχψωάßΆέΈέΉίϊΐΊόΌύΰϋΎΫΏ" +  // Greek
                "ÀÄĂÂĎÊĚÉÈÍÎŁĹĽÔÓÖƠŘŔŚŤƯŮÚÜÝŻŹäâąďêęěłĺľńňñôöơřŕśťưůýżźáèìíóûú" +
                "กขฃคฅฆงจฉชซฌญฎฏฐฑฒณดตถทธนบปผฝพฟภมยรฤลฦวศษสหฬอฮฯะัาำิีึืฺุู฿เแโใไๅๆ็่้๊๋์ํ๎๏๐๑๒๓๔๕๖๗๘๙๚๛" +  // Thai
                "İıÇŞşĞğ"+ /// turkish
                "1234567890" +
                "‘?ʼ’'“!”(%)[#]{@}/&\\<-+÷×=>®©\$€£¥¢:;,.…¡*|«»—∞✘✔"
        val charSet = HashSet<Char>()
        charSet.addAll(defaultText.asIterable())

        if (language != "") {
            for (entry in UncivGame.Current.translations.entries) {
                for (lang in entry.value) {
                    if (lang.key == language) charSet.addAll(lang.value.asIterable())
                }
            }
        }
        val characterSetString = charSet.joinToString("")
        characterSetCache[language]=characterSetString

        val totalTime = System.currentTimeMillis() - startTime
        println("Loading characters for font - "+totalTime+"ms")

        return characterSetString
    }

   fun getFont(size: Int): BitmapFont {
       val language = UncivGame.Current.settings.language
       val fontForLanguage ="Nativefont"
       val isUniqueFont = language.contains("Chinese") || language == "Korean" || language=="Japanese"
       val keyForFont = if(!isUniqueFont) "$fontForLanguage $size" else "$fontForLanguage $size $language"
       if (fontCache.containsKey(keyForFont)) return fontCache[keyForFont]!!

       val font=NativeFont(NativeFontPaint(size))
       val charsForFont = getCharactersForFont(if(isUniqueFont) language else "")


       font.appendText(charsForFont)


       fontCache[keyForFont] = font
       return font
   }
}
