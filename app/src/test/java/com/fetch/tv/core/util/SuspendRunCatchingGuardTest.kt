package com.fetch.tv.core.util

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Ratchets bare `runCatching` out of coroutine code (audit X6, #450). In suspend code
 * `runCatching` also catches [CancellationException], so a cancelled coroutine carries on past
 * the suspension point; [runSuspendCatching] rethrows it.
 *
 * A call counts as coroutine code when, walking outwards by indentation, a `suspend fun` or a
 * coroutine builder (`launch {`, `withContext(...) {`, `flow {` and the like) encloses it before
 * a plain `fun` does. That over-counts a little (a `runCatching` around pure parsing in a suspend
 * function), which the baseline absorbs.
 *
 * `suspend-runcatching-baseline.txt` records how many such calls each file may keep. A file
 * going over fails, and so does one going under, so the baseline is lowered as code migrates and
 * can only shrink. After migrating, run this test and copy the counts it prints.
 */
class SuspendRunCatchingGuardTest {

    private val sourceRoot = File("src/main/java")

    private val call = Regex("""(?<![A-Za-z0-9_])runCatching\b""")
    private val suspendFun = Regex("""\bsuspend\s+fun\b""")
    private val plainFun = Regex("""(^|\s)fun\b""")
    private val builder = Regex(
        """\b(launch|async|withContext|withTimeout|withTimeoutOrNull|coroutineScope|supervisorScope|""" +
            """flow|channelFlow|callbackFlow|collect|collectLatest)\b\s*(\([^)]*\))?\s*\{"""
    )

    private fun indentOf(line: String) = line.length - line.trimStart().length

    /** The 1-based lines in [lines] holding a `runCatching` call in coroutine code. */
    private fun suspendCalls(lines: List<String>): List<Int> = lines.indices.filter { index ->
        val line = lines[index]
        val code = line.substringBefore("//")
        if (!call.containsMatchIn(code) || code.trimStart().startsWith("*")) return@filter false
        // The call may itself sit inside a builder on its own line: `launch { runCatching {`.
        val before = code.substring(0, call.find(code)!!.range.first)
        if (builder.containsMatchIn(before) || suspendFun.containsMatchIn(before)) return@filter true
        if (plainFun.containsMatchIn(before)) return@filter false
        var indent = indentOf(line)
        for (back in index - 1 downTo 0) {
            val outer = lines[back]
            if (outer.isBlank() || indentOf(outer) >= indent) continue
            indent = indentOf(outer)
            val outerCode = outer.substringBefore("//")
            if (suspendFun.containsMatchIn(outerCode) || builder.containsMatchIn(outerCode)) return@filter true
            if (plainFun.containsMatchIn(outerCode)) return@filter false
            if (indent == 0) return@filter false
        }
        false
    }.map { it + 1 }

    private fun locations(): Map<String, List<Int>> =
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { file ->
                val lines = suspendCalls(file.readLines())
                if (lines.isEmpty()) null else file.relativeTo(sourceRoot).invariantSeparatorsPath to lines
            }
            .toMap()

    private fun baseline(): Map<String, Int> =
        javaClass.getResourceAsStream("/suspend-runcatching-baseline.txt")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line ->
                val (path, count) = line.split('|')
                path to count.toInt()
            }

    @Test
    fun `coroutine code adds no bare runCatching`() {
        check(sourceRoot.isDirectory) { "Run from the app module; ${sourceRoot.absolutePath} is missing" }
        val actual = locations()
        val allowed = baseline()
        val problems = buildList {
            (actual.keys + allowed.keys).sorted().forEach { path ->
                val now = actual[path]?.size ?: 0
                val max = allowed[path] ?: 0
                if (now > max) {
                    add(
                        "$path: ${now - max} new bare runCatching in coroutine code (lines " +
                            "${actual[path].orEmpty().joinToString()}); use runSuspendCatching"
                    )
                } else if (now < max) {
                    add("$path: down to $now from $max; lower it in suspend-runcatching-baseline.txt")
                }
            }
        }
        if (problems.isNotEmpty()) {
            val current = actual.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}|${it.value.size}" }
            fail(problems.joinToString("\n") + "\n\nCurrent counts:\n$current")
        }
    }

    @Test
    fun `the detector sees suspend functions and builders but not plain functions`() {
        val source = """
            class A {
                suspend fun a() {
                    runCatching { work() }
                }
                fun b() {
                    runCatching { work() }
                    scope.launch {
                        val x = runCatching { work() }
                    }
                }
                fun c() = runCatching { work() }
                suspend fun d() = runCatching { work() }
            }
        """.trimIndent().lines()
        val found = suspendCalls(source)
        assertTrue("found $found", found == listOf(3, 8, 12))
    }

    @Test
    fun `runSuspendCatching rethrows cancellation`() = runBlocking {
        var reachedAfter = false
        val job = launch {
            runSuspendCatching { delay(10_000) }
            reachedAfter = true
        }
        delay(50)
        job.cancel()
        job.join()
        assertTrue("work carried on after cancellation", !reachedAfter)
    }

    @Test
    fun `runSuspendCatching keeps ordinary failures and inner timeouts as failures`() = runBlocking {
        val failure = runSuspendCatching { error("boom") }
        assertTrue(failure.exceptionOrNull() is IllegalStateException)
        val timeout = runSuspendCatching { withTimeout(10) { delay(1_000) } }
        assertTrue(timeout.exceptionOrNull() is TimeoutCancellationException)
    }
}
