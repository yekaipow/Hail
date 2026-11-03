package com.aistra.hail.utils

import android.os.Build
import androidx.annotation.RequiresApi
import java.util.Locale

/**
 * 汉字转拼音（无声调、小写）。
 *
 * 用系统 ICU 的 Han-Latin 转换器代替 pinyin4j：后者内置一份完整的汉字-拼音字典（约 300KB），
 * 而 ICU 是系统自带的，不占 APK 体积。
 *
 * API 24 以下没有 android.icu，此时 [pinyinOf] 返回 null —— 拼音/九宫格搜索退化为原文匹配。
 */
object HPinyin {
    /** 逐字结果缓存：搜索结果里同一个字会被反复查询 */
    private val cache = HashMap<Char, String?>()

    /** 该字符的拼音；非汉字或无法转换返回 null */
    fun pinyinOf(ch: Char): String? = cache.getOrPut(ch) {
        if (!isCjk(ch)) return@getOrPut null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@getOrPut null
        runCatching { Icu.convert(ch.toString()) }
            .getOrNull()
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }
    }

    /** 兼容 pinyin4j 的调用形态：多音字这里只返回一个最常用读音 */
    fun pinyinArray(ch: Char): Array<String>? = pinyinOf(ch)?.let { arrayOf(it) }

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF || code in 0xF900..0xFAFF
    }

    /**
     * ICU 只在 API 24+ 存在。放在嵌套 object 里是为了让类加载延迟到真正调用时，
     * 低版本设备不会因为解析 android.icu 类而崩溃。
     */
    @RequiresApi(Build.VERSION_CODES.N)
    private object Icu {
        private val transliterator =
            android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII")

        fun convert(text: String): String = transliterator.transliterate(text)
    }
}
