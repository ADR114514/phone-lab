package com.mobilelab.core.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {

    @Test
    fun primitivesAndEscaping() {
        val json = JsonObject().put("s", "a\"b\\c").put("i", 3).put("b", true).put("n", null).toJson()
        assertTrue(json.contains("\"s\":\"a\\\"b\\\\c\""))
        assertTrue(json.contains("\"i\":3"))
        assertTrue(json.contains("\"b\":true"))
        assertTrue(json.contains("\"n\":null"))
    }

    @Test
    fun nestedObjectsAndArrays() {
        val inner = JsonObject().put("x", 1.5)
        val json = JsonObject().put("obj", inner).put("list", listOf(1, "two")).toJson()
        assertEquals("{\"obj\":{\"x\":1.5},\"list\":[1,\"two\"]}", json)
    }

    @Test
    fun nonFiniteNumbersBecomeNull() {
        assertEquals("{\"v\":null}", JsonObject().put("v", Double.NaN).toJson())
    }
}
