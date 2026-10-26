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
    @Test fun optionalNamingDownloadCancelDeleteAndNetworkPolicyAreSeparateExplicitActions() {
        var model by mutableStateOf(DiscoveryModelState(description = "Gemma topic model", totalBytes = 2588147712))
        var downloads = 0; var cancels = 0; var deletes = 0; var wifi = true
        compose.setContent { MaterialTheme {
            DiscoverySettingsScreen(DiscoverySettingsUi("Embedding model", topicModel = model),
                {}, {}, {}, {}, {}, {}, {}, {}, {},
                onTopicModelDownload = { downloads++; model = model.copy(downloading = true, downloadedBytes = 1) },
                onTopicModelCancel = { cancels++; model = model.copy(downloading = false, installed = true, storageBytes = 17) },
                onTopicModelDelete = { deletes++ }, onTopicModelWifiOnlyChange = { wifi = it })
        } }
        compose.runOnIdle { assertEquals(0, downloads) }
        compose.onNodeWithContentDescription("Topic model download using Wi-Fi only").performScrollTo()
            .assertIsReachable().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertFalse(wifi) }
        compose.onNodeWithText("Download topic naming model").performScrollTo().assertIsReachable()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Cancel topic model download").performScrollTo().assertIsReachable()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Delete topic naming model").performScrollTo().assertIsReachable()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(1, downloads); assertEquals(1, cancels); assertEquals(1, deletes) }
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
