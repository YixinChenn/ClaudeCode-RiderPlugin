package com.anthropic.claudecode.rider.browser

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.io.FileUtil
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Extracts the bundled webview assets (index.js, index.css) from the plugin JAR
 * into a temporary directory so that JCEF can load them via file:// URLs.
 *
 * Also writes the generated index.html into the same directory so it can be loaded
 * via loadURL("file:///...") — this ensures relative/absolute file:// references
 * to index.js and index.css work without CSP issues.
 */
object WebviewAssetProvider {

    private val log = Logger.getInstance(WebviewAssetProvider::class.java)
    private var cachedDir: Path? = null

    @Synchronized
    fun ensureExtracted(): Path {
        cachedDir?.let { return it }

        val dir = Files.createTempDirectory("claude-code-rider-webview")
        log.info("Extracting Claude webview assets to $dir")

        for (name in listOf("index.js", "index.css")) {
            val resource = WebviewAssetProvider::class.java.getResourceAsStream("/webview/$name")
                ?: throw IllegalStateException(
                    "Webview resource '/webview/$name' not found in plugin JAR. " +
                    "Copy webview/index.js and webview/index.css from the VS Code extension " +
                    "into src/main/resources/webview/"
                )
            if (name == "index.js") {
                // index.js needs one tiny behavioural patch (see patchBundle); read, transform, write.
                val patched = resource.use { it.readBytes().toString(Charsets.UTF_8) }.let(::patchBundle)
                Files.writeString(dir.resolve(name), patched)
            } else {
                resource.use { input ->
                    Files.copy(input, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING)
                }
            }
            log.debug("Extracted $name")
        }

        Runtime.getRuntime().addShutdownHook(Thread {
            try {
                FileUtil.delete(dir.toFile())
            } catch (ignored: Exception) { }
        })

        cachedDir = dir
        return dir
    }

    /**
     * Applies minimal behavioural patches to the (otherwise unmodified) VS Code webview
     * bundle at extraction time. The source asset under resources/ is left untouched;
     * only the temp copy that JCEF loads is patched, so the change survives re-copying a
     * fresh bundle from the VS Code extension (as long as the anchor strings still match).
     *
     * Patch — don't list empty "Untitled" conversations in history.
     *   Clicking "New conversation" calls the webview's `createSession()`, which prepends a
     *   brand-new (no messages, no summary) session to `sessions.value` and makes it active.
     *   The session-history list is rendered straight from `sessions.value` with NO empty
     *   filter (only the unrelated worktree-grouping view filters), so every click leaves an
     *   empty row that renders as "Untitled" (title = `summary || "Untitled"`). In VS Code
     *   this never happens because "New conversation" opens a separate editor tab
     *   (openNewInTab=true); Rider has a single tool window (openNewInTab=false, see
     *   ClaudeMessageRouter.handleInit) so it falls back to createSession().
     *
     *   Fix: inject a `.filter(...)` into the two places that build the displayed list from
     *   `sessions.value` so a session only shows once it has at least one message or a title.
     *   Sessions loaded from disk always carry a host-provided summary (never blank — see
     *   ClaudeMessageRouter.readSessionSummary), so real history is unaffected; only the
     *   transient empty in-memory session is hidden until the user actually sends something.
     *
     * Patch — don't crash the conversation render on an unknown partial-stream case.
     *   The bundle's SSE accumulator throws `Unhandled case: <obj>` (helper `ZB1`) from the
     *   `default:` branch of two switches: the top-level stream-event type switch and the
     *   content_block_delta `delta.type` switch. We launch `claude` with
     *   `--include-partial-messages`, so when a newer CLI emits a stream event / delta type
     *   this (older) bundle doesn't recognise, the throw bubbles up and the webview shows a
     *   fatal "Unhandled case: [object Object]" banner, killing the whole turn's render.
     *   Partial messages are purely a live-streaming preview; the authoritative complete
     *   `assistant` message arrives separately, so skipping an unknown partial case is safe.
     *
     *   Fix: rewrite `ZB1` so the no-explicit-message path (the "Unhandled case" default)
     *   warns and returns instead of throwing. Callers that pass an explicit message string
     *   still throw — but there are none for this helper, and the unrelated
     *   "Mismatched content block type" errors throw via `Error(...)` directly, so they are
     *   unaffected. This is also forward-resilient to future stream-protocol additions.
     */
    private fun patchBundle(js: String): String {
        var out = js
        out = patchEmptyConversations(out)
        out = patchUnhandledStreamCase(out)
        return out
    }

    private fun patchUnhandledStreamCase(js: String): String {
        val anchor = "function ZB1(\$,Z){throw Error(Z??`Unhandled case: \${\$}`)}"
        val replacement = "function ZB1(\$,Z){if(Z!==void 0)throw Error(Z);" +
            "try{console.warn(\"Claude(Rider): skipping unhandled partial-stream case\",\$&&\$.type)}catch(e){}}"
        return if (js.contains(anchor)) {
            log.info("Applied webview patch: unknown partial-stream cases are skipped instead of crashing the render")
            js.replace(anchor, replacement)
        } else {
            log.warn("Webview patch 'unhandled-stream-case' not applied; bundle likely updated — " +
                "an unknown stream event/delta type may still surface as 'Unhandled case: [object Object]'.")
            js
        }
    }

    private fun patchEmptyConversations(js: String): String {
        // Keep a session in the list only if it has messages or a non-empty summary/title.
        val keep = ".filter(e=>e.messages.value.length>0||e.summary.value)"
        // Two list builders read sessions.value directly: the history dropdown and the panel.
        val patches = listOf(
            // dropdown: localSessions:[...$.sessions.value].sort(...)
            "[...\$.sessions.value].sort(" to "[...\$.sessions.value]$keep.sort(",
            // panel:   q=useMemo(()=>[...X,...G].sort(...))  where X=$.sessions.value
            "[...X,...G].sort(" to "[...X,...G]$keep.sort("
        )
        var out = js
        var applied = 0
        for ((anchor, replacement) in patches) {
            if (out.contains(anchor)) { out = out.replace(anchor, replacement); applied++ }
        }
        if (applied == patches.size) {
            log.info("Applied webview patch: empty conversations hidden from history until they have content ($applied/${patches.size})")
        } else {
            log.warn("Webview patch 'empty-conversation' only partially applied ($applied/${patches.size}); " +
                "bundle likely updated — some new conversations may still appear as Untitled in history.")
        }
        return out
    }

    /**
     * Writes the HTML string into index.html in the asset directory and returns
     * the file:// URL suitable for JBCefBrowser.loadURL().
     */
    fun writeHtml(assetDir: Path, html: String): String {
        val htmlFile = assetDir.resolve("index.html")
        Files.writeString(htmlFile, html)
        return htmlFile.toUri().toString()
    }
}
