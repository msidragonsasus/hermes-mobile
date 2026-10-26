package com.m57.hermescontrol.career

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class CareerGmailSyncTest {
    private val gmail = mockk<CareerGmailClient>()
    private val local = mockk<CareerLocalStore>()
    private val repository = mockk<CareerCompanyRepository>()
    private val auth = CareerGmailAuthorizationResult.Authorized("synthetic-token", "jobs@example.test")
    private val sender = CareerMailSenderRule("rule", 1, "a", "sender@example.test")
    private val checkpoints = mutableMapOf<String, CareerGmailCheckpoint>()
    private val stored = mutableSetOf<String>()
    private val sync get() = CareerGmailSync(gmail, local, repository)

    @Before
    fun setup() {
        val detail = mockk<CareerCompanyDetail>()
        io.mockk.every { detail.mail_senders } returns
            listOf(sender, sender.copy(company_id = "b", address = "other@example.test"))
        coEvery { repository.company("a") } returns detail
        coEvery { local.gmailCheckpoint(any()) } coAnswers { checkpoints[firstArg()] ?: CareerGmailCheckpoint() }
        coEvery { local.saveGmailCheckpoint(any(), any()) } coAnswers {
            checkpoints[firstArg()] = secondArg()
            Unit
        }
        coEvery { gmail.verifyAccount(any(), any()) } returns Unit
        coEvery { gmail.listPage(any(), listOf(sender.address), any()) } coAnswers {
            val start = thirdArg<String?>()?.toInt() ?: 0
            CareerGmailIdPage(
                (start until minOf(start + 25, 125)).map { it.toString(16) },
                (start + 25).takeIf { it < 125 }?.toString(),
            )
        }
        coEvery { repository.cachedMail(auth.accountEmail, "a", any()) } coAnswers {
            thirdArg<List<String>>().filter { it in stored }.toSet()
        }
        coEvery { gmail.readMessage(any(), any(), setOf(sender.address)) } coAnswers { message(secondArg()) }
        coEvery { repository.importClientMail(auth.accountEmail, any(), "a") } coAnswers {
            stored.addAll(secondArg<List<CareerClientMailMessage>>().map { it.id })
            JsonObject(emptyMap())
        }
    }

    @Test
    fun companyOnlyHundredThenContinuesBeyondNinetyDays() =
        runBlocking {
            val progress = mutableListOf<Int>()
            assertTrue(sync.collect(auth, "a", false) { count, _ -> progress.add(count) })
            assertEquals(100, stored.size)
            assertEquals(100, progress.last())
            assertEquals("100", checkpoints.values.single().pageToken)
            assertFalse(sync.collect(auth, "a", false) { _, _ -> })
            assertEquals(125, stored.size)
            coVerify(exactly = 0) { repository.connections() }
            coVerify(exactly = 5) { gmail.listPage(any(), listOf(sender.address), any()) }
            coVerify(exactly = 0) { repository.company("b") }
        }

    @Test
    fun failedChunkResumesAfterLastAcknowledgedChunk() =
        runBlocking {
            coEvery { gmail.readMessage(any(), "a", any()) } throws IOException("offline")
            assertTrue(runCatching { sync.collect(auth, "a", false) { _, _ -> } }.isFailure)
            assertEquals(10, stored.size)
            assertEquals(
                "a",
                checkpoints.values
                    .single()
                    .pendingIds
                    .first(),
            )
            coEvery { gmail.readMessage(any(), "a", any()) } returns message("a")
            sync.collect(auth, "a", false) { _, _ -> }
            assertEquals(110, stored.size)
            coVerify(exactly = 1) { gmail.readMessage(any(), "0", any()) }
        }

    @Test
    fun uncertainAckUsesServerCacheWithoutDownloadingBodyAgain() =
        runBlocking {
            var uncertain = true
            coEvery { repository.importClientMail(auth.accountEmail, any(), "a") } coAnswers {
                stored.addAll(secondArg<List<CareerClientMailMessage>>().map { it.id })
                if (uncertain && secondArg<List<CareerClientMailMessage>>().isNotEmpty()) {
                    uncertain = false
                    throw IOException("ack lost")
                }
                JsonObject(emptyMap())
            }
            assertTrue(runCatching { sync.collect(auth, "a", false) { _, _ -> } }.isFailure)
            assertEquals(
                25,
                checkpoints.values
                    .single()
                    .pendingIds.size,
            )
            sync.collect(auth, "a", false) { _, _ -> }
            assertEquals(100, stored.size)
            coVerify(exactly = 1) { gmail.readMessage(any(), "0", any()) }
        }

    @Test
    fun refreshReusesSavedBodiesAndKeysIsolateAccountCompanyAndRules() =
        runBlocking {
            stored.addAll((0 until 125).map { it.toString(16) })
            sync.collect(auth, "a", true) { _, _ -> }
            coVerify(exactly = 0) { gmail.readMessage(any(), any(), any()) }
            val key = CareerGmailSync.checkpointKey("a", "jobs@example.test", listOf(sender.address))
            assertNotEquals(key, CareerGmailSync.checkpointKey("b", auth.accountEmail, listOf(sender.address)))
            assertNotEquals(key, CareerGmailSync.checkpointKey("a", "other@example.test", listOf(sender.address)))
            assertNotEquals(key, CareerGmailSync.checkpointKey("a", auth.accountEmail, listOf("new@example.test")))
        }

    @Test
    fun removedMessageDoesNotBlockFollowingMessages() =
        runBlocking {
            coEvery { gmail.readMessage(any(), "a", any()) } throws CareerGmailHttpException(404, "deleted")
            sync.collect(auth, "a", false) { _, _ -> }
            assertEquals(99, stored.size)
            assertFalse("a" in stored)
            assertTrue("b" in stored)
        }

    @Test
    fun cancellationKeepsAcknowledgedProgress() =
        runBlocking {
            coEvery { gmail.readMessage(any(), "a", any()) } throws kotlinx.coroutines.CancellationException()
            val error = runCatching { sync.collect(auth, "a", false) { _, _ -> } }.exceptionOrNull()
            assertTrue(error is kotlinx.coroutines.CancellationException)
            assertEquals(10, stored.size)
            assertEquals(
                "a",
                checkpoints.values
                    .single()
                    .pendingIds
                    .first(),
            )
        }

    private fun message(id: String) =
        CareerClientMailMessage(
            id,
            id,
            sender.address,
            sender.address,
            "件名",
            "案内",
            "本文",
            "https://mail.google.com/mail/#all/$id",
            "2020-01-01T00:00:00Z",
            false,
        )
}
