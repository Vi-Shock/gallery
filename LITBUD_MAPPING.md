# Gallery → LitBud Codebase Mapping

Reference for Phase 2 (Core MVP). Maps Gallery's architecture to LitBud's planned customizations.

---

## Architecture Summary

Gallery is a **Jetpack Compose app** (not Activities-based). Key facts:
- All screens are `@Composable` functions, not Android Activities
- Navigation via `GalleryNavGraph.kt` using the NavHost/composable pattern
- Custom tasks (like LitBud) use the `CustomTask` interface — implement it, register with Hilt `@IntoSet`, and the home screen auto-discovers it
- System prompt is a `String` field on the `Task` data class (`defaultSystemPrompt`)
- Function calling uses `@Tool` / `@ToolParam` annotations from LiteRT-LM SDK (see `AgentTools.kt`)
  - BUT for LitBud: per CLAUDE.md, parse tool JSON from model text response using `org.json.JSONObject`

---

## Gallery Screens → LitBud Screens

| Gallery component | Gallery ID / file | LitBud purpose | What changes |
|---|---|---|---|
| `HomeScreen.kt` | `ui/home/HomeScreen.kt` | LitBud home | App name "LitBud", tagline, child-friendly colors, 2 buttons |
| Ask Image (single-turn) | `BuiltInTaskId.LLM_ASK_IMAGE` → `LlmSingleTurnScreen` | Page Capture (OCR) | OCR prompt, camera UI, child-friendly |
| Audio Scribe (single-turn) | `BuiltInTaskId.LLM_ASK_AUDIO` → `LlmSingleTurnScreen` | Read Aloud screen | 30s recording UI, pass audio + page text |
| AI Chat | `BuiltInTaskId.LLM_CHAT` → `LlmChatScreen` | Coaching Response | Speech bubble UI, LitBud system prompt |
| Agent Skills | `customtasks/agentchat/` → `AgentChatScreen` | Tool execution (4 tools) | LitBud tools: track_progress, get_hint, adjust_difficulty, log_session |

**Best path for LitBud:** Create a single new `customtasks/litbud/` package that implements the full reading flow as one custom task (Page Capture → Read Aloud → Coaching) rather than patching 4 separate tasks. This is cleaner and avoids modifying Gallery's built-in tasks.

---

## Key Files for Phase 2

### System prompt
Set on `Task.defaultSystemPrompt` (data class field in `data/Tasks.kt`)
→ LitBud: load from `assets/prompts/tutor_system.txt` at task initialization

### Task registration
`customtasks/examplecustomtask/ExampleCustomTaskModule.kt` — Hilt `@Module` + `@IntoSet`
→ LitBud: create `customtasks/litbud/LitBudTaskModule.kt` following the same pattern

### Function calling
`customtasks/agentchat/AgentTools.kt` — uses `@Tool` / `@ToolParam` SDK annotations
→ LitBud: parse JSON from model text with `org.json.JSONObject` (per CLAUDE.md constraint)
   The 4 tools: `track_progress`, `get_hint`, `adjust_difficulty`, `log_session`

### Config keys
`data/Config.kt` — `ConfigKeys.TEMPERATURE`, `ConfigKeys.ENABLE_THINKING`, etc.
→ LitBud: use `defaultSystemPrompt` on Task; no need for per-session config UI

### Navigation
`ui/navigation/GalleryNavGraph.kt`
→ Do NOT modify. Add LitBud as a `CustomTask` and it auto-appears in nav.

### Model allowlist
`model_allowlist.json` at repo root
→ Add Gemma 4 E2B entry for LitBud (Phase 2, Feature 1)

---

## Files to NEVER Touch

Per CLAUDE.md:
- `engine/` — LiteRT-LM inference engine
- `litert/` — LiteRT runtime
- `inference/` — inference pipeline

These directories are Google's. Read-only.

---

## Phase 2 New Files (plan)

```
android/Android/src/app/src/main/java/com/google/ai/edge/gallery/
  customtasks/litbud/
    LitBudTask.kt           — Task definition with system prompt
    LitBudTaskModule.kt     — Hilt @Module registration
    LitBudScreen.kt         — Full reading flow UI (camera → record → coaching)
    LitBudViewModel.kt      — State management + model calls
    FuzzyMatcher.kt         — Levenshtein word comparison (thresholds: 85/60)
    ToolCallHandler.kt      — Parse tool JSON from model response text
    LitBudDatabase.kt       — Room DB: progress + sessions tables
    LitBudDao.kt            — Room DAOs with Kotlin Flow
    ProgressDashboard.kt    — Accuracy-over-time chart screen

android/Android/src/app/src/main/assets/
  prompts/
    tutor_system.txt        — Copy from ollama/prompts/tutor_system.txt

android/Android/src/app/src/main/res/
  font/
    nunito.ttf              — Nunito font (Google Fonts, OFL license)
  values/
    colors_litbud.xml       — LitBud brand colors
```
