package com.example.notionreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import com.example.notionreader.application.ContinueListeningCard
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The compact playback surface the app shows while a resumable session exists.
 *
 * ## It reflects the session; it is not a second session
 *
 * The contract is emphatic: "The mini-player reflects the existing Player session. It is not a second
 * playback engine or second playback state." That is why this composable takes a
 * [ContinueListeningCard] — a *description* of the session — plus two callbacks, and holds no state of
 * its own. It cannot start playback, because starting playback is not one of the things it is given.
 *
 * The same shape is used by Home's Continue listening section, deliberately: the contract says the
 * mini-player "represents the existing session elsewhere in the app", and having one value that both
 * surfaces describe means they cannot drift into describing it differently.
 *
 * ## Deliberately compact
 *
 * The contract lists what it must *not* expose: "queue editing; detailed section navigation; filtering;
 * large groups of playback controls." So this is a title, a state, one Play/Pause control, and a tap
 * target that opens the Player. Nothing else, even though the Player has more controls available.
 *
 * ## Accessibility
 *
 * "Logically grouped accessible region" — the title and state merge into one focus stop, so a swipe lands
 * on the session rather than on each of its parts. The contract gives the useful announcement as
 * "Current Note title. Playing. Open Player.", which is exactly what the merged node plus the click
 * action produces; and it says the mini-player "should not read the complete queue state every time
 * focus reaches it", which is why the queue position is the short "Note 3 of 8" rather than a list.
 *
 * The region is marked `isTraversalGroup`, which is what "logically grouped" means in Compose: traversal
 * does not leave the region until it has visited everything in it. That is the right behaviour on its own,
 * and it has a second use — the instrumented suite scopes its *focus-stop count* to this node, so "is the
 * mini-player two focus stops rather than one or six?" (the check `docs/decisions.md` §12.4 named) is asked
 * about this surface instead of about the whole screen. A count taken over the screen would have to be
 * updated every time anything else on the screen changed.
 *
 * Play/Pause is a separate, explicit control rather than part of that group, because the contract
 * requires it to "remain an explicit accessible control" — merging it in would make the whole region one
 * button and remove the separate way to pause.
 */
@Composable
fun MiniPlayer(
    session: ContinueListeningCard,
    onOpenPlayer: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.material3.Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { isTraversalGroup = true },
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onOpenPlayer)
                        .semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = session.title,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    val detail = listOfNotNull(session.queuePosition, session.stateDescription)
                        .joinToString(" · ")
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                TextButton(
                    onClick = onPlayPause,
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Text(if (session.isPlaying) "Pause" else "Play")
                }
            }
        }
    }
}
