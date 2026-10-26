package com.m57.hermescontrol.miniapp.workbench

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WorkbenchContentTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<String>()
    private val snapshot =
        WorkbenchSnapshot(
            "wb-" + "a".repeat(48),
            null,
            1,
            true,
            "Synthetic",
            workbenchFiles.associateWith {
                "synthetic source"
            },
            "synthetic diff",
            false,
            true,
        )

    private fun show(state: WorkbenchUiState) {
        compose.setContent {
            MaterialTheme {
                WorkbenchContent("synthetic-company", state, { calls += "read" }, { action, _ ->
                    calls +=
                        action
                }, { calls += "fence" })
            }
        }
    }

    @Test fun openingDoesNotWriteAndApplyRequiresReviewConfirmation() {
        show(WorkbenchUiState(snapshot = snapshot))
        assertEquals(emptyList<String>(), calls)
        compose
            .onNodeWithTag("workbench_apply")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(emptyList<String>(), calls)
        compose.onNodeWithTag("workbench_confirm").performClick()
        assertEquals(listOf("apply"), calls)
    }

    @Test fun untestedAndTruncatedChangesCannotApply() {
        show(WorkbenchUiState(snapshot = snapshot.copy(tested = false, diffTruncated = true)))
        compose.onNodeWithTag("workbench_apply").performScrollTo().assertIsNotEnabled()
    }

    @Test fun unknownReceiptOffersRecoveryAndFenceWhileNewWritesAreDisabled() {
        val pending = WorkbenchPending("synthetic-operation-0001", "b".repeat(64), "apply", snapshot.active, 1)
        show(WorkbenchUiState(snapshot = snapshot, pending = pending))
        compose.onNodeWithTag("workbench_test").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("workbench_refresh").performScrollTo().performClick()
        compose.onNodeWithTag("workbench_fence").performScrollTo().performClick()
        assertEquals(listOf("read", "fence"), calls)
    }

    @Test fun unsavedEditorDisablesTestAndApplyUntilExplicitSave() {
        show(WorkbenchUiState(snapshot = snapshot))
        compose.onNodeWithTag("workbench_editor").performScrollTo().performTextReplacement("edited synthetic source")
        compose.onNodeWithTag("workbench_test").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("workbench_apply").performScrollTo().assertIsNotEnabled()
        compose
            .onNodeWithTag("workbench_save")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(listOf("edit"), calls)
    }

    @Test fun realPreferencesSurviveReconstructionAndRemainScopeBound() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = "synthetic-workbench-test-${java.util.UUID.randomUUID()}"
        val pending = WorkbenchPending("synthetic-operation-0001", "b".repeat(64), "test", snapshot.active, 1)
        val original = PreferencesWorkbenchPendingStore.forScope(context, scope, "one")
        assertNull(original.read())
        assertTrue(original.commit(pending))
        val reconstructed = PreferencesWorkbenchPendingStore.forScope(context, scope, "one")
        assertEquals(pending, reconstructed.read())
        assertNull(PreferencesWorkbenchPendingStore.forScope(context, "$scope-other", "one").read())
        assertNull(PreferencesWorkbenchPendingStore.forScope(context, scope, "two").read())
        assertFalse(reconstructed.commit(pending.copy(operationId = "synthetic-operation-0002")))
        assertFalse(reconstructed.clear(pending.copy(operationId = "synthetic-operation-0002")))
        assertTrue(reconstructed.clear(pending))
        assertNull(original.read())
    }

    @Test fun naturalRequestUsesTheWrittenGoalWithoutARequiredModeSelection() {
        show(WorkbenchUiState(snapshot = snapshot.copy(draft = false), editor = EditorSnapshot(false, false, null)))
        compose.onNodeWithTag("workbench_goal").performScrollTo().performTextInput("ログイン待ちの分岐を直して")
        compose
            .onNodeWithTag("workbench_propose")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(listOf("propose"), calls)
    }

    @Test fun runningEditorBlocksAnotherRequestAndOffersCancellation() {
        val job = EditorJob("synthetic-editor-job-0001", "running", snapshot.active, null, null, null, null, "")
        show(WorkbenchUiState(snapshot = snapshot.copy(draft = false), editor = EditorSnapshot(true, true, job)))
        compose.onNodeWithTag("workbench_propose").performScrollTo().assertIsNotEnabled()
        compose
            .onNodeWithTag("workbench_cancel_editor")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(listOf("cancel_editor"), calls)
    }
}
