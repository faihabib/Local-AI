package com.localai.app.geniex

/**
 * Minimal Jinja2 interpreter tailored to GGUF chat templates.
 * Supports {{ expr }}, {% if/elif/else/endif %}, {% for x in y %}, {% set %},
 * whitespace-control dashes, attribute/index access, string concat with +,
 * comparisons, and/or/not, `is defined`, string/int/bool literals, loop.*.
 */
class MiniJinja(private val template: String) {

    fun render(ctx: Map<String, Any?>): String {
        val sb = StringBuilder()
        renderNodes(parse(tokenize(template)), HashMap(ctx), sb)
        return sb.toString()
    }

    // ---------- tokenizer ----------
    private sealed class Tok
    private data class RawText(val s: String) : Tok()
    private data class ExprTok(val inner: String) : Tok()
    private data class TagTok(val inner: String) : Tok()

    private fun tokenize(t: String): List<Tok> {
        val out = mutableListOf<Tok>()
        var i = 0
        val sb = StringBuilder()
        while (i < t.length) {
            if (i + 1 < t.length && t[i] == '{' && (t[i+1] == '{' || t[i+1] == '%' || t[i+1] == '#')) {
                val kind = t[i+1]
                val close = when (kind) { '{' -> "}}"; '%' -> "%}"; else -> "#}" }
                val trimL = i + 2 < t.length && t[i+2] == '-'
                val bodyStart = if (trimL) i + 3 else i + 2
                val closeIdx = t.indexOf(close, bodyStart)
                if (closeIdx < 0) { sb.append(t, i, t.length); break }
                val trimR = closeIdx > 0 && t[closeIdx - 1] == '-'
                val bodyEnd = if (trimR) closeIdx - 1 else closeIdx
                if (sb.isNotEmpty()) { out += RawText(sb.toString()); sb.clear() }
                val inner = t.substring(bodyStart, bodyEnd).trim()
                if (kind == '{') out += ExprTok(inner)
                else if (kind == '%') out += TagTok(inner)
                i = if (trimR) {
                    var j = closeIdx + 2
                    while (j < t.length && t[j].isWhitespace()) j++
                    j
                } else closeIdx + 2
            } else { sb.append(t[i]); i++ }
        }
        if (sb.isNotEmpty()) out += RawText(sb.toString())
        return out
    }

    // ---------- parser ----------
    private sealed class Node
    private data class TextN(val s: String) : Node()
    private data class ExprN(val e: String) : Node()
    private data class IfN(val branches: List<Pair<String, List<Node>>>, val elseBody: List<Node>?) : Node()
    private data class ForN(val varName: String, val iter: String, val body: List<Node>) : Node()
    private data class SetN(val name: String, val e: String) : Node()

    private fun parse(tokens: List<Tok>): List<Node> = parseNodes(tokens, 0).first

    private fun parseNodes(toks: List<Tok>, start: Int): Pair<List<Node>, Int> {
        val out = mutableListOf<Node>()
        var i = start
        while (i < toks.size) {
            when (val t = toks[i]) {
                is RawText -> { out += TextN(t.s); i++ }
                is ExprTok -> { out += ExprN(t.inner); i++ }
                is TagTok -> {
                    val head = t.inner.substringBefore(' ').substringBefore('(')
                    if (head in setOf("endif","endfor","else") || head == "elif") return out to i
                    when {
                        t.inner.startsWith("if ") || t.inner == "if" -> {
                            val (n, ni) = parseIf(toks, i); out += n; i = ni
                        }
                        t.inner.startsWith("for ") -> {
                            val (n, ni) = parseFor(toks, i); out += n; i = ni
                        }
                        t.inner.startsWith("set ") -> {
                            val body = t.inner.removePrefix("set ").trim()
                            val eq = body.indexOf('=')
                            if (eq > 0) out += SetN(body.substring(0, eq).trim(), body.substring(eq + 1).trim())
                            i++
                        }
                        t.inner.startsWith("raise_exception") -> { i++ }
                        else -> i++
                    }
                }
            }
        }
        return out to i
    }

    private fun parseIf(toks: List<Tok>, start: Int): Pair<IfN, Int> {
        val branches = mutableListOf<Pair<String, List<Node>>>()
        var elseBody: List<Node>? = null
        var i = start
        // header
        val first = toks[start] as TagTok
        var cond = first.inner.removePrefix("if").trim()
        while (i < toks.size) {
            val (body, next) = parseNodes(toks, i + 1)
            branches += cond to body
            val nt = toks.getOrNull(next) as? TagTok ?: return IfN(branches, elseBody) to next
            when {
                nt.inner == "endif" -> return IfN(branches, elseBody) to (next + 1)
                nt.inner == "else" -> {
                    val (eb, n2) = parseNodes(toks, next + 1)
                    elseBody = eb
                    return IfN(branches, elseBody) to (n2 + 1)
                }
                nt.inner.startsWith("elif ") -> {
                    cond = nt.inner.removePrefix("elif ").trim()
                    i = next
                }
                else -> return IfN(branches, elseBody) to (next + 1)
            }
        }
        return IfN(branches, elseBody) to i
    }

    private fun parseFor(toks: List<Tok>, start: Int): Pair<ForN, Int> {
        val t = toks[start] as TagTok
        val body = t.inner.removePrefix("for ").trim()
        val inIdx = body.indexOf(" in ")
        val varName = body.substring(0, inIdx).trim()
        val iter = body.substring(inIdx + 4).trim()
        val (b, next) = parseNodes(toks, start + 1)
        val endIdx = if ((toks.getOrNull(next) as? TagTok)?.inner == "endfor") next + 1 else next
        return ForN(varName, iter, b) to endIdx
    }

    // ---------- renderer ----------
    private fun renderNodes(nodes: List<Node>, ctx: MutableMap<String, Any?>, sb: StringBuilder) {
        for (n in nodes) when (n) {
            is TextN -> sb.append(n.s)
            is ExprN -> sb.append(str(eval(n.e, ctx)))
            is SetN -> ctx[n.name] = eval(n.e, ctx)
            is IfN -> {
                var done = false
                for ((c, body) in n.branches) {
                    if (truthy(eval(c, ctx))) { renderNodes(body, ctx, sb); done = true; break }
                }
                if (!done) n.elseBody?.let { renderNodes(it, ctx, sb) }
            }
            is ForN -> {
                val items = eval(n.iter, ctx) as? List<*> ?: emptyList<Any?>()
                for ((idx, item) in items.withIndex()) {
                    val local = HashMap(ctx)
                    local[n.varName] = item
                    local["loop"] = mapOf(
                        "first" to (idx == 0),
                        "last" to (idx == items.size - 1),
                        "index" to idx + 1,
                        "index0" to idx,
                        "length" to items.size,
                    )
                    renderNodes(n.body, local, sb)
                }
            }
        }
    }

    // ---------- evaluator ----------
    private fun eval(e: String, ctx: Map<String, Any?>): Any? = evalOr(e.trim(), ctx)

    private fun evalOr(e: String, ctx: Map<String, Any?>): Any? {
        val p = splitTop(e, " or ")
        return if (p.size > 1) p.any { truthy(evalOr(it, ctx)) } else evalAnd(e, ctx)
    }
    private fun evalAnd(e: String, ctx: Map<String, Any?>): Any? {
        val p = splitTop(e, " and ")
        return if (p.size > 1) p.all { truthy(evalAnd(it, ctx)) } else evalNot(e, ctx)
    }
    private fun evalNot(e: String, ctx: Map<String, Any?>): Any? {
        val t = e.trim()
        return if (t.startsWith("not ")) !truthy(evalNot(t.substring(4), ctx)) else evalCmp(t, ctx)
    }
    private fun evalCmp(e: String, ctx: Map<String, Any?>): Any? {
        for (op in listOf(" is not defined"," is defined"," == "," != "," >= "," <= "," > "," < ")) {
            val p = splitTop(e, op)
            if (p.size == 2) {
                if (op == " is defined") return isDefined(p[0], ctx)
                if (op == " is not defined") return !isDefined(p[0], ctx)
                val a = evalPrimary(p[0], ctx); val b = evalPrimary(p[1], ctx)
                return when (op.trim()) {
                    "==" -> eq(a, b); "!=" -> !eq(a, b)
                    ">"  -> cmp(a, b) > 0; "<"  -> cmp(a, b) < 0
                    ">=" -> cmp(a, b) >= 0; "<=" -> cmp(a, b) <= 0
                    else -> false
                }
            }
        }
        return evalPrimary(e, ctx)
    }
    private fun isDefined(expr: String, ctx: Map<String, Any?>): Boolean {
        val name = expr.trim().substringBefore('.').substringBefore('[')
        return ctx[name] != null
    }
    private fun eq(a: Any?, b: Any?): Boolean = when {
        a == null && b == null -> true
        a == null || b == null -> false
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
    }
    private fun cmp(a: Any?, b: Any?): Int {
        val x = (a as? Number)?.toDouble() ?: 0.0
        val y = (b as? Number)?.toDouble() ?: 0.0
        return x.compareTo(y)
    }

    private fun evalPrimary(e: String, ctx: Map<String, Any?>): Any? {
        val t = e.trim()
        if (t.startsWith("(") && t.endsWith(")")) return eval(t.substring(1, t.length - 1), ctx)
        if ((t.startsWith("'") && t.endsWith("'")) || (t.startsWith("\"") && t.endsWith("\"")))
            return t.substring(1, t.length - 1)
        if (t == "true" || t == "True") return true
        if (t == "false" || t == "False") return false
        if (t == "none" || t == "None" || t == "null") return null
        t.toLongOrNull()?.let { return it }
        val concat = splitTop(t, " + ")
        if (concat.size > 1) return concat.joinToString("") { str(evalPrimary(it, ctx)) }
        return evalChain(t, ctx)
    }

    private fun evalChain(t: String, ctx: Map<String, Any?>): Any? {
        var i = 0
        val base = StringBuilder()
        while (i < t.length && t[i] != '.' && t[i] != '[') { base.append(t[i]); i++ }
        var cur: Any? = ctx[base.toString().trim()]
        while (i < t.length) {
            if (t[i] == '.') {
                i++; val s = i
                while (i < t.length && (t[i].isLetterOrDigit() || t[i] == '_')) i++
                cur = attrGet(cur, t.substring(s, i))
            } else if (t[i] == '[') {
                i++; val s = i; var d = 1
                while (i < t.length && d > 0) { if (t[i] == '[') d++; if (t[i] == ']') d--; if (d > 0) i++ }
                val keyExpr = t.substring(s, i); i++
                cur = idxGet(cur, eval(keyExpr, ctx))
            } else break
        }
        return cur
    }
    private fun attrGet(o: Any?, k: String): Any? = (o as? Map<*, *>)?.get(k)
    private fun idxGet(o: Any?, k: Any?): Any? = when {
        o is Map<*, *> && k != null -> o[k]
        o is List<*> && k is Number -> o.getOrNull(k.toInt())
        else -> null
    }

    private fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        false -> false
        true -> true
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty()
        is Collection<*> -> v.isNotEmpty()
        else -> true
    }
    private fun str(v: Any?): String = when (v) {
        null -> ""
        is Boolean -> if (v) "true" else "false"
        else -> v.toString()
    }

    private fun splitTop(s: String, sep: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0; var i = 0; var last = 0; var q: Char? = null
        while (i < s.length) {
            val c = s[i]
            if (q != null) { if (c == q && s.getOrNull(i-1) != '\\') q = null; i++; continue }
            when (c) { '\'', '"' -> q = c; '(', '[', '{' -> depth++; ')', ']', '}' -> depth-- }
            if (depth == 0 && s.startsWith(sep, i)) {
                out += s.substring(last, i); i += sep.length; last = i; continue
            }
            i++
        }
        out += s.substring(last)
        return out
    }
}
