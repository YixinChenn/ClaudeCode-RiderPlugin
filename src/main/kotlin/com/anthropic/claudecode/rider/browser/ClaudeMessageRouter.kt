package com.anthropic.claudecode.rider.browser

import com.anthropic.claudecode.rider.process.ClaudeProcessConfig
import com.anthropic.claudecode.rider.process.ClaudeProcessManager
import com.anthropic.claudecode.rider.settings.ClaudeSettings
import com.anthropic.claudecode.rider.toolwindow.ClaudeIconManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.serialization.json.*
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefMessageRouterHandlerAdapter
import java.awt.Desktop
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Handles all JavaScript -> Kotlin IPC messages from the JCEF webview.
 *
 * The webview uses a bidirectional RPC protocol:
 *  - Top-level types: "request" (RPC call), "launch_claude", "io_message", "close_channel", "interrupt_claude"
 *  - RPC request envelope: { type, requestId, channelId, request: { type, ...fields } }
 *  - Messages TO the webview are wrapped in { type: "from-extension", message: <inner> } by ClaudeBrowserManager
 */
class ClaudeMessageRouter(
    private val project: Project,
    private val browserManager: ClaudeBrowserManager
) : CefMessageRouterHandlerAdapter() {

    private val log = Logger.getInstance(ClaudeMessageRouter::class.java)

    @Volatile private var listFilesDebounce: java.util.concurrent.ScheduledFuture<*>? = null

    override fun onQuery(
        browser: CefBrowser?,
        frame: CefFrame?,
        queryId: Long,
        request: String?,
        persistent: Boolean,
        callback: CefQueryCallback?
    ): Boolean {
        if (request == null) return false

        // onQuery fires on the CEF I/O thread. All IntelliJ platform APIs
        // (project services, VFS, tool windows) must run on the EDT, so we
        // dispatch the entire message body there and call callback on EDT too.
        ApplicationManager.getApplication().invokeLater {
            try {
                val json = Json.parseToJsonElement(request).jsonObject
                val type = json["type"]?.jsonPrimitive?.content ?: ""

                when (type) {
                    "request"         -> handleRpcRequest(json)
                    "launch_claude"   -> handleLaunchClaude(json)
                    "io_message"      -> handleIoMessage(json)
                    "close_channel"   -> handleCloseChannel(json)
                    "interrupt_claude"-> handleInterruptClaude(json)
                    "retry_load"      -> browserManager.loadWebview()
                    "response"        -> handleWebviewResponse(json)
                    "cancel_request"  -> handleCancelRequest(json)
                    else              -> log.debug("Unhandled top-level message type: $type")
                }

                callback?.success("")
            } catch (e: Exception) {
                log.warn("Error processing message from webview: ${e.message}", e)
                callback?.failure(500, e.message ?: "Internal error")
            }
        }

        return true
    }

    // ── RPC dispatcher ──────────────────────────────────────────────────────

    private fun handleRpcRequest(envelope: JsonObject) {
        val requestId = envelope["requestId"]?.jsonPrimitive?.content ?: ""
        val channelId = envelope["channelId"]?.jsonPrimitive?.contentOrNull ?: ""
        val req       = envelope["request"]?.jsonObject ?: return
        val reqType   = req["type"]?.jsonPrimitive?.content ?: ""

        log.debug("RPC request type=$reqType requestId=$requestId")

        when (reqType) {
            "init"                    -> handleInit(requestId)
            "get_claude_state"        -> {
                ensureSlashCommandsProbed()
                sendResponse(requestId, buildJsonObject {
                    put("type", "get_claude_state_response")
                    put("config", buildClaudeConfig())
                })
            }
            "get_auth_status"         -> handleGetAuthStatus(requestId)
            "login"                   -> sendResponse(requestId, buildJsonObject {
                put("type", "login_response")
                put("auth", buildJsonObject {
                    put("authMethod", "claudeai")
                    put("email", JsonNull)
                    put("subscriptionType", JsonNull)
                })
            })
            "list_sessions_request"   -> handleListSessions(requestId)
            "get_session_request"     -> handleGetSession(requestId, req)
            "new_conversation_tab"    -> handleNewConversationTab(requestId, req)
            "get_current_selection"   -> handleGetCurrentSelection(requestId)
            "open_file"               -> handleOpenFile(requestId, req)
            "open_url"                -> handleOpenUrl(requestId, req)
            "show_notification"       -> handleShowNotification(requestId, req)
            "log_event"               -> sendResponse(requestId, buildJsonObject { put("type", "log_event_response") })
            "rename_tab"              -> handleRenameTab(requestId, req)
            "update_session_state"    -> sendResponse(requestId, buildJsonObject { put("type", "update_session_state_response") })
            "delete_session"          -> handleDeleteSession(requestId, req)
            "rename_session"          -> handleRenameSession(requestId, req)
            "open_diff"               -> handleOpenDiff(requestId, req)
            "fork_conversation"       -> sendResponse(requestId, buildJsonObject { put("type", "fork_conversation_response") })
            "message_rated"           -> sendResponse(requestId, buildJsonObject { put("type", "message_rated_response") })
            "set_permission_mode"     -> handleSetPermissionMode(requestId, channelId, req)
            "set_model"               -> handleSetModel(requestId, channelId, req)
            "set_thinking_level"      -> handleSetThinkingLevel(requestId, channelId, req)
            "apply_settings"          -> sendResponse(requestId, buildJsonObject { put("type", "apply_settings_response") })
            "get_asset_uris"          -> sendResponse(requestId, buildJsonObject { put("type", "asset_uris_response"); put("uris", buildJsonObject {}) })
            "request_usage_update"    -> sendResponse(requestId, buildJsonObject { put("type", "request_usage_update_response") })
            "dismiss_onboarding"      -> sendResponse(requestId, buildJsonObject { put("type", "dismiss_onboarding_response") })
            "dismiss_terminal_banner" -> sendResponse(requestId, buildJsonObject { put("type", "dismiss_terminal_banner_response") })
            "dismiss_review_upsell_banner" -> sendResponse(requestId, buildJsonObject { put("type", "dismiss_review_upsell_banner_response") })
            "list_plugins"            -> sendResponse(requestId, buildJsonObject {
                put("type", "list_plugins_response")
                put("installed", JsonArray(emptyList()))
                put("available", JsonArray(emptyList()))
            })
            "list_marketplaces"       -> sendResponse(requestId, buildJsonObject {
                put("type", "list_marketplaces_response")
                put("marketplaces", JsonArray(emptyList()))
            })
            "get_mcp_servers"         -> handleGetMcpServers(requestId, channelId)
            "get_context_usage"       -> sendResponse(requestId, buildJsonObject { put("type", "get_context_usage_response") })
            "check_git_status"        -> handleCheckGitStatus(requestId, req)
            "list_files_request"      -> handleListFiles(requestId, req)
            "generate_session_title"  -> sendResponse(requestId, buildJsonObject { put("type", "generate_session_title_response"); put("title", JsonNull) })
            "list_remote_sessions"    -> sendResponse(requestId, buildJsonObject { put("type", "list_remote_sessions_response"); put("sessions", JsonArray(emptyList())) })
            "open_folder"             -> sendResponse(requestId, buildJsonObject { put("type", "open_folder_response") })
            "open_config"             -> handleOpenConfig(requestId)
            "open_help"               -> handleOpenHelp(requestId)
            "open_output_panel"       -> handleOpenOutputPanel(requestId)
            "open_markdown_preview"   -> handleOpenMarkdownPreview(requestId, req)
            "close_plan_preview"      -> handleClosePlanPreview(requestId, req)
            "remove_plan_comment"     -> handleRemovePlanComment(requestId, req)
            else -> {
                log.debug("Unhandled RPC request type: $reqType — sending stub response")
                sendResponse(requestId, buildJsonObject { put("type", "${reqType}_response") })
            }
        }
    }

    // ── init ────────────────────────────────────────────────────────────────

    private fun handleInit(requestId: String) {
        val response = buildJsonObject {
            put("type", "init_response")
            put("state", buildInitState())
        }
        sendResponse(requestId, response)

        // Push the current file context now that the React app is mounted and listening.
        browserManager.sendCurrentFileContext()
    }

    /** The host "state" object the webview reads into `config.value` (init_response + update_state). */
    private fun buildInitState(): JsonObject {
        val cwd = project.basePath ?: System.getProperty("user.home", "")
        val settings = ClaudeSettings.getInstance()
        val platform = when {
            SystemInfo.isWindows -> "windows"
            SystemInfo.isMac     -> "mac"
            else                 -> "linux"
        }
        return buildJsonObject {
            put("defaultCwd", cwd)
            put("openNewInTab", false)
            put("showTerminalBanner", false)
            put("showReviewUpsellBanner", false)
            put("isOnboardingEnabled", false)
            put("isOnboardingDismissed", true)
            put("authStatus", buildJsonObject {
                put("authMethod", "claudeai")
                put("email", JsonNull)
                put("subscriptionType", JsonNull)
            })
            put("modelSetting", settings.model)
            put("thinkingLevel", settings.thinkingLevel)
            put("initialPermissionMode", settings.initialPermissionMode)
            put("allowDangerouslySkipPermissions", false)
            put("platform", platform)
            put("speechToTextEnabled", false)
            put("speechToTextMicDenied", false)
            put("marketplaceType", "none")
            put("useCtrlEnterToSend", settings.useCtrlEnterToSend)
            put("chromeMcpState", buildJsonObject { put("status", "disconnected") })
            put("settings", buildJsonObject {})
            // claudeSettings must have effective.permissions to avoid NPE in the webview
            put("claudeSettings", buildJsonObject {
                put("effective", buildJsonObject {
                    put("permissions", buildJsonObject {})
                    put("model", JsonNull)
                })
                put("applied", buildJsonObject {})
                put("errors", JsonArray(emptyList()))
            })
            put("experimentGates", buildJsonObject {
                // Enables the "Auto" permission mode in the webview's mode menu.
                // Availability is further gated by the selected model's supportsAutoMode flag.
                put("tengu_auto_mode_state", "enabled")
            })
            put("spinnerVerbsConfig", JsonNull)
            put("currentRepo", JsonNull)
        }
    }

    // ── slash commands / skills ───────────────────────────────────────────────

    /**
     * The `/` command + skill catalog, as `{name, description, argumentHint}` objects probed
     * from the CLI's `initialize` handshake (see [ClaudeProcessConfig.probeSlashCommands]).
     * This is the same data the VS Code extension's `loadConfig()` resolves into `config.commands`.
     */
    @Volatile private var cachedSlashCommands: JsonArray = JsonArray(emptyList())
    /** Guards against launching more than one probe; the catalog is stable per session. */
    private val slashCommandsProbeStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * The webview "claudeConfig" object (set from `get_claude_state` and `update_state`).
     * Its `commands` array is the *only* source the React bundle reads to populate the
     * `/` slash-command picker, so it must carry the probed command/skill catalog.
     */
    private fun buildClaudeConfig(): JsonObject = buildJsonObject {
        put("claudeSettings", buildJsonObject {
            put("effective", buildJsonObject {
                put("permissions", buildJsonObject {})
                put("model", JsonNull)
            })
            put("applied", buildJsonObject {})
            put("errors", JsonArray(emptyList()))
        })
        put("settings", buildJsonObject {})
        put("models", buildModelsArray())
        put("commands", cachedSlashCommands)
    }

    /**
     * Kicks off a one-time background probe of the slash command/skill catalog (mirrors the
     * VS Code extension's `loadConfig()`/`spawnConfigProbe()`), then pushes the result to the
     * webview as an `update_state`. The `/` picker registers commands reactively from
     * `claudeConfig.commands`, so the push makes them appear without any user action.
     */
    private fun ensureSlashCommandsProbed() {
        if (!slashCommandsProbeStarted.compareAndSet(false, true)) return
        val cwd = project.basePath ?: System.getProperty("user.home", "")
        ApplicationManager.getApplication().executeOnPooledThread {
            val commands = try {
                ClaudeProcessConfig.probeSlashCommands(cwd)
            } catch (e: Exception) {
                log.warn("slash command probe failed: ${e.message}")
                JsonArray(emptyList())
            }
            if (commands.isNotEmpty()) {
                ApplicationManager.getApplication().invokeLater { pushSlashCommands(commands) }
            } else {
                // Allow a later retry (e.g. CLI installed after first open) on the next state fetch.
                slashCommandsProbeStarted.set(false)
            }
        }
    }

    /**
     * Caches the probed command catalog and pushes it to the webview. The envelope must match the
     * VS Code extension's `pushStateUpdate()` exactly: a top-level `request` message whose inner
     * `request.type` is "update_state", with `state` and `config` as siblings of `type` inside
     * `request`. The webview dispatches on `request.type` and reads `$.request.state` /
     * `$.request.config`. The state snapshot is rebuilt from persisted settings, so it faithfully
     * mirrors current model/permission/thinking selections — only `commands` is genuinely new.
     */
    private fun pushSlashCommands(commands: JsonArray) {
        cachedSlashCommands = commands
        log.info("Pushing ${commands.size} slash command(s)/skill(s) to webview")
        val message = buildJsonObject {
            put("type", "request")
            put("channelId", "")
            put("requestId", java.util.UUID.randomUUID().toString())
            put("request", buildJsonObject {
                put("type", "update_state")
                put("state", buildInitState())
                put("config", buildClaudeConfig())
            })
        }
        browserManager.sendToWebview(message.toString())
    }

    // ── auth status ─────────────────────────────────────────────────────────

    private fun handleGetAuthStatus(requestId: String) {
        sendResponse(requestId, buildJsonObject {
            put("type", "get_auth_status_response")
            put("status", buildJsonObject {
                put("authMethod", "claudeai")
                put("email", JsonNull)
                put("subscriptionType", JsonNull)
            })
        })
    }

    // ── MCP servers ───────────────────────────────────────────────────────────

    /**
     * The webview's `/mcp` panel asks the host for the server list (with per-server tools) via
     * this RPC. Mirrors the official VS Code extension, whose `mcpServerStatus()` issues a
     * `control_request {subtype:"mcp_status"}` against the **live conversation** and returns the
     * `mcpServers` array — each entry carrying live `status` plus the connected server's `tools`.
     *
     * Querying the live channel (rather than a detached `claude mcp list`) is what makes a new
     * conversation re-fetch its own tool list: each channel reports its own MCP connection state,
     * and the per-server `tools` only `claude mcp list` cannot provide appear once connected.
     *
     * Falls back to `claude mcp list` when there is no live channel yet (e.g. the panel is opened
     * before any conversation has been launched).
     */
    private fun handleGetMcpServers(requestId: String, channelId: String) {
        val processManager = ClaudeProcessManager.getInstance(project)
        if (channelId.isNotBlank() && processManager.hasChannel(channelId)) {
            processManager.sendControlRequestAwait(channelId, "mcp_status") { inner ->
                val servers = inner?.get("mcpServers")?.let { it as? JsonArray }
                if (servers != null) {
                    ApplicationManager.getApplication().invokeLater {
                        sendResponse(requestId, buildJsonObject {
                            put("type", "get_mcp_servers_response")
                            put("mcpServers", servers)
                        })
                    }
                } else {
                    // mcp_status failed/timed out — fall back to the detached listing.
                    respondWithMcpListFallback(requestId)
                }
            }
            return
        }
        respondWithMcpListFallback(requestId)
    }

    /** `claude mcp list` health-checks the servers (slow), so run it off the EDT. */
    private fun respondWithMcpListFallback(requestId: String) {
        val cwd = project.basePath ?: System.getProperty("user.home", "")
        ApplicationManager.getApplication().executeOnPooledThread {
            val servers = try {
                ClaudeProcessConfig.listMcpServers(cwd)
            } catch (e: Exception) {
                log.warn("get_mcp_servers failed: ${e.message}")
                emptyList()
            }
            ApplicationManager.getApplication().invokeLater {
                sendResponse(requestId, buildJsonObject {
                    put("type", "get_mcp_servers_response")
                    put("mcpServers", JsonArray(servers))
                })
            }
        }
    }

    // ── sessions ─────────────────────────────────────────────────────────────

    private fun handleListSessions(requestId: String) {
        val sessions = loadSessionsFromDisk()
        sendResponse(requestId, buildJsonObject {
            put("type", "list_sessions_response")
            put("sessions", JsonArray(sessions))
        })
    }

    private fun loadSessionsFromDisk(): List<JsonElement> {
        val cwd = project.basePath ?: return emptyList()
        val gitBranch = readGitBranch(cwd)
        return try {
            val claudeDir = Paths.get(System.getProperty("user.home"), ".claude", "projects")
            if (!Files.exists(claudeDir)) return emptyList()

            // Claude CLI encodes project paths as directory names by replacing every
            // non-alphanumeric character with a hyphen, e.g.:
            //   "F:\GitHub\my_project" → "F--GitHub-my-project"
            val encodedDirName = cwd.replace(Regex("[^A-Za-z0-9]"), "-")
            log.info("loadSessionsFromDisk: cwd=$cwd encodedDirName=$encodedDirName")
            val projectDir = Files.list(claudeDir).use { stream ->
                stream.filter { Files.isDirectory(it) }
                      .filter { dir -> dir.fileName.toString() == encodedDirName }
                      .findFirst()
                      .orElse(null)
            }

            if (projectDir == null) return emptyList()

            Files.list(projectDir).use { stream ->
                val settings = ClaudeSettings.getInstance()
                stream.filter { it.toString().endsWith(".jsonl") }
                      .filter { !settings.isSessionHidden(it.fileName.toString().removeSuffix(".jsonl")) }
                      .sorted { a, b ->
                          Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a))
                      }
                      .limit(10)
                      .map { file ->
                          val sessionId = file.fileName.toString().removeSuffix(".jsonl")
                          val lastMod = Files.getLastModifiedTime(file).toMillis()
                          val size = Files.size(file)
                          val summary = readSessionSummary(file)
                          buildJsonObject {
                              put("id", sessionId)
                              put("lastModified", lastMod)
                              put("fileSize", size)
                              put("summary", summary)
                              if (gitBranch != null) put("gitBranch", gitBranch) else put("gitBranch", JsonNull)
                              put("worktree", JsonNull)
                              put("isCurrentWorkspace", true)
                          }
                      }.toList()
            }
        } catch (e: Exception) {
            log.warn("Failed to load sessions from disk", e)
            emptyList()
        }
    }

    private fun readGitBranch(cwd: String): String? {
        return try {
            val proc = ProcessBuilder("git", "-C", cwd, "branch", "--show-current")
                .redirectErrorStream(true)
                .start()
            val branch = proc.inputStream.bufferedReader().readLine()?.trim()
            proc.waitFor()
            branch?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    // Matches XML-like system context blocks injected by the IDE extension, e.g.:
    //   <ide_opened_file>...</ide_opened_file>
    //   <local-command-caveat>...</local-command-caveat>
    // [\\s\\S]*? matches any character including newlines (non-greedy).
    private val systemTagRegex = Regex("<[a-zA-Z_][a-zA-Z_0-9-]*>[\\s\\S]*?</[a-zA-Z_][a-zA-Z_0-9-]*>")

    private fun readSessionSummary(path: java.nio.file.Path): String {
        return try {
            var summaryTitle: String? = null
            var firstUserContent: String? = null
            Files.newBufferedReader(path).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val json = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                    // summary records (written by rename or Claude itself) take priority
                    if (json["type"]?.jsonPrimitive?.contentOrNull == "summary") {
                        summaryTitle = json["summary"]?.jsonPrimitive?.contentOrNull
                        continue
                    }
                    // fall back to first user message that contains actual user-typed text
                    if (firstUserContent == null) {
                        val role = json["role"]?.jsonPrimitive?.contentOrNull
                            ?: json["message"]?.jsonObject?.get("role")?.jsonPrimitive?.contentOrNull
                        if (role == "user") {
                            val contentEl = json["content"]
                                ?: json["message"]?.jsonObject?.get("content")
                            val text = extractUserText(contentEl)
                            if (!text.isNullOrBlank()) firstUserContent = text.take(80)
                        }
                    }
                }
            }
            summaryTitle ?: firstUserContent ?: "Session"
        } catch (e: Exception) {
            "Session"
        }
    }

    /**
     * Extracts the actual user-typed text from a message content element.
     * Strips system-injected XML context blocks (<ide_opened_file>, <local-command-caveat>, etc.)
     * that the IDE extension prepends to user messages before sending to Claude.
     */
    private fun extractUserText(content: JsonElement?): String? {
        val textBlocks: List<String> = when (content) {
            is JsonPrimitive -> listOf(content.contentOrNull ?: return null)
            is JsonArray -> content.mapNotNull {
                runCatching { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            }
            else -> return null
        }
        for (text in textBlocks) {
            val stripped = systemTagRegex.replace(text, "").trim()
            if (stripped.isNotBlank()) return stripped
        }
        return null
    }

    private fun handleGetSession(requestId: String, req: JsonObject) {
        ClaudeIconManager.setIdle(project)
        val sessionId = req["sessionId"]?.jsonPrimitive?.contentOrNull
        if (sessionId == null) {
            sendResponse(requestId, buildJsonObject {
                put("type", "error"); put("error", "No sessionId provided")
            })
            return
        }
        val messages = loadSessionMessages(sessionId)
        sendResponse(requestId, buildJsonObject {
            put("type", "get_session_response")
            put("messages", JsonArray(messages))
            put("sessionDiffs", JsonArray(emptyList()))
        })
    }

    private fun loadSessionMessages(sessionId: String): List<JsonElement> {
        val cwd = project.basePath ?: return emptyList()
        return try {
            val claudeDir = Paths.get(System.getProperty("user.home"), ".claude", "projects")
            Files.walk(claudeDir, 2).use { stream ->
                val file = stream.filter { it.fileName.toString() == "$sessionId.jsonl" }.findFirst().orElse(null)
                    ?: return emptyList()
                Files.readAllLines(file).mapNotNull { line ->
                    if (line.isBlank()) null
                    else runCatching { Json.parseToJsonElement(line) }.getOrNull()
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to load session $sessionId", e)
            emptyList()
        }
    }

    // ── session delete / rename ──────────────────────────────────────────────

    private fun handleDeleteSession(requestId: String, req: JsonObject) {
        val sessionId = req["sessionId"]?.jsonPrimitive?.contentOrNull
        if (sessionId == null) {
            sendResponse(requestId, buildJsonObject { put("type", "delete_session_response") })
            return
        }
        // Match the official VS Code extension: "delete" is a persistent hide, not a file
        // removal. The official host does `settings.hideSession(id)` (appends to a persisted
        // hiddenSessionIds list) and never touches the .jsonl. Hiding instead of deleting
        // keeps it durable across restarts and avoids the race where a resumed
        // `claude --resume` subprocess recreates a just-deleted session file.
        ClaudeSettings.getInstance().hideSession(sessionId)
        log.info("Hid session $sessionId from history")
        sendResponse(requestId, buildJsonObject { put("type", "delete_session_response") })
    }

    private fun handleRenameSession(requestId: String, req: JsonObject) {
        val sessionId = req["sessionId"]?.jsonPrimitive?.contentOrNull
        val title     = req["title"]?.jsonPrimitive?.contentOrNull
        if (sessionId == null || title == null) {
            sendResponse(requestId, buildJsonObject { put("type", "rename_session_response"); put("skipped", true) })
            return
        }
        try {
            val claudeDir = Paths.get(System.getProperty("user.home"), ".claude", "projects")
            val file = Files.walk(claudeDir, 2).use { stream ->
                stream.filter { it.fileName.toString() == "$sessionId.jsonl" }.findFirst().orElse(null)
            }
            if (file != null) {
                // Append a summary record so readSessionSummary picks up the new title
                val summaryLine = buildJsonObject {
                    put("type", "summary")
                    put("summary", title)
                    put("leafUuid", "")
                }.toString()
                Files.write(file, ("\n" + summaryLine + "\n").toByteArray(Charsets.UTF_8),
                    java.nio.file.StandardOpenOption.APPEND)
                log.info("Renamed session $sessionId to '$title'")
                sendResponse(requestId, buildJsonObject { put("type", "rename_session_response"); put("skipped", false) })
                return
            }
        } catch (e: Exception) {
            log.warn("Failed to rename session $sessionId", e)
        }
        sendResponse(requestId, buildJsonObject { put("type", "rename_session_response"); put("skipped", true) })
    }

    // ── new conversation tab ─────────────────────────────────────────────────

    private fun handleNewConversationTab(requestId: String, req: JsonObject) {
        val channelId = java.util.UUID.randomUUID().toString()
        val cwd = req["cwd"]?.jsonPrimitive?.contentOrNull ?: project.basePath ?: System.getProperty("user.home")
        val initialPrompt = req["initialPrompt"]?.jsonPrimitive?.contentOrNull
        val sessionId = req["sessionId"]?.jsonPrimitive?.contentOrNull

        val processManager = ClaudeProcessManager.getInstance(project)
        processManager.launchChannel(channelId, cwd, sessionId, "default", "none")

        if (!initialPrompt.isNullOrBlank()) {
            val userMsg = buildJsonObject {
                put("type", "user")
                put("session_id", "")
                put("parent_tool_use_id", JsonNull)
                put("message", buildJsonObject {
                    put("role", "user")
                    put("content", initialPrompt)
                })
            }
            processManager.sendIoMessage(channelId, userMsg.toString(), done = false)
        }

        sendResponse(requestId, buildJsonObject {
            put("type", "new_conversation_tab_response")
            put("channelId", channelId)
        })
    }

    // ── Claude process channel management ────────────────────────────────────

    private fun handleLaunchClaude(json: JsonObject) {
        val channelId     = json["channelId"]?.jsonPrimitive?.contentOrNull ?: return
        val cwd           = json["cwd"]?.jsonPrimitive?.contentOrNull ?: project.basePath ?: System.getProperty("user.home")
        val resume        = json["resume"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
        val permMode      = json["permissionMode"]?.jsonPrimitive?.contentOrNull ?: "default"
        val thinkingLevel = json["thinkingLevel"]?.jsonPrimitive?.contentOrNull ?: "none"

        log.info("launch_claude channelId=$channelId cwd=$cwd resume=$resume permMode=$permMode")
        ClaudeIconManager.setPending(project)
        ClaudeProcessManager.getInstance(project)
            .launchChannel(channelId, cwd, resume, permMode, thinkingLevel)
    }

    private fun handleIoMessage(json: JsonObject) {
        val channelId = json["channelId"]?.jsonPrimitive?.contentOrNull ?: return
        val message   = json["message"]?.jsonObject ?: return
        val done      = json["done"]?.jsonPrimitive?.booleanOrNull ?: false

        if (message["type"]?.jsonPrimitive?.contentOrNull == "user") {
            ClaudeProcessManager.getInstance(project)
                .sendIoMessage(channelId, message.toString(), done)
        }
    }

    private fun handleCloseChannel(json: JsonObject) {
        val channelId = json["channelId"]?.jsonPrimitive?.contentOrNull ?: return
        log.info("close_channel $channelId (requested by webview)")
        ClaudeProcessManager.getInstance(project).closeChannel(channelId)
        ClaudeIconManager.setDone(project)
    }

    private fun handleInterruptClaude(json: JsonObject) {
        val channelId = json["channelId"]?.jsonPrimitive?.contentOrNull ?: return
        log.info("interrupt_claude $channelId")
        ClaudeProcessManager.getInstance(project).interruptChannel(channelId)
    }

    /** Handles a webview `{type:"response"}` — typically the result of a tool_permission_request. */
    private fun handleWebviewResponse(json: JsonObject) {
        val requestId = json["requestId"]?.jsonPrimitive?.contentOrNull ?: return
        val response  = json["response"]?.jsonObject ?: return
        ClaudeProcessManager.getInstance(project).handlePermissionResponse(requestId, response)
    }

    /** Handles a webview `{type:"cancel_request"}` — user dismissed the permission dialog. */
    private fun handleCancelRequest(json: JsonObject) {
        val requestId = json["targetRequestId"]?.jsonPrimitive?.contentOrNull ?: return
        ClaudeProcessManager.getInstance(project).cancelPermissionRequest(requestId)
    }

    // ── IDE integration ──────────────────────────────────────────────────────

    private fun handleGetCurrentSelection(requestId: String) {
        ApplicationManager.getApplication().runReadAction {
            val editorManager = FileEditorManager.getInstance(project)
            val editor = editorManager.selectedTextEditor
            val vFile = editorManager.selectedFiles.firstOrNull()
            val selection = editor?.selectionModel?.selectedText ?: ""
            val filePath  = vFile?.path ?: ""
            val startLine = editor?.selectionModel?.selectionStartPosition?.line ?: 0
            val endLine   = editor?.selectionModel?.selectionEndPosition?.line ?: 0
            val languageId = vFile?.let {
                FileTypeManager.getInstance().getFileTypeByFile(it).name.lowercase()
            } ?: ""
            sendResponse(requestId, buildJsonObject {
                put("type", "get_current_selection_response")
                put("selection", buildJsonObject {
                    put("filePath", filePath)
                    put("selectedText", selection)
                    put("startLine", startLine)
                    put("endLine", endLine)
                    put("languageId", languageId)
                })
            })
        }
    }

    private fun handleOpenDiff(requestId: String, req: JsonObject) {
        // The webview sends open_diff for Edit/Write tool permissions.
        // Returning the edits unchanged signals "user accepted the diff" and allows the tool.
        // Returning null would trigger $.reject("User cancelled") and deny the tool.
        sendResponse(requestId, buildJsonObject {
            put("type", "open_diff_response")
            put("newEdits", req["edits"] ?: JsonNull)
        })
    }

    private fun handleOpenFile(requestId: String, req: JsonObject) {
        val filePath = req["filePath"]?.jsonPrimitive?.contentOrNull ?: run {
            sendResponse(requestId, buildJsonObject { put("type", "open_file_response") }); return
        }
        val line = req["location"]?.jsonObject?.get("line")?.jsonPrimitive?.intOrNull ?: 0
        val col  = req["location"]?.jsonObject?.get("column")?.jsonPrimitive?.intOrNull ?: 0
        ApplicationManager.getApplication().invokeLater {
            // Normalize Windows backslashes and use refreshAndFindFileByPath so files outside
            // the project (e.g. ~/.claude/plans/) and newly-created files are found reliably.
            val normalized = filePath.replace('\\', '/')
            val vFile = LocalFileSystem.getInstance().findFileByPath(normalized)
                ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(normalized)
            if (vFile != null) OpenFileDescriptor(project, vFile, line, col).navigate(true)
        }
        sendResponse(requestId, buildJsonObject { put("type", "open_file_response") })
    }

    // ── Plan preview ──────────────────────────────────────────────────────────

    private fun handleOpenMarkdownPreview(requestId: String, req: JsonObject) {
        val channelId      = req["channelId"]?.jsonPrimitive?.contentOrNull ?: ""
        val content        = req["content"]?.jsonPrimitive?.contentOrNull ?: ""
        val title          = req["title"]?.jsonPrimitive?.contentOrNull ?: "Claude's Plan"
        val enableComments = req["enableComments"]?.jsonPrimitive?.booleanOrNull ?: true
        browserManager.planPreviewPanel.show(channelId, content, title, enableComments)
        sendResponse(requestId, buildJsonObject { put("type", "open_markdown_preview_response") })
    }

    private fun handleClosePlanPreview(requestId: String, req: JsonObject) {
        browserManager.planPreviewPanel.close()
        sendResponse(requestId, buildJsonObject { put("type", "close_plan_preview_response") })
    }

    private fun handleRemovePlanComment(requestId: String, req: JsonObject) {
        val commentId = req["commentId"]?.jsonPrimitive?.contentOrNull ?: ""
        browserManager.planPreviewPanel.removeComment(commentId)
        sendResponse(requestId, buildJsonObject { put("type", "remove_plan_comment_response") })
    }

    private fun handleOpenUrl(requestId: String, req: JsonObject) {
        val url = req["url"]?.jsonPrimitive?.contentOrNull ?: run {
            sendResponse(requestId, buildJsonObject { put("type", "open_url_response") }); return
        }
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url))
        } catch (e: Exception) {
            log.warn("Failed to open URL: $url", e)
        }
        sendResponse(requestId, buildJsonObject { put("type", "open_url_response") })
    }

    private fun handleOpenHelp(requestId: String) {
        try {
            if (Desktop.isDesktopSupported())
                Desktop.getDesktop().browse(URI("https://github.com/NoahXia/ClaudeCode-RiderPlugin"))
        } catch (e: Exception) {
            log.warn("Failed to open help URL", e)
        }
        sendResponse(requestId, buildJsonObject { put("type", "open_help_response") })
    }

    private fun handleRenameTab(requestId: String, req: JsonObject) {
        val title = req["title"]?.jsonPrimitive?.contentOrNull
        if (!title.isNullOrBlank()) {
            ApplicationManager.getApplication().invokeLater {
                com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                    .getToolWindow("Claude")
                    ?.setTitle(title)
            }
        }
        sendResponse(requestId, buildJsonObject { put("type", "rename_tab_response") })
    }

    private fun handleOpenConfig(requestId: String) {
        ApplicationManager.getApplication().invokeLater {
            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, "com.anthropic.claudecode.rider.settings")
        }
        sendResponse(requestId, buildJsonObject { put("type", "open_config_response") })
    }

    private fun handleOpenOutputPanel(requestId: String) {
        ApplicationManager.getApplication().invokeLater {
            val twm = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            // Try common tool window IDs for the IDE's log/event panel
            for (id in listOf("Event Log", "Problems View", "Build")) {
                val tw = twm.getToolWindow(id)
                if (tw != null) { tw.show(); break }
            }
        }
        sendResponse(requestId, buildJsonObject { put("type", "open_output_panel_response") })
    }

    private fun handleShowNotification(requestId: String, req: JsonObject) {
        val message  = req["message"]?.jsonPrimitive?.contentOrNull ?: ""
        val severity = req["severity"]?.jsonPrimitive?.contentOrNull ?: "info"
        val notifType = when (severity.lowercase()) {
            "error"   -> NotificationType.ERROR
            "warning" -> NotificationType.WARNING
            else      -> NotificationType.INFORMATION
        }
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Claude Code")
                ?.createNotification(message, notifType)
                ?.notify(project)
        }
        sendResponse(requestId, buildJsonObject { put("type", "show_notification_response") })
    }

    private fun handleListFiles(requestId: String, req: JsonObject) {
        listFilesDebounce?.cancel(false)
        listFilesDebounce = com.intellij.util.concurrency.AppExecutorUtil
            .getAppScheduledExecutorService()
            .schedule({
                ApplicationManager.getApplication().invokeLater {
                    doListFiles(requestId, req)
                }
            }, 500, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun doListFiles(requestId: String, req: JsonObject) {
        val pattern = req["pattern"]?.jsonPrimitive?.contentOrNull ?: ""
        val basePath = project.basePath ?: System.getProperty("user.home")

        val results = mutableListOf<JsonElement>()
        try {
            ApplicationManager.getApplication().runReadAction {
                val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return@runReadAction
                val fileIndex = ProjectFileIndex.getInstance(project)

                if (pattern.isBlank()) {
                    fileIndex.iterateContent { vFile ->
                        if (results.size >= 100) return@iterateContent false
                        if (vFile.path.contains("/.git/") || vFile.path.contains("\\.git\\")) return@iterateContent true
                        val rel = VfsUtilCore.getRelativePath(vFile, baseDir) ?: return@iterateContent true
                        results += buildJsonObject {
                            put("path", rel.replace('\\', '/'))
                            put("name", vFile.name)
                            put("type", if (vFile.isDirectory) "directory" else "file")
                        }
                        true
                    }
                } else {
                    val scope = GlobalSearchScope.projectScope(project)
                    val allNames = FilenameIndex.getAllFilenames(project)
                    for (name in allNames) {
                        if (results.size >= 100) break
                        if (!name.contains(pattern, ignoreCase = true)) continue
                        val files = FilenameIndex.getVirtualFilesByName(name, scope)
                        for (vFile in files) {
                            if (results.size >= 100) break
                            if (!fileIndex.isInContent(vFile)) continue
                            if (vFile.path.contains("/.git/") || vFile.path.contains("\\.git\\")) continue
                            val rel = VfsUtilCore.getRelativePath(vFile, baseDir) ?: continue
                            results += buildJsonObject {
                                put("path", rel.replace('\\', '/'))
                                put("name", name)
                                put("type", if (vFile.isDirectory) "directory" else "file")
                            }
                        }
                    }

                    // Also match on relative path for files whose name didn't match
                    if (results.size < 100) {
                        val matchedPaths = results.mapTo(HashSet()) { it.jsonObject["path"]?.jsonPrimitive?.contentOrNull }
                        fileIndex.iterateContent { vFile ->
                            if (results.size >= 100) return@iterateContent false
                            if (vFile.path.contains("/.git/") || vFile.path.contains("\\.git\\")) return@iterateContent true
                            val rel = VfsUtilCore.getRelativePath(vFile, baseDir) ?: return@iterateContent true
                            val relNorm = rel.replace('\\', '/')
                            if (relNorm in matchedPaths) return@iterateContent true
                            if (!relNorm.contains(pattern, ignoreCase = true)) return@iterateContent true
                            results += buildJsonObject {
                                put("path", relNorm)
                                put("name", vFile.name)
                                put("type", if (vFile.isDirectory) "directory" else "file")
                            }
                            true
                        }
                    }
                }
            }
            results.sortBy { it.jsonObject["path"]?.jsonPrimitive?.contentOrNull ?: "" }
        } catch (e: Exception) {
            log.warn("list_files_request failed: ${e.message}")
        }

        sendResponse(requestId, buildJsonObject {
            put("type", "list_files_response")
            put("files", JsonArray(results))
        })
    }

    // u2500u2500 settings u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500u2500

    private fun handleSetPermissionMode(requestId: String, channelId: String, req: JsonObject) {
        val mode = req["mode"]?.jsonPrimitive?.contentOrNull
            ?: req["permissionMode"]?.jsonPrimitive?.contentOrNull
            ?: return
        log.info("set_permission_mode: $mode")
        ClaudeSettings.getInstance().initialPermissionMode = mode
        if (channelId.isNotEmpty()) {
            ClaudeProcessManager.getInstance(project)
                .sendControlRequest(channelId, "set_permission_mode", buildJsonObject { put("mode", mode) })
        }
        sendResponse(requestId, buildJsonObject { put("type", "set_permission_mode_response"); put("success", true) })
    }

    private fun handleSetModel(requestId: String, channelId: String, req: JsonObject) {
        // webview sends model as object {value: "claude-sonnet-4-6", label: "..."} or string
        val modelValue = req["model"]?.let { el ->
            when (el) {
                is JsonPrimitive -> el.contentOrNull
                is JsonObject -> el["value"]?.jsonPrimitive?.contentOrNull
                else -> null
            }
        } ?: return
        log.info("set_model: $modelValue")
        ClaudeSettings.getInstance().model = modelValue
        val settingsValue = if (modelValue == "default") null else modelValue
        writeUserSetting("model", settingsValue)
        if (channelId.isNotEmpty()) {
            ClaudeProcessManager.getInstance(project)
                .sendControlRequest(channelId, "apply_flag_settings", buildJsonObject {
                    put("settings", buildJsonObject {
                        if (settingsValue != null) put("model", settingsValue) else put("model", JsonNull)
                    })
                })
        }
        sendResponse(requestId, buildJsonObject { put("type", "set_model_response") })
    }

    private fun handleSetThinkingLevel(requestId: String, channelId: String, req: JsonObject) {
        val level = req["thinkingLevel"]?.jsonPrimitive?.contentOrNull ?: return
        log.info("set_thinking_level: $level")
        ClaudeSettings.getInstance().thinkingLevel = level
        if (channelId.isNotEmpty()) {
            val maxTokens = when (level) {
                "high" -> 32000
                "medium" -> 10000
                "low" -> 4000
                else -> 0
            }
            ClaudeProcessManager.getInstance(project)
                .sendControlRequest(channelId, "set_max_thinking_tokens", buildJsonObject {
                    put("max_thinking_tokens", maxTokens)
                })
        }
        sendResponse(requestId, buildJsonObject { put("type", "set_thinking_level_response") })
    }

    private fun writeUserSetting(key: String, value: String?) {
        try {
            val claudeDir = java.nio.file.Paths.get(System.getProperty("user.home"), ".claude")
            Files.createDirectories(claudeDir)
            val settingsFile = claudeDir.resolve("settings.json")
            val existing = if (Files.exists(settingsFile)) {
                runCatching { Json.parseToJsonElement(Files.readString(settingsFile)).jsonObject }.getOrNull()
                    ?: buildJsonObject {}
            } else {
                buildJsonObject {}
            }
            val updated = buildJsonObject {
                existing.forEach { (k, v) -> if (k != key) put(k, v) }
                if (value != null) put(key, value) else { /* delete key by not including it */ }
            }
            Files.writeString(settingsFile, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), updated) + "\n")
            log.info("Wrote $key=${value ?: "null"} to ${settingsFile}")
        } catch (e: Exception) {
            log.warn("Failed to write user setting $key: ${e.message}")
        }
    }

    private fun handleCheckGitStatus(requestId: String, req: JsonObject) {
        val cwd = req["cwd"]?.jsonPrimitive?.contentOrNull ?: project.basePath ?: ""
        try {
            val proc = ProcessBuilder("git", "-C", cwd, "branch", "--show-current")
                .redirectErrorStream(true)
                .start()
            val branch = proc.inputStream.bufferedReader().readLine()?.trim() ?: ""
            proc.waitFor()
            sendResponse(requestId, buildJsonObject {
                put("type", "check_git_status_response")
                put("isGitRepo", branch.isNotEmpty())
                if (branch.isNotEmpty()) put("branch", branch) else put("branch", JsonNull)
                put("hasUncommittedChanges", false)
            })
        } catch (e: Exception) {
            log.debug("check_git_status: git not found or error: ${e.message}")
            sendResponse(requestId, buildJsonObject {
                put("type", "check_git_status_response")
                put("isGitRepo", false)
                put("branch", JsonNull)
                put("hasUncommittedChanges", false)
            })
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun sendResponse(requestId: String, response: JsonObject) {
        val message = buildJsonObject {
            put("type", "response")
            put("requestId", requestId)
            put("response", response)
        }
        browserManager.sendToWebview(message.toString())
    }

    private fun buildModelsArray(): JsonArray {
        val env = ClaudeProcessConfig.buildEnvironment()

        val customSonnet = env["ANTHROPIC_DEFAULT_SONNET_MODEL"]
        val customSonnetName = env["ANTHROPIC_DEFAULT_SONNET_MODEL_NAME"]
        val customSonnetDesc = env["ANTHROPIC_DEFAULT_SONNET_MODEL_DESCRIPTION"]
        val customOpus = env["ANTHROPIC_DEFAULT_OPUS_MODEL"]
        val customOpusName = env["ANTHROPIC_DEFAULT_OPUS_MODEL_NAME"]
        val customOpusDesc = env["ANTHROPIC_DEFAULT_OPUS_MODEL_DESCRIPTION"]
        val customHaiku = env["ANTHROPIC_DEFAULT_HAIKU_MODEL"]
        val customHaikuName = env["ANTHROPIC_DEFAULT_HAIKU_MODEL_NAME"]
        val customHaikuDesc = env["ANTHROPIC_DEFAULT_HAIKU_MODEL_DESCRIPTION"]

        data class Model(
            val value: String,
            val displayName: String,
            val description: String,
            val supportsEffort: Boolean = false,
            val supportsAdaptiveThinking: Boolean = false,
            val supportsFastMode: Boolean = false,
            val supportsAutoMode: Boolean = true
        )
        val models = mutableListOf(
            Model(
                value = "default",
                displayName = "Default (recommended)",
                description = "Use the default model (currently Sonnet 4.6)"
            ),
            Model(
                value = "sonnet",
                displayName = if (customSonnet != null) (customSonnetName ?: customSonnet) else "Sonnet",
                description = if (customSonnet != null) (customSonnetDesc ?: "Custom Sonnet model") else "Sonnet 4.6 · Best for everyday tasks"
            ),
            Model(
                value = "opus",
                displayName = if (customOpus != null) (customOpusName ?: customOpus) else "Opus 4.7",
                description = if (customOpus != null) (customOpusDesc ?: "Custom Opus model") else "Opus 4.7 · Most capable for complex work",
                supportsEffort = true,
                supportsAdaptiveThinking = true,
                supportsFastMode = true,
                supportsAutoMode = true
            ),
            Model(
                value = "haiku",
                displayName = if (customHaiku != null) (customHaikuName ?: customHaiku) else "Haiku",
                description = if (customHaiku != null) (customHaikuDesc ?: "Custom Haiku model") else "Haiku 4.5 · Fastest for quick answers"
            ),
        )

        val customModelOption = env["ANTHROPIC_CUSTOM_MODEL_OPTION"]
        if (!customModelOption.isNullOrBlank() && models.none { it.value == customModelOption }) {
            models.add(Model(
                value = customModelOption,
                displayName = env["ANTHROPIC_CUSTOM_MODEL_OPTION_NAME"] ?: customModelOption,
                description = env["ANTHROPIC_CUSTOM_MODEL_OPTION_DESCRIPTION"] ?: "Custom model ($customModelOption)"
            ))
        }
        return JsonArray(models.map { m ->
            buildJsonObject {
                put("value", m.value)
                put("displayName", m.displayName)
                put("description", m.description)
                put("supportsEffort", m.supportsEffort)
                put("supportsAdaptiveThinking", m.supportsAdaptiveThinking)
                put("supportsFastMode", m.supportsFastMode)
                put("supportsAutoMode", m.supportsAutoMode)
                if (m.supportsEffort) put("supportedEffortLevels", JsonArray(listOf(
                    JsonPrimitive("low"), JsonPrimitive("medium"), JsonPrimitive("high")
                )))
            }
        })
    }
}
