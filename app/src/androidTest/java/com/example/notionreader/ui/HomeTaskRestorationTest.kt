package com.example.notionreader.ui

import androidx.test.core.app.ActivityScenario
import com.example.notionreader.MainActivity
import com.example.notionreader.ReaderApplication
import com.example.notionreader.application.ReaderDestination
import org.junit.Assert.*
import org.junit.Test

class HomeTaskRestorationTest {
    @Test fun newTasksStartAtHomeAndRecreationKeepsDiscoverWithoutReplacingTheSession() {
        var session: Any? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val app = (activity.application as ReaderApplication).host.app
                app.openDiscover()
                session = app.session
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val app = (activity.application as ReaderApplication).host.app
                assertEquals(ReaderDestination.Discover, app.navigator.state.value.current)
                assertSame(session, app.session)
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val app = (activity.application as ReaderApplication).host.app
                assertEquals(ReaderDestination.Home, app.navigator.state.value.current)
                assertSame(session, app.session)
            }
        }
    }
}
