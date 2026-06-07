# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a **JetBrains Rider IDE plugin** written in Kotlin that embeds the Claude Code AI assistant into the IDE. Rather than rewriting the UI, the plugin wraps the existing VS Code extension's pre-built React webview bundle (`src/main/resources/webview/index.js` + `index.css`) and bridges IPC between a JCEF (Chromium Embedded Framework) browser and the `claude` CLI subprocess.

## Build Commands

```bash
./gradlew buildPlugin        # Build the plugin ZIP to build/distributions/
./gradlew runIde             # Launch a sandboxed Rider instance with the plugin loaded
./gradlew clean              # Clean build artifacts
./gradlew verifyPlugin       # Verify plugin compatibility
```

**Toolchain**: Kotlin 2.1.0 (JVM toolchain 17), Gradle 8.9, IntelliJ Platform Gradle plugin 2.6.0. Builds against Rider 2025.1 (`build.gradle.kts` falls back to a 2022.2.1 platform dependency if no local SDK is detected), while `plugin.xml` declares a 2022.2+ minimum. The only third-party runtime dependency is `kotlinx-serialization-json`.

**Prerequisites**: Java 17+. The webview assets must be present at `src/main/resources/webview/index.js` and `index.css` (copied from the VS Code Claude Code extension). They are large (~4.6 MB JS, ~365 KB CSS) and not tracked in git.

There are no automated tests — validation is done by running `./gradlew runIde`.

## Architecture

### Communication Flow

```
React Webview (JCEF)
   └── window.cefQuery() (JS shim of acquireVsCodeApi)
         └── ClaudeMessageRouter.onQuery()       ← dispatches by message type
               ├── handleLaunchClaude()
               │     └── ClaudeProcessManager.launchChannel()
               │           └── claude subprocess (stream-JSON over stdin/stdout)
               └── handleRpcRequest()            ← webview RPC (permissions, context, etc.)
```

**Key invariant**: The React bundle is unmodified VS Code extension code. The `acquireVsCodeApi()` shim in `HtmlTemplateProvider` makes the webview believe it's running inside VS Code.

### Core Components

| Component | Location | Responsibility |
|-----------|----------|---------------|
| `ClaudeToolWindowPanel` | `toolwindow/` | Swing panel hosting the JCEF browser |
| `ClaudeBrowserManager` | `browser/` | JCEF lifecycle, theme/font injection, editor context sync |
| `ClaudeMessageRouter` | `browser/` | JS→Kotlin IPC dispatcher (CefMessageRouter) |
| `ClaudePlanPreviewPanel` | `browser/` | Standalone "Claude Plan" tool window; renders plan markdown in its own JCEF browser and bridges review comments |
| `HtmlTemplateProvider` | `browser/` | Generates HTML shell + `acquireVsCodeApi()` shim |
| `WebviewAssetProvider` | `browser/` | Extracts bundled `index.js`/`index.css` to temp dir |
| `ClaudeProcessManager` | `process/` | Spawns `claude` subprocess, routes stream-JSON messages |
| `ClaudeProcessConfig` | `process/` | Binary path resolution (handles `.cmd`/`.bat` on Windows) |
| `MessageProtocol` | `process/` | `@Serializable` data classes for all message types |
| `ClaudeSettings` | `settings/` | App-level persistent settings (`claude-code.xml`) |

### Tool Permission Bridging

Claude CLI sends `control_request {subtype:"can_use_tool"}` on stdin. The plugin:
1. Auto-approves non-interactive tools (Read, Write, Edit, Glob, etc.) in Kotlin
2. Translates interactive tools (Bash) to a `tool_permission_request` RPC call to the webview
3. Webview shows the permission dialog → user allows/denies → response goes back to Claude stdin as `control_response`

### IDE Context Sync

`ClaudeBrowserManager.setupEditorContextListener()` listens on `FileEditorManager` + `SelectionListener`. On tab switch or text selection change, it pushes active file path, language, line range, and selected text to the webview so Claude always sees the current editor state.

### Editor Actions (`actions/` package)

These bridge IDE gestures into the webview's input box via `ClaudeBrowserManager.insertAtMention()` (each first calls `notifyVisibility(true)` so the webview doesn't gate out the insertion):
- `OpenClaudeAction` / `NewConversationAction` — open/focus the tool window or start a fresh conversation (the `Ctrl+Escape` / `Ctrl+Shift+Escape` bindings).
- `ClaudeEditorActionGroup` — the editor right-click popup; builds `AskClaudeAction` entries.
- `AskClaudeAction(text, prefix)` — sends the current selection prefixed with an instruction; disabled when there's no selection.
- `SendFileAction` — inserts the current file's project-relative path as an `@`-mention; no selection needed.

### Plan Preview & Review

`ClaudePlanPreviewPanel` runs an independent JCEF browser in a separate "Claude Plan" tool window. `ClaudeMessageRouter` drives its lifecycle via RPC: `open_markdown_preview` (show), `close_plan_preview` (hide), `remove_plan_comment` (drop a comment by id). The panel renders plan markdown with a self-contained JS renderer; when comments are enabled, user-selected text + comment is sent back through a `JBCefJSQuery` and forwarded to the React webview via `ClaudeBrowserManager.sendPlanComment()`.

### Services and Lifecycle

- **`ClaudeSettings`** — app-level `PersistentStateComponent` (single instance across all projects)
- **`ClaudeProcessManager`** — project-level service; holds a `ConcurrentHashMap<channelId, Process>`. Cleaned up via `Disposable` on project close.

## Plugin Descriptor

`src/main/resources/META-INF/plugin.xml` is the authoritative source for:
- Registered extensions (tool window, settings, services, notification group)
- Action bindings (`Ctrl+Escape` = open, `Ctrl+Shift+Escape` = new conversation)
- Editor right-click action group (`ClaudeEditorActionGroup`)
- Minimum Rider version (2022.2+)

## Key Design Notes

- **Windows `.cmd` shim**: `ClaudeProcessConfig` detects npm-installed Claude CLI by checking for a `.cmd` wrapper and wrapping the command in `cmd /c` accordingly.
- **Theme injection**: IDE colors are read via `EditorColorsManager` and injected as CSS variables on every theme change event.
- **External links**: Clicks on `http://https://` URLs open the system browser via `Desktop.browse()` to avoid spawning CEF processes for external navigation.
- **Sessions**: Claude CLI stores sessions under `~/.claude/projects/`; the plugin does not manage session storage itself.
- **Stream-JSON protocol**: Both stdin and stdout of the `claude` subprocess use one JSON object per line, matching the Claude SDK stdio protocol.
