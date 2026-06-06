package com.anthropic.claudecode.rider.process

import com.anthropic.claudecode.rider.settings.ClaudeSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.EnvironmentUtil
import com.intellij.util.SystemProperties
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * Resolves the Claude CLI binary path and assembles the environment variables
 * required to run it in VS Code IDE integration mode.
 */
object ClaudeProcessConfig {

    private val log = Logger.getInstance(ClaudeProcessConfig::class.java)

    private val isWindows = System.getProperty("os.name", "").contains("Windows", ignoreCase = true)
    private val isMac = System.getProperty("os.name", "").contains("Mac", ignoreCase = true)

    /**
     * Resolves the claude binary path using the following priority:
     * 1. User-configured path from ClaudeSettings
     * 2. `claude` / `claude.exe` found on the system PATH
     * 3. Binary bundled in the VS Code extension directory (fallback)
     */
    fun resolveBinaryPath(): String? {
        val settings = ClaudeSettings.getInstance()

        // 1. User-configured path
        val configured = settings.claudeExecutablePath.trim()
        if (configured.isNotEmpty()) {
            val f = File(configured)
            if (f.exists() && f.canExecute()) {
                log.info("Using user-configured Claude binary: $configured")
                return configured
            }
            log.warn("Configured Claude path does not exist or is not executable: $configured")
        }

        // 2. System PATH — look for native binary first, then npm .cmd shim
        val candidates = if (isWindows) listOf("claude.exe", "claude.cmd") else listOf("claude")
        for (exeName in candidates) {
            val fromPath = findOnPath(exeName)
            if (fromPath != null) {
                log.info("Found Claude on PATH: $fromPath")
                return fromPath
            }
        }

        // 3. npm global bin directory (may not be on PATH)
        if (isWindows) {
            val npmDir = System.getenv("APPDATA")?.let { File(it, "npm") }
            if (npmDir != null && npmDir.exists()) {
                for (name in listOf("claude.exe", "claude.cmd")) {
                    val f = File(npmDir, name)
                    if (f.exists()) {
                        log.info("Found Claude in npm global bin: ${f.absolutePath}")
                        return f.absolutePath
                    }
                }
            }
        }

        // 4. VS Code extension bundled binary (Windows only)
        if (isWindows) {
            val vscodeBinary = findVsCodeExtensionBinary()
            if (vscodeBinary != null) {
                log.info("Using VS Code extension Claude binary: $vscodeBinary")
                return vscodeBinary
            }
        }

        log.warn("Claude binary not found")
        return null
    }

    private fun findOnPath(exeName: String): String? {
        val pathEnv = System.getenv("PATH") ?: return null
        val separator = if (isWindows) ";" else ":"
        return pathEnv.split(separator)
            .map { File(it, exeName) }
            .firstOrNull { it.exists() && it.canExecute() }
            ?.absolutePath
    }

    private fun findVsCodeExtensionBinary(): String? {
        val home = SystemProperties.getUserHome()
        val extensionsDir = File(home, ".vscode/extensions")
        if (!extensionsDir.exists()) return null

        // Find any claude-code extension directory
        val extDirs = extensionsDir.listFiles { f ->
            f.isDirectory && f.name.startsWith("anthropic.claude-code")
        } ?: return null

        // Prefer architecture-specific binary, fall back to generic
        val arch = if (System.getProperty("os.arch", "").contains("64")) "x64" else "x86"
        val platform = "win32"

        for (extDir in extDirs.sortedByDescending { it.name }) {
            val archSpecific = File(extDir, "resources/native-binaries/$platform-$arch/claude.exe")
            if (archSpecific.exists()) return archSpecific.absolutePath

            val generic = File(extDir, "resources/native-binary/claude.exe")
            if (generic.exists()) return generic.absolutePath
        }
        return null
    }

    /**
     * Builds the environment variable map for the Claude subprocess.
     * Merges the current process environment with the required IDE integration vars.
     */
    fun buildEnvironment(): Map<String, String> {
        val env = EnvironmentUtil.getEnvironmentMap().toMutableMap()

        // Required for VS Code IPC mode in the Claude binary
        env["CLAUDE_CODE_ENTRYPOINT"] = "claude-vscode"
        env["MCP_CONNECTION_NONBLOCKING"] = "true"

        // Merge user-configured environment variables
        val settings = ClaudeSettings.getInstance()
        for (v in settings.environmentVariables) {
            if (v.name.isNotBlank()) {
                env[v.name] = v.value
            }
        }

        return env
    }

    /**
     * Enumerates configured MCP servers by shelling out to `claude mcp list`, which is the
     * same source of truth the interactive CLI `/mcp` uses (it health-checks approved servers).
     *
     * The webview's `/mcp` panel drives itself off a `get_mcp_servers` RPC to the host rather
     * than reading the stream-JSON `system/init` message (whose `mcp_servers` status is always
     * "pending" under MCP_CONNECTION_NONBLOCKING). So the host has to produce this list itself.
     *
     * Returns one JsonObject per server with the fields the panel reads: `name`, `status`
     * (connected|failed|needs-auth|pending|disabled), `scope` (for grouping) and a truthy
     * `config` object (servers without `config` are filtered out by the webview).
     *
     * Runs a subprocess with a health-check timeout — call this OFF the EDT.
     */
    fun listMcpServers(cwd: String): List<JsonObject> {
        val binary = resolveBinaryPath() ?: run {
            log.warn("listMcpServers: claude binary not found")
            return emptyList()
        }

        val cmd = mutableListOf<String>()
        if (binary.endsWith(".cmd", ignoreCase = true) || binary.endsWith(".bat", ignoreCase = true)) {
            cmd += listOf("cmd.exe", "/c", binary)
        } else {
            cmd += binary
        }
        cmd += listOf("mcp", "list")

        val output = try {
            val pb = ProcessBuilder(cmd)
                .directory(File(cwd.takeIf { File(it).exists() } ?: SystemProperties.getUserHome()))
                .redirectErrorStream(true)
            pb.environment().putAll(buildEnvironment())
            val proc = pb.start()
            val text = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
            if (!proc.waitFor(45, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                log.warn("listMcpServers: `claude mcp list` timed out")
            }
            text
        } catch (e: Exception) {
            log.warn("listMcpServers: failed to run `claude mcp list`: ${e.message}")
            return emptyList()
        }

        val scopes = readConfiguredScopes(cwd)
        val servers = parseMcpListOutput(output, scopes)
        log.info("listMcpServers: parsed ${servers.size} server(s) from `claude mcp list`")
        return servers
    }

    /**
     * Probes the available slash commands + skills the way the VS Code extension does:
     * spawn a detached `claude` (no conversation channel), send a single
     * `control_request {subtype:"initialize"}`, and read the `commands` array out of the
     * matching `control_response`. Each entry is `{name, description, argumentHint}` — exactly
     * the shape the webview's `/` picker expects. The process is killed as soon as the
     * response arrives, so no conversation turn is ever run.
     *
     * Returns an empty array on any failure/timeout. Runs synchronously — call off the EDT.
     */
    fun probeSlashCommands(cwd: String): JsonArray {
        val binary = resolveBinaryPath() ?: run {
            log.warn("probeSlashCommands: claude binary not found")
            return JsonArray(emptyList())
        }

        val cmd = mutableListOf<String>()
        if (binary.endsWith(".cmd", ignoreCase = true) || binary.endsWith(".bat", ignoreCase = true)) {
            cmd += listOf("cmd.exe", "/c", binary)
        } else {
            cmd += binary
        }
        cmd += listOf("--output-format", "stream-json", "--verbose", "--input-format", "stream-json")

        val requestId = "rider-slash-probe"
        var proc: Process? = null
        return try {
            val pb = ProcessBuilder(cmd)
                .directory(File(cwd.takeIf { File(it).exists() } ?: SystemProperties.getUserHome()))
                .redirectErrorStream(false)
            pb.environment().putAll(buildEnvironment())
            proc = pb.start()

            // Watchdog: a hung CLI would block readLine() forever (the deadline below only
            // fires between lines), so force-kill the process after the timeout to unblock it.
            val watched = proc
            val watchdog = Thread {
                try { Thread.sleep(35_000) } catch (_: InterruptedException) { return@Thread }
                if (watched.isAlive) watched.destroyForcibly()
            }.apply { isDaemon = true; start() }

            // Trigger the SDK-style initialize handshake; the CLI replies with a control_response
            // whose payload carries the full command/skill catalog.
            proc.outputStream.write(
                ("""{"type":"control_request","request_id":"$requestId","request":{"subtype":"initialize"}}""" + "\n")
                    .toByteArray(Charsets.UTF_8)
            )
            proc.outputStream.flush()

            var commands = JsonArray(emptyList())
            val reader = proc.inputStream.bufferedReader(Charsets.UTF_8)
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline) {
                val line = reader.readLine() ?: break
                val obj = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                if (obj["type"]?.jsonPrimitive?.contentOrNull != "control_response") continue
                val resp = obj["response"]?.jsonObject ?: continue
                if (resp["request_id"]?.jsonPrimitive?.contentOrNull != requestId) continue
                commands = resp["response"]?.jsonObject?.get("commands")?.jsonArray ?: JsonArray(emptyList())
                break
            }
            watchdog.interrupt()
            log.info("probeSlashCommands: harvested ${commands.size} command(s)/skill(s)")
            commands
        } catch (e: Exception) {
            log.warn("probeSlashCommands failed: ${e.message}")
            JsonArray(emptyList())
        } finally {
            proc?.destroyForcibly()
        }
    }

    /**
     * Parses the human-readable `claude mcp list` output. Each server line looks like:
     *   `name: https://host/mcp (HTTP) - ✓ Connected`
     *   `claude.ai Gmail: https://.../mcp/v1 - ! Needs authentication`
     *   `name: /path/to/cmd arg - ✓ Connected`   (stdio, no `(TYPE)` parenthetical)
     * The leading "Checking MCP server health…" header and blank lines are ignored.
     */
    private fun parseMcpListOutput(output: String, scopes: Map<String, String>): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val colon = line.indexOf(": ")
            val dash = line.lastIndexOf(" - ")
            // Both separators are required for a server entry; skips headers/notices.
            if (colon <= 0 || dash <= colon) continue

            val name = line.substring(0, colon).trim()
            var target = line.substring(colon + 2, dash).trim()
            val statusText = line.substring(dash + 3).trim()

            // Pull a trailing "(HTTP)" / "(SSE)" type hint off the target if present.
            var type: String? = null
            val typeMatch = Regex("""\(([^)]+)\)$""").find(target)
            if (typeMatch != null) {
                type = typeMatch.groupValues[1].trim().lowercase()
                target = target.substring(0, typeMatch.range.first).trim()
            }
            val resolvedType = type ?: if (target.startsWith("http")) "http" else "stdio"

            val status = when {
                statusText.contains("Connected", ignoreCase = true) -> "connected"
                statusText.contains("auth", ignoreCase = true)      -> "needs-auth"
                statusText.contains("Failed", ignoreCase = true)    -> "failed"
                statusText.contains("Pending", ignoreCase = true)   -> "pending"
                statusText.contains("Disabled", ignoreCase = true)  -> "disabled"
                else                                                  -> "pending"
            }

            val scope = when {
                scopes.containsKey(name)        -> scopes.getValue(name)
                name.startsWith("claude.ai")    -> "claudeai"
                else                            -> "user"
            }

            result += buildJsonObject {
                put("name", name)
                put("status", status)
                put("scope", scope)
                put("config", buildJsonObject {
                    put("type", resolvedType)
                    if (resolvedType == "stdio") put("command", target) else put("url", target)
                })
                if (status == "failed" || status == "needs-auth") put("error", statusText)
            }
        }
        return result
    }

    /**
     * Builds a server-name → scope map from the on-disk config so the panel can group servers.
     * Top-level `~/.claude.json` `mcpServers` → "user"; that file's `projects[cwd].mcpServers`
     * → "local"; a project `.mcp.json` → "project".
     */
    private fun readConfiguredScopes(cwd: String): Map<String, String> {
        val scopes = mutableMapOf<String, String>()

        fun keysOf(obj: JsonObject?): Set<String> =
            (obj?.get("mcpServers") as? JsonObject)?.keys ?: emptySet()

        runCatching {
            val claudeJson = File(SystemProperties.getUserHome(), ".claude.json")
            if (claudeJson.exists()) {
                val root = Json.parseToJsonElement(claudeJson.readText()).jsonObject
                keysOf(root).forEach { scopes[it] = "user" }
                val projects = root["projects"] as? JsonObject
                val projectEntry = projects?.entries?.firstOrNull { (k, _) ->
                    k.replace('\\', '/').equals(cwd.replace('\\', '/'), ignoreCase = true)
                }?.value as? JsonObject
                keysOf(projectEntry).forEach { scopes[it] = "local" }
            }
        }

        runCatching {
            val mcpJson = File(cwd, ".mcp.json")
            if (mcpJson.exists()) {
                val root = Json.parseToJsonElement(mcpJson.readText()).jsonObject
                keysOf(root).forEach { scopes[it] = "project" }
            }
        }

        return scopes
    }

    /**
     * On Windows, Claude CLI requires Git Bash for some shell operations.
     * Returns the path to bash.exe if found, or null.
     */
    fun findGitBash(): String? {
        if (!isWindows) return null
        val candidates = listOf(
            "C:/Program Files/Git/bin/bash.exe",
            "C:/Program Files (x86)/Git/bin/bash.exe",
            System.getenv("ProgramFiles")?.let { "$it/Git/bin/bash.exe" },
            System.getenv("LOCALAPPDATA")?.let { "$it/Programs/Git/bin/bash.exe" }
        )
        return candidates.filterNotNull()
            .map { File(it) }
            .firstOrNull { it.exists() }
            ?.absolutePath
    }
}
