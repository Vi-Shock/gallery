/*
 * LitBud — Offline AI Reading Tutor for Children
 * Apache 2.0 License (same as Google AI Edge Gallery fork)
 */

package com.google.ai.edge.gallery.customtasks.litbud

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

const val LITBUD_TASK_ID = "litbud_reading"
private const val SYSTEM_PROMPT_ASSET = "prompts/tutor_system.txt"

/**
 * LitBud reading tutor custom task.
 *
 * Implements the full reading flow as a single Gallery CustomTask:
 *   1. Page Capture: camera → Gemma 4 vision → extract book text (Feature 1)
 *   2. Read Aloud: child speaks → audio → fuzzy matching (Feature 2, Phase 2)
 *   3. Coaching: Gemma 4 generates warm phonics feedback (Feature 3, Phase 2)
 *   4. Function calling: track_progress, get_hint, adjust_difficulty, log_session (Feature 4)
 *   5. Progress Dashboard (Feature 5)
 */
class LitBudTask @Inject constructor() : CustomTask {

    override val task: Task = Task(
        id = LITBUD_TASK_ID,
        label = "LitBud Reading Tutor",
        category = Category.LLM,
        icon = Icons.AutoMirrored.Outlined.MenuBook,
        description = "Point the camera at a book page, read aloud, and get warm coaching — 100% offline. For children ages 5–12.",
        shortDescription = "Offline AI reading tutor",
        models = mutableListOf(),
        modelNames = listOf("Gemma-4-E2B-it"),
    )

    override fun initializeModelFn(
        context: Context,
        coroutineScope: CoroutineScope,
        model: Model,
        onDone: (String) -> Unit,
    ) {
        val systemPrompt = try {
            context.assets.open(SYSTEM_PROMPT_ASSET).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }

        model.runtimeHelper.initialize(
            context = context,
            model = model,
            supportImage = true,
            supportAudio = true,
            onDone = onDone,
            systemInstruction = if (systemPrompt.isNotEmpty()) {
                Contents.of(Content.Text(systemPrompt))
            } else {
                null
            },
            coroutineScope = coroutineScope,
        )
    }

    override fun cleanUpModelFn(
        context: Context,
        coroutineScope: CoroutineScope,
        model: Model,
        onDone: () -> Unit,
    ) {
        model.runtimeHelper.cleanUp(model = model, onDone = onDone)
    }

    @Composable
    override fun MainScreen(data: Any) {
        val customTaskData = data as CustomTaskData
        LitBudScreen(
            modelManagerViewModel = customTaskData.modelManagerViewModel,
            bottomPadding = customTaskData.bottomPadding,
            setAppBarControlsDisabled = customTaskData.setAppBarControlsDisabled,
            setTopBarVisible = customTaskData.setTopBarVisible,
        )
    }
}
