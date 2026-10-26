package com.m57.hermescontrol.home

import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.ScopedWsEvent
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class HomeRepositoryTest {
    @Test
    fun completionUsesSavedDestinationRejectsOldConnectionAndCoalescesUnreadReplies() =
        runBlocking {
            val destination = "chat-home:${UUID.randomUUID()}"
            val connection = ConnectionScope("server", "connection", "profile", "process", 1)
            val events = MutableSharedFlow<ScopedWsEvent>()
            val scope = CoroutineScope(Dispatchers.Default)
            mockkObject(AuthManager, HermesWsClient)
            every { AuthManager.uiPreferenceScope() } returns destination
            every { HermesWsClient.scopedEvents } returns events
            every { HermesWsClient.matchesCurrentScope(any()) } answers { firstArg<ConnectionScope>() == connection }
            val repository = HomeRepository(InstrumentationRegistry.getInstrumentation().targetContext)
            try {
                repository.start(scope)
                withTimeout(5_000) { events.subscriptionCount.first { it > 0 } }
                events.emit(ScopedWsEvent(connection, 1, WsEvent.MessageComplete("unknown", "runtime")))
                events.emit(
                    ScopedWsEvent(
                        connection.copy(generation = 0),
                        2,
                        WsEvent.MessageComplete("old", "runtime", storedSessionId = "old"),
                    ),
                )
                events.emit(
                    ScopedWsEvent(
                        connection,
                        3,
                        WsEvent.MessageComplete("first", "runtime", storedSessionId = "saved"),
                    ),
                )
                withTimeout(5_000) {
                    while (repository.dao.item(destination, "chat", "saved") == null) delay(20)
                }
                val first =
                    repository.json.decodeFromString<HomeItem>(
                        repository.dao.item(destination, "chat", "saved")!!.payload,
                    )
                repository.markRead(destination, first)
                events.emit(
                    ScopedWsEvent(
                        connection,
                        4,
                        WsEvent.MessageComplete("second", "runtime", storedSessionId = "saved"),
                    ),
                )
                val rows =
                    withTimeout(5_000) {
                        repository.dao.observe(destination).first {
                            it.any { row ->
                                repository.json.decodeFromString<HomeItem>(row.payload).version ==
                                    homeVersion("second")
                            }
                        }
                    }
                assertEquals(1, rows.size)
                assertEquals("saved", rows.single().id)
                val reads =
                    repository.dao
                        .reads(destination)
                        .first()
                        .associate { it.key to it.version }
                assertEquals(
                    1,
                    homeItems(rows.map { repository.json.decodeFromString<HomeItem>(it.payload) }, reads).size,
                )
                // New repository instances use the same encrypted persisted feed, without replaying an event.
                val reopened = HomeRepository(InstrumentationRegistry.getInstrumentation().targetContext)
                assertEquals(rows, reopened.dao.observe(destination).first())
                assertTrue(
                    reopened.dao
                        .observe("$destination/another-profile")
                        .first()
                        .isEmpty(),
                )
            } finally {
                scope.cancel()
                unmockkObject(AuthManager, HermesWsClient)
            }
        }
}
