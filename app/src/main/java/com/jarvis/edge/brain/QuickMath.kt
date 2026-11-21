package com.jarvis.edge.brain

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Deterministic, offline answers to unit conversions and simple arithmetic in a
 * speech transcript. The on-device LLM is unreliable at both, so the voice
 * pipeline asks [answer] first. Returns null unless the whole request parses.
 */
object QuickMath {

    fun answer(text: String): String? {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return null
        return arithmetic(tokens) ?: conversion(tokens)
    }

    // ---- Units -------------------------------------------------------------

    private enum class Dim { LENGTH, MASS, VOLUME, TEMP, TIME, SPEED, DATA }

    /** [factor] scales a value to the dimension's base unit; [offset] is added after scaling (temperature). */
    private class Measure(
        val dim: Dim,
        val factor: Double,
        val singular: String,
        val plural: String,
        val offset: Double = 0.0,
    )

    private val SECOND = Measure(Dim.TIME, 1.0, "second", "seconds")
    private val MINUTE = Measure(Dim.TIME, 60.0, "minute", "minutes")
    private val HOUR = Measure(Dim.TIME, 3600.0, "hour", "hours")
    private val METER = Measure(Dim.LENGTH, 1.0, "meter", "meters")
    private val KILOMETER = Measure(Dim.LENGTH, 1000.0, "kilometer", "kilometers")
    private val MILE = Measure(Dim.LENGTH, 1609.344, "mile", "miles")
    private val GRAM = Measure(Dim.MASS, 1.0, "gram", "grams")
    private val POUND = Measure(Dim.MASS, 453.59237, "pound", "pounds")
    private val LITER = Measure(Dim.VOLUME, 1.0, "liter", "liters")

    private val units: Map<String, Measure> = buildMap<String, Measure> {
        fun add(keys: String, m: Measure) = keys.split(' ').forEach { put(it, m) }
        add("mm millimeter millimeters millimetre millimetres", Measure(Dim.LENGTH, 0.001, "millimeter", "millimeters"))
        add("cm centimeter centimeters centimetre centimetres", Measure(Dim.LENGTH, 0.01, "centimeter", "centimeters"))
        add("m meter meters metre metres", METER)
        add("km kilometer kilometers kilometre kilometres", KILOMETER)
        add("in inch inches", Measure(Dim.LENGTH, 0.0254, "inch", "inches"))
        add("ft foot feet", Measure(Dim.LENGTH, 0.3048, "foot", "feet"))
        add("yd yard yards", Measure(Dim.LENGTH, 0.9144, "yard", "yards"))
        add("mi mile miles", MILE)
        add("mg milligram milligrams", Measure(Dim.MASS, 0.001, "milligram", "milligrams"))
        add("g gram grams gramme grammes", GRAM)
        add("kg kilo kilos kilogram kilograms", Measure(Dim.MASS, 1000.0, "kilogram", "kilograms"))
        add("oz ounce ounces", Measure(Dim.MASS, 28.349523125, "ounce", "ounces"))
        add("lb lbs pound pounds", POUND)
        add("stone stones", Measure(Dim.MASS, 6350.29318, "stone", "stones"))
        add("ton tons tonne tonnes", Measure(Dim.MASS, 1_000_000.0, "metric ton", "metric tons"))
        add("ml milliliter milliliters millilitre millilitres", Measure(Dim.VOLUME, 0.001, "milliliter", "milliliters"))
        add("l liter liters litre litres", LITER)
        add("gallon gallons", Measure(Dim.VOLUME, 3.78541, "gallon", "gallons"))
        add("cup cups", Measure(Dim.VOLUME, 0.236588, "cup", "cups"))
        add("pint pints", Measure(Dim.VOLUME, 0.473176, "pint", "pints"))
        add("teaspoon teaspoons tsp", Measure(Dim.VOLUME, 0.00492892, "teaspoon", "teaspoons"))
        add("tablespoon tablespoons tbsp", Measure(Dim.VOLUME, 0.0147868, "tablespoon", "tablespoons"))
        add("celsius centigrade", Measure(Dim.TEMP, 1.0, "degree Celsius", "degrees Celsius", 273.15))
        add("fahrenheit", Measure(Dim.TEMP, 5.0 / 9.0, "degree Fahrenheit", "degrees Fahrenheit", 459.67 * 5.0 / 9.0))
        add("kelvin", Measure(Dim.TEMP, 1.0, "kelvin", "kelvin"))
        add("s sec secs second seconds", SECOND)
        add("min mins minute minutes", MINUTE)
        add("h hr hrs hour hours", HOUR)
        add("day days", Measure(Dim.TIME, 86_400.0, "day", "days"))
        add("week weeks", Measure(Dim.TIME, 604_800.0, "week", "weeks"))
        add("year years", Measure(Dim.TIME, 31_536_000.0, "year", "years"))
        add("byte bytes", Measure(Dim.DATA, 1.0, "byte", "bytes"))
        add("kb kilobyte kilobytes", Measure(Dim.DATA, 1e3, "kilobyte", "kilobytes"))
        add("mb megabyte megabytes", Measure(Dim.DATA, 1e6, "megabyte", "megabytes"))
        add("gb gigabyte gigabytes", Measure(Dim.DATA, 1e9, "gigabyte", "gigabytes"))
        add("tb terabyte terabytes", Measure(Dim.DATA, 1e12, "terabyte", "terabytes"))
    }

    private fun perUnit(length: Measure, time: Measure) = Measure(
        Dim.SPEED,
        length.factor / time.factor,
        "${length.singular} per ${time.singular}",
        "${length.plural} per ${time.singular}",
    )

    /** A unit at token [i] (two-token forms: "degrees celsius", "kilometers per hour"), and the index after it. */
    private fun unitAt(t: List<String>, i: Int): Pair<Measure, Int>? {
        val w = t.getOrNull(i) ?: return null
        if (w == "degrees" || w == "degree") {
            val temp = t.getOrNull(i + 1)?.let { units[it] }
            if (temp != null && temp.dim == Dim.TEMP) return temp to i + 2
        }
        val m = units[w] ?: return null
        if (m.dim == Dim.LENGTH && t.getOrNull(i + 1) == "per") {
            val time = t.getOrNull(i + 2)?.let { units[it] }
            if (time != null && time.dim == Dim.TIME) return perUnit(m, time) to i + 3
        }
        return m to i + 1
    }

    // ---- Numbers -----------------------------------------------------------

    private val ONES: Map<String, Int> = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS: Map<String, Int> = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )
    private val SCALES: Map<String, Double> = mapOf("thousand" to 1_000.0, "million" to 1_000_000.0)
    private val SEPARATORS = setOf("to", "into", "in")
    private val LEADING = setOf(
        "jarvis", "hey", "please", "what", "tell", "me", "calculate", "compute",
        "how", "many", "much", "convert", "the", "can", "you", "could",
    )

    private enum class Kind { NONE, ONES, TENS, HUNDRED, SCALE, AND }

    private fun digitOf(w: String): Int? =
        ONES[w]?.takeIf { it <= 9 } ?: if (w.length == 1 && w[0].isDigit()) w[0] - '0' else null

    private fun isNumeral(w: String): Boolean =
        w.isNotEmpty() && w[0].isDigit() && w.last().isDigit() &&
            w.all { it.isDigit() || it == '.' } && w.count { it == '.' } <= 1

    /** A number starting at [start] (digits, number words, "half", "a" before a unit), and the index after it. */
    private fun numberAt(t: List<String>, start: Int): Pair<Double, Int>? {
        val w = t.getOrNull(start) ?: return null
        if ((w == "a" || w == "an") && unitAt(t, start + 1) != null) return 1.0 to start + 1
        if (w == "half") {
            var j = start + 1
            if ((t.getOrNull(j) == "a" || t.getOrNull(j) == "an") && unitAt(t, j + 1) != null) j++
            return 0.5 to j
        }
        var value: Double
        var j: Int
        if (isNumeral(w)) {
            value = w.toDouble()
            j = start + 1
        } else {
            val words = wordNumber(t, start) ?: return null
            value = words.first
            j = words.second
        }
        if (t.getOrNull(j) == "point") {
            val frac = StringBuilder()
            var k = j + 1
            while (k < t.size) {
                val d = digitOf(t[k]) ?: break
                frac.append(d)
                k++
            }
            if (frac.isNotEmpty()) {
                value += "0.$frac".toDouble()
                j = k
            }
        }
        return value to j
    }

    /** Number words such as "one hundred and forty four", "twenty five", "a thousand". */
    private fun wordNumber(t: List<String>, start: Int): Pair<Double, Int>? {
        var i = start
        var total = 0.0
        var current = 0.0
        var kind = Kind.NONE
        var any = false
        if (t[i] == "a" || t[i] == "an") {
            val next = t.getOrNull(i + 1)
            if (next != "hundred" && next != "thousand" && next != "million") return null
            current = 1.0
            any = true
            i++
        }
        while (i < t.size) {
            val w = t[i]
            val one = ONES[w]
            val ten = TENS[w]
            if (one != null || ten != null) {
                val ok = when (kind) {
                    Kind.NONE, Kind.HUNDRED, Kind.SCALE, Kind.AND -> true
                    Kind.TENS -> one != null && one in 1..9
                    Kind.ONES -> false
                }
                if (!ok) break
                current += (one ?: ten)!!.toDouble()
                kind = if (ten != null) Kind.TENS else Kind.ONES
                any = true
            } else if (w == "hundred") {
                if (kind != Kind.NONE && kind != Kind.ONES && kind != Kind.TENS) break
                current = (if (current == 0.0) 1.0 else current) * 100
                kind = Kind.HUNDRED
                any = true
            } else if (SCALES.containsKey(w)) {
                if (kind == Kind.SCALE) break
                total += (if (current == 0.0) 1.0 else current) * SCALES.getValue(w)
                current = 0.0
                kind = Kind.SCALE
                any = true
            } else if (w == "and" && any && (kind == Kind.HUNDRED || kind == Kind.SCALE)) {
                val next = t.getOrNull(i + 1) ?: break
                if (!ONES.containsKey(next) && !TENS.containsKey(next)) break
                kind = Kind.AND
            } else {
                break
            }
            i++
        }
        return if (any) (total + current) to i else null
    }

    /** The number spanning all of [t] from [start], or null if other tokens remain. */
    private fun whole(t: List<String>, start: Int): Double? {
        val (v, end) = numberAt(t, start) ?: return null
        return if (end == t.size) v else null
    }

    // ---- Arithmetic --------------------------------------------------------

    private enum class Op(val word: String) { PLUS("plus"), MINUS("minus"), TIMES("times"), DIVIDE("divided by") }

    private fun operatorAt(t: List<String>, i: Int): Pair<Op, Int>? {
        val pair = t.getOrNull(i + 1)?.let { t[i] + " " + it }
        when (pair) {
            "multiplied by" -> return Op.TIMES to i + 2
            "divided by" -> return Op.DIVIDE to i + 2
            "added to" -> return Op.PLUS to i + 2
            "take away" -> return Op.MINUS to i + 2
        }
        val op = when (t[i]) {
            "plus", "add", "+" -> Op.PLUS
            "minus", "subtract", "-" -> Op.MINUS
            "times", "x", "*" -> Op.TIMES
            "over", "/" -> Op.DIVIDE
            else -> null
        } ?: return null
        return op to i + 1
    }

    private fun arithmetic(t: List<String>): String? {
        if (t.size > 3 && t[0] == "square" && t[1] == "root" && t[2] == "of") {
            val n = whole(t, 3) ?: return null
            if (n < 0) return null
            return "The square root of ${fmt(n)} is ${fmt(sqrt(n))}."
        }
        val (a, j) = numberAt(t, 0) ?: return null
        if (j >= t.size) return null
        val next = t[j]
        val after = t.getOrNull(j + 1)
        when {
            j == t.size - 1 && next == "squared" -> return "${fmt(a)} squared is ${fmt(a * a)}."
            j == t.size - 1 && next == "cubed" -> return "${fmt(a)} cubed is ${fmt(a * a * a)}."
            next == "percent" && after == "of" -> {
                val b = whole(t, j + 2) ?: return null
                return "${fmt(a)} percent of ${fmt(b)} is ${fmt(a * b / 100)}."
            }
            next == "to" && after == "the" && t.getOrNull(j + 2) == "power" -> {
                var k = j + 3
                if (t.getOrNull(k) == "of") k++
                val b = whole(t, k) ?: return null
                val r = a.pow(b)
                if (!r.isFinite()) return null
                return "${fmt(a)} to the power of ${fmt(b)} is ${fmt(r)}."
            }
        }
        val (op, k) = operatorAt(t, j) ?: return null
        val b = whole(t, k) ?: return null
        val r = when (op) {
            Op.PLUS -> a + b
            Op.MINUS -> a - b
            Op.TIMES -> a * b
            Op.DIVIDE -> {
                if (b == 0.0) return "You can't divide by zero."
                a / b
            }
        }
        return "${fmt(a)} ${op.word} ${fmt(b)} is ${fmt(r)}."
    }

    // ---- Conversions -------------------------------------------------------

    private fun conversion(t: List<String>): String? {
        // "<number> <unit> to|in|into <unit>": "convert ten miles to km", "10 miles in km"
        val leading = numberAt(t, 0)
        if (leading != null) {
            val (v, j) = leading
            val src = unitAt(t, j)
            if (src != null && src.second < t.size && t[src.second] in SEPARATORS) {
                val dst = unitAt(t, src.second + 1)
                if (dst != null && dst.second == t.size) return convert(v, src.first, dst.first)
            }
        }
        // "<unit> in <number> <unit>": "how many kilometers in 10 miles", "grams in a pound"
        val dst = unitAt(t, 0) ?: return null
        if (dst.second >= t.size || t[dst.second] !in SEPARATORS) return null
        val num = numberAt(t, dst.second + 1) ?: return null
        val src = unitAt(t, num.second) ?: return null
        if (src.second != t.size) return null
        return convert(num.first, src.first, dst.first)
    }

    private fun convert(value: Double, from: Measure, to: Measure): String? {
        if (from.dim != to.dim) return null
        val result = (value * from.factor + from.offset - to.offset) / to.factor
        if (!result.isFinite()) return null
        val v = fmt(value)
        val r = fmt(result)
        return "$v ${name(from, v)} is $r ${name(to, r)}."
    }

    private fun name(m: Measure, amount: String) = if (amount == "1") m.singular else m.plural

    /** Up to two decimals, trailing zeros stripped, no thousands separators. */
    private fun fmt(x: Double): String {
        val r = if (abs(x) < 1e12) Math.round(x * 100.0) / 100.0 else x
        if (abs(r) < 1e15 && r == floor(r)) return r.toLong().toString()
        return String.format(Locale.US, "%.2f", r).trimEnd('0').trimEnd('.')
    }

    // ---- Tokens ------------------------------------------------------------

    private fun tokenize(text: String): List<String> {
        val s = text.lowercase()
            .replace("what’s", "what is").replace("what's", "what is").replace("whats", "what is")
            .replace("km/h", " kilometers per hour ").replace("m/s", " meters per second ")
            .replace("kph", " kilometers per hour ").replace("kmh", " kilometers per hour ")
            .replace("mph", " miles per hour ")
            .replace("°c", " degrees celsius ").replace("°f", " degrees fahrenheit ")
            .replace("°", " degrees ").replace("%", " percent ")
        val spaced = StringBuilder()
        for (i in s.indices) {
            val c = s[i]
            val digitsAround = i > 0 && i < s.lastIndex && s[i - 1].isDigit() && s[i + 1].isDigit()
            when {
                c == ',' && digitsAround -> {}
                c == '.' && digitsAround -> spaced.append(c)
                c.isLetterOrDigit() -> spaced.append(c)
                c in "+-*/" -> spaced.append(" $c ")
                else -> spaced.append(' ')
            }
        }
        val words = spaced.toString().split(' ').filter { it.isNotEmpty() && it != "is" && it != "are" }
        var start = 0
        while (start < words.size && words[start] in LEADING) start++
        return words.subList(start, words.size)
    }
}
