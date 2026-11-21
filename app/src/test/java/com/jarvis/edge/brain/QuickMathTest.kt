package com.jarvis.edge.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class QuickMathTest {

    @Test
    fun convertsMilesToKilometers() {
        assertEquals("10 miles is 16.09 kilometers.", QuickMath.answer("Convert ten miles to kilometers."))
    }

    @Test
    fun convertsPoundToGramsWithArticle() {
        assertEquals("1 pound is 453.59 grams.", QuickMath.answer("how many grams in a pound"))
    }

    @Test
    fun convertsFahrenheitToCelsius() {
        assertEquals("100 degrees Fahrenheit is 37.78 degrees Celsius.", QuickMath.answer("100 fahrenheit to celsius"))
    }

    @Test
    fun multipliesDigits() {
        assertEquals("25 times 4 is 100.", QuickMath.answer("What is 25 times 4?"))
    }

    @Test
    fun squareRootOfNumberWords() {
        assertEquals("The square root of 144 is 12.", QuickMath.answer("what's the square root of one hundred and forty four"))
    }

    @Test
    fun percentOf() {
        assertEquals("15 percent of 200 is 30.", QuickMath.answer("15 percent of 200"))
    }

    @Test
    fun divideByZero() {
        assertEquals("You can't divide by zero.", QuickMath.answer("144 divided by 0"))
    }

    @Test
    fun decimalWordsInConversion() {
        assertEquals("2.5 kilometers is 2500 meters.", QuickMath.answer("two point five kilometers in meters"))
    }

    @Test
    fun extraPhrasings() {
        assertEquals("10 miles is 16.09 kilometers.", QuickMath.answer("how many kilometres are in ten miles"))
        assertEquals("10 miles is 16.09 kilometers.", QuickMath.answer("10 miles in km"))
        assertEquals("25 times 4 is 100.", QuickMath.answer("what's twenty five multiplied by four"))
        assertEquals("2 to the power of 10 is 1024.", QuickMath.answer("2 to the power of 10"))
        assertEquals("1000 plus 5 is 1005.", QuickMath.answer("1,000 plus 5"))
    }

    @Test
    fun notAMathOrConversionRequest() {
        assertNull(QuickMath.answer("What is the capital of France?"))
        assertNull(QuickMath.answer("Tell me a joke."))
        assertNull(QuickMath.answer("Set a timer for five minutes."))
        assertNull(QuickMath.answer("How many legs does a spider have?"))
        assertNull(QuickMath.answer("convert ten miles to kilograms"))
        assertNull(QuickMath.answer("what time is it"))
    }

    @Test
    fun `everyday requests are left to the rest of the assistant`() {
        val math = setOf(
            "What is the square root of one hundred and forty four?",
            "Convert ten miles to kilometres.",
            "How much is twenty five times four?",
            "What's fifteen percent of two thousand?",
            "How many grams are in a kilo?",
        )
        val wronglyAnswered = com.jarvis.edge.core.VoicePrompts.SENTENCES
            .filter { it !in math }
            .filter { QuickMath.answer(it) != null }
        assertEquals(emptyList<String>(), wronglyAnswered)
        math.forEach { org.junit.jupiter.api.Assertions.assertNotNull(QuickMath.answer(it), it) }
    }
}
