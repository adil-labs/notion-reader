package com.example.notionreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.notionreader.domain.queue.SavedQueueDraft

/**
 * `Create saved queue`: one field, and a control that is disabled until the name is usable.
 *
 * ## The rule is the model's, not the screen's
 *
 * The contract's required input is "initially only: Queue name", and its one rule is that a name is
 * required. That rule is **not** written here. [SavedQueueDraft] owns it — trimming and blankness — and this
 * screen reads `isCompletable` to decide whether the control is available. The alternative, a screen
 * checking `name.isNotBlank()` for itself, is a second definition of "a usable name" that would disagree
 * with the model's the first time either changed: the draft trims and the screen might not, so `"  "` would
 * be accepted here and refused there, or the reverse.
 *
 * ## Why the control is disabled rather than inert
 *
 * The same rule the Player's controls and Edit mode's moves follow: a listener is told what is unavailable
 * rather than pressing something that does nothing. And because the *field* is the only thing to fill in,
 * the disabled state is also the instruction — with `Create` unavailable and a name required, there is
 * nothing else the listener could think is missing.
 *
 * ## Why state is held here rather than in the application layer
 *
 * A half-typed name is not a fact about the reader, and nothing outside this screen can observe it: the
 * queue does not exist until the control is pressed, which is exactly the contract's "an abandoned Create
 * screen creates nothing". `rememberSaveable` is what keeps the typing across the configuration change that
 * would otherwise clear it.
 *
 * ## What happens after the control is pressed is the caller's business
 *
 * This screen reports a name and nothing else. Whether the queue was created — and therefore whether the
 * listener leaves this screen — is decided by whoever handles [onCreate], because it depends on an outcome
 * this composable cannot see. It is not given a "did it work" flag to render: a failure here is either a
 * rejected name, which the disabled control already prevents, or a storage failure, which the specification
 * requires to be invisible.
 */
@Composable
fun CreateSavedQueueScreen(
    onCreate: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Injected so that a test can type without a clock or an IME. Never read by this composable. */
    initialName: String = ""
) {
    // Keyed by nothing: the field's value is the only state, and `rememberSaveable` restores it from the
    // saved-instance-state bundle after a configuration change or process restart.
    var name by rememberSaveable { mutableStateOf(initialName) }
    val draft = SavedQueueDraft(name)

    Column(
        modifier = modifier
            .fillMaxSize()
            .semantics { paneTitle = "Create saved queue" }
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Create saved queue",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() }
        )

        Text(
            text = "Give the queue a name. You can add Notes to it afterwards, and rename it later.",
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Queue name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // `isCompletable` and not a local check: the field is trimmed by the model, so a name of spaces is
        // unusable and the control says so.
        Button(
            onClick = { onCreate(name) },
            enabled = draft.isCompletable
        ) {
            Text("Create")
        }
    }
}
