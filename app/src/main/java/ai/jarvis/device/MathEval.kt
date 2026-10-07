package ai.jarvis.device

/**
 * Tiny arithmetic evaluator: + - * / % ^ and parentheses.
 * Written by hand so we never eval untrusted input.
 */
object MathEval {

    fun eval(expr: String): Double {
        val p = Parser(expr.replace(" ", ""))
        val v = p.parseExpr()
        require(p.atEnd()) { "unexpected trailing input" }
        return v
    }

    private class Parser(private val src: String) {
        private var pos = 0
        fun atEnd() = pos >= src.length
        private fun peek(): Char? = if (pos < src.length) src[pos] else null

        fun parseExpr(): Double {
            var v = parseTerm()
            while (true) {
                when (peek()) {
                    '+' -> { pos++; v += parseTerm() }
                    '-' -> { pos++; v -= parseTerm() }
                    else -> return v
                }
            }
        }

        private fun parseTerm(): Double {
            var v = parseFactor()
            while (true) {
                when (peek()) {
                    '*' -> { pos++; v *= parseFactor() }
                    '/' -> { pos++; v /= parseFactor() }
                    '%' -> { pos++; v %= parseFactor() }
                    else -> return v
                }
            }
        }

        private fun parseFactor(): Double {
            var base = parseUnary()
            if (peek() == '^') {
                pos++
                base = Math.pow(base, parseFactor())
            }
            return base
        }

        private fun parseUnary(): Double = when (peek()) {
            '-' -> { pos++; -parseUnary() }
            '+' -> { pos++; parseUnary() }
            '(' -> {
                pos++
                val v = parseExpr()
                require(peek() == ')') { "missing )" }
                pos++
                v
            }
            else -> parseNumber()
        }

        private fun parseNumber(): Double {
            val start = pos
            while (peek()?.let { it.isDigit() || it == '.' } == true) pos++
            require(pos > start) { "number expected" }
            return src.substring(start, pos).toDouble()
        }
    }
}
