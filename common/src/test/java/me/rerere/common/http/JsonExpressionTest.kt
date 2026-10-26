package me.rerere.common.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class JsonExpressionTest {
    private val locales = listOf(Locale.US, Locale.GERMANY, Locale.FRANCE, Locale.CHINA)

    private fun forEachNumberLocale(block: () -> Unit) {
        val original = Locale.getDefault(Locale.Category.FORMAT)
        try {
            locales.forEach { locale ->
                Locale.setDefault(Locale.Category.FORMAT, locale)
                block()
            }
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, original)
        }
    }

    @Test
    fun `numeric paths do not depend on the system decimal separator`() = forEachNumberLocale {
        val root = Json.parseToJsonElement("""{"data":{"amount":12.3456}}""").jsonObject

        assertEquals("12.3456", evaluateJsonExpr("data.amount", root))
    }

    @Test
    fun `arithmetic keeps sub cent precision until final formatting`() = forEachNumberLocale {
        val root = Json.parseToJsonElement("""{"credits":0.00125,"scale":1000}""").jsonObject

        assertEquals("1.25", evaluateJsonExpr("credits * scale", root))
    }

    @Test
    fun `large numeric balances retain double precision`() = forEachNumberLocale {
        val root = Json.parseToJsonElement("""{"balance":16777217.125}""").jsonObject

        assertEquals(16777217.125, evaluateJsonExpr("balance", root).toDouble(), 0.0)
    }
}
