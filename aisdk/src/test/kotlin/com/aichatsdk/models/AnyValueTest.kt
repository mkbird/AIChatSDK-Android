package com.aichatsdk.models

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AnyValueTest {

    @Test fun `roundtrip primitives`() {
        listOf(
            AnyValue.Str("hello"),
            AnyValue.IntV(42),
            AnyValue.IntV(-1),
            AnyValue.DoubleV(3.14),
            AnyValue.Bool(true),
            AnyValue.Bool(false),
            AnyValue.Null,
        ).forEach { v ->
            val json = wrapAndDump(v)
            val parsed = parseAndUnwrap(json)
            assertThat(parsed).isEqualTo(v)
        }
    }

    @Test fun `roundtrip nested object with arrays and nulls`() {
        val v = AnyValue.Obj(linkedMapOf(
            "name" to AnyValue.Str("china"),
            "age" to AnyValue.IntV(5000),
            "tags" to AnyValue.Arr(listOf(AnyValue.Str("a"), AnyValue.IntV(1), AnyValue.Null)),
            "meta" to AnyValue.Obj(mapOf("x" to AnyValue.Bool(true))),
        ))
        val parsed = parseAndUnwrap(wrapAndDump(v))
        assertThat(parsed).isEqualTo(v)
    }

    @Test fun `accessor helpers`() {
        val s = AnyValue.Str("x")
        assertThat(s.stringValue).isEqualTo("x")
        assertThat(s.intValue).isNull()

        val n = AnyValue.IntV(7)
        assertThat(n.intValue).isEqualTo(7L)
        assertThat(n.doubleValue).isEqualTo(7.0)

        val arr = AnyValue.Arr(listOf(AnyValue.IntV(1)))
        assertThat(arr.arrayValue).hasSize(1)
    }

    @Test fun `of() handles raw kotlin values`() {
        assertThat(AnyValue.of(null)).isEqualTo(AnyValue.Null)
        assertThat(AnyValue.of(true)).isEqualTo(AnyValue.Bool(true))
        assertThat(AnyValue.of(1)).isEqualTo(AnyValue.IntV(1))
        assertThat(AnyValue.of(1L)).isEqualTo(AnyValue.IntV(1))
        assertThat(AnyValue.of(1.5)).isEqualTo(AnyValue.DoubleV(1.5))
        assertThat(AnyValue.of("x")).isEqualTo(AnyValue.Str("x"))
        assertThat(AnyValue.of(listOf(1, 2))).isEqualTo(AnyValue.Arr(listOf(AnyValue.IntV(1), AnyValue.IntV(2))))
    }

    private fun wrapAndDump(v: AnyValue): String = ChatEventCodec.toJson(
        ChatEvent("t", payload = mapOf("v" to v))
    )

    private fun parseAndUnwrap(json: String): AnyValue =
        ChatEventCodec.fromJson(json).payload["v"] ?: AnyValue.Null
}
