package com.example.notionreader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.example.notionreader.domain.discovery.DiscoveryModelState
import com.example.notionreader.ui.settings.*
import com.example.notionreader.ui.A11y.assertIsReachable
import com.example.notionreader.ui.A11y.assertIsReadable
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TopicModelSettingsAccessibilityTest {
    @get:Rule val compose = createComposeRule()
    @Test fun discoveryExplainsAnActiveTopicModelDownload() {
        val state = com.example.notionreader.application.DiscoveryState(
            cacheStatus = com.example.notionreader.application.DiscoveryCacheStatus.Ready,
            cachedPageIds = setOf("a", "b"), indexedCount = 2, paused = false, workPending = true)
        val model = DiscoveryModelState(downloading = true, downloadedBytes = 50, totalBytes = 100)
        compose.setContent { MaterialTheme { TopicProgressNotice(withTopicModelStatus(state, model)) {} } }
        compose.onNodeWithText("Downloading the topic model. New topics will be found after it is ready.").assertIsReadable()
        compose.onNodeWithText("Finding topics and connections…").assertDoesNotExist()
    }
    @Test fun aPartialTopicModelExposesSavedProgressAndAResumeAction() {
        var resumes = 0
        compose.setContent { MaterialTheme {
            DiscoverySettingsScreen(DiscoverySettingsUi("Embedding model", topicModel =
                DiscoveryModelState(description = "Topic model", totalBytes = 1024 * 1024, downloadedBytes = 512 * 1024)),
                {}, {}, {}, {}, {}, {}, {}, {}, {}, onTopicModelDownload = { resumes++ })
        } }
        compose.onNodeWithText("Saved: 512.0 KiB of 1.0 MiB").performScrollTo().assertIsReadable()
        compose.onNodeWithText("Resume topic model download").performScrollTo().assertIsReachable()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(1, resumes) }
    }
    @Test fun topicModelDownloadPauseResumeDeleteAndNetworkPolicyAreSeparateExplicitActions() {
        var model by mutableStateOf(DiscoveryModelState(description = "Gemma topic model", totalBytes = 2588147712))
        var downloads = 0; var cancels = 0; var deletes = 0; var wifi = true
        compose.setContent { MaterialTheme {
            DiscoverySettingsScreen(DiscoverySettingsUi("Embedding model", topicModel = model),
                {}, {}, {}, {}, {}, {}, {}, {}, {},
                onTopicModelDownload = { downloads++; model = if (downloads == 1) model.copy(downloading = true, downloadedBytes = 1) else model.copy(installed = true, storageBytes = 17) },
                onTopicModelCancel = { cancels++; model = model.copy(downloading = false) },
                onTopicModelDelete = { deletes++ }, onTopicModelWifiOnlyChange = { wifi = it })
        } }
        compose.runOnIdle { assertEquals(0, downloads) }
        compose.onNodeWithContentDescription("Topic model download using Wi-Fi only").performScrollTo()
            .assertIsReachable().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertFalse(wifi) }
        compose.onNodeWithText("Download topic discovery model").performScrollTo().assertIsReachable()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Pause topic model download").performScrollTo().assertIsReachable()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Saved: 1 bytes of 2.4 GiB").performScrollTo().assertIsReadable()
        compose.onNodeWithText("Resume topic model download").performScrollTo().assertIsReachable()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Delete topic discovery model").performScrollTo().assertIsReachable()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(2, downloads); assertEquals(1, cancels); assertEquals(1, deletes) }
    }
    @Test fun unavailableNamingDoesNotHideTheFailureOrClaimThatGenerationIsEnabled() {
        compose.setContent { MaterialTheme {
            DiscoverySettingsScreen(DiscoverySettingsUi("Embedding model",
                topicModel = DiscoveryModelState(description = "Gemma topic model", message = "Model could not be verified.", storageBytes = 0),
                topicNamingActivationMessage = "Topic naming is under validation; generation is not enabled."),
                {}, {}, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Topic naming is under validation; generation is not enabled.").performScrollTo().assertIsReadable()
        compose.onNodeWithText("Model could not be verified.").performScrollTo().assertIsReadable()
        compose.onNodeWithText("Topic model files: 0 bytes").performScrollTo().assertIsReadable()
    }
}
