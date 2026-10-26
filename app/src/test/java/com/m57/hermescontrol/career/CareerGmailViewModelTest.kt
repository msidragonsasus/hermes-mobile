package com.m57.hermescontrol.career

import android.app.Activity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CareerGmailViewModelTest {
    @Test
    fun connectionOnlyNeverFetchesMailAndDoubleTapIsIgnored() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val auth = mockk<CareerGmailAuthManager>()
                val gmail = mockk<CareerGmailClient>()
                val repository = mockk<CareerCompanyRepository>()
                val activity = mockk<Activity>()
                coEvery { auth.connectedEmail() } returns null
                coEvery { auth.beginAuthorization(activity) } returns
                    CareerGmailAuthorizationResult.Authorized("test", "jobs@example.test")
                val model = CareerGmailViewModel(auth, gmail, repository, mockk())
                advanceUntilIdle()
                model.collect(activity, {})
                model.collect(activity, {})
                advanceUntilIdle()
                assertEquals("jobs@example.test", model.state.value.accountEmail)
                assertFalse(model.state.value.loading)
                coVerify(exactly = 1) { auth.beginAuthorization(activity) }
                coVerify(exactly = 0) { repository.connections() }
                coVerify(exactly = 0) { repository.company(any()) }
                coVerify(exactly = 0) { gmail.verifyAccount(any(), any()) }
                model.stop()
                model.completeAuthorization(activity, null)
                advanceUntilIdle()
                coVerify(exactly = 0) { auth.completeAuthorization(any(), any()) }
            } finally {
                Dispatchers.resetMain()
            }
        }
}
