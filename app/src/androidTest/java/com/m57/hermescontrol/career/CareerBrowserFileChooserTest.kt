package com.m57.hermescontrol.career

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Process
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Intent/Uri/ClipData behavior; permission and owner checks have deterministic fixture results. */
@RunWith(AndroidJUnit4::class)
class CareerBrowserFileChooserTest {
    private val context = mockk<Context>()
    private val packages = mockk<PackageManager>()
    private val owner = mockk<WebView>()
    private val launches = mutableListOf<Intent>()
    private val pendingChanges = mutableListOf<Boolean>()
    private var currentDocument = 7L
    private lateinit var chooser: CareerBrowserFileChooser

    @Before
    fun prepare() {
        every { context.applicationContext } returns context
        every { context.packageManager } returns packages
        every { context.packageName } returns "com.m57.hermescontrol"
        every { packages.resolveContentProvider("fixture.documents", 0) } returns
            ProviderInfo().apply {
                packageName = "fixture.provider"
                applicationInfo = ApplicationInfo().apply { uid = Process.myUid() + 1 }
            }
        every { context.checkUriPermission(any(), any(), any(), Intent.FLAG_GRANT_READ_URI_PERMISSION) } returns
            PackageManager.PERMISSION_GRANTED
        chooser = newChooser()
    }

    @Test
    fun opensSystemDocumentPickerWithMimeFiltersAndNoCaptureOrPersistentGrant() {
        val callback = Callback()
        chooser.show(owner, 7, callback, Params(arrayOf(".jpg", ".pdf"), multiple = true, capture = true))
        val intent = launches.single()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals(setOf(Intent.CATEGORY_OPENABLE), intent.categories)
        assertEquals("*/*", intent.type)
        assertArrayEquals(arrayOf("image/jpeg", "application/pdf"), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        assertNull(intent.component)
        assertNull(intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(listOf(true), pendingChanges)
        assertTrue(callback.results.isEmpty())
    }

    @Test
    fun selectedSingleFileReachesOriginalCallbackExactlyOnceWithoutOpeningBytes() {
        val callback = Callback()
        val uri = document("one")
        chooser.show(owner, 7, callback, Params())
        chooser.onResult(Activity.RESULT_OK, result(uri))
        chooser.onResult(Activity.RESULT_OK, result(uri))
        chooser.cancel()
        chooser.close()
        assertEquals(1, callback.results.size)
        assertArrayEquals(arrayOf(uri), callback.results.single())
        assertFalse(chooser.isAwaitingResult)
        verify(exactly = 0) { context.contentResolver }
    }

    @Test
    fun multipleSelectionDeduplicatesDataAndClipUris() {
        val callback = Callback()
        val one = document("one")
        val two = document("two")
        val data = result(one)
        data.clipData = ClipData.newRawUri("fixture", one).apply { addItem(ClipData.Item(two)) }
        chooser.show(owner, 7, callback, Params(multiple = true))
        chooser.onResult(Activity.RESULT_OK, data)
        assertArrayEquals(arrayOf(one, two), callback.results.single())
    }

    @Test
    fun cancelNullDataAndMissingReadGrantEachCompleteOnceWithNull() {
        val results =
            listOf(
                Activity.RESULT_CANCELED to result(document("one")),
                Activity.RESULT_OK to null,
                Activity.RESULT_OK to Intent().setData(document("one")),
            )
        for ((code, data) in results) {
            val callback = Callback()
            chooser.show(owner, 7, callback, Params())
            chooser.onResult(code, data)
            assertEquals(1, callback.results.size)
            assertNull(callback.results.single())
        }
    }

    @Test
    fun changedDocumentAndExplicitCancellationNeverReceiveOldFiles() {
        val stale = Callback()
        chooser.show(owner, 7, stale, Params())
        currentDocument = 8
        chooser.onResult(Activity.RESULT_OK, result(document("one")))
        assertNull(stale.results.single())

        val cancelled = Callback()
        chooser.show(owner, 8, cancelled, Params())
        chooser.cancel(owner)
        chooser.onResult(Activity.RESULT_OK, result(document("two")))
        assertEquals(1, cancelled.results.size)
        assertNull(cancelled.results.single())
    }

    @Test
    fun unrelatedTabCancellationDoesNotCancelCurrentPicker() {
        val callback = Callback()
        chooser.show(owner, 7, callback, Params())
        chooser.cancel(mockk<WebView>())
        assertTrue(callback.results.isEmpty())
        chooser.onResult(Activity.RESULT_OK, result(document("one")))
        assertArrayEquals(arrayOf(document("one")), callback.results.single())
    }

    @Test
    fun repeatedRequestCancelsBothCallbacksAndDrainsOldResultBeforeRetry() {
        val old = Callback()
        val replacement = Callback()
        chooser.show(owner, 7, old, Params())
        chooser.show(owner, 7, replacement, Params())
        assertNull(old.results.single())
        assertNull(replacement.results.single())
        assertEquals(1, launches.size)
        assertTrue(chooser.isAwaitingResult)
        chooser.onResult(Activity.RESULT_OK, result(document("old")))
        assertEquals(1, old.results.size)
        assertEquals(1, replacement.results.size)

        val retry = Callback()
        chooser.show(owner, 7, retry, Params())
        chooser.onResult(Activity.RESULT_OK, result(document("new")))
        assertEquals(2, launches.size)
        assertArrayEquals(arrayOf(document("new")), retry.results.single())
    }

    @Test
    fun recreatedHelperDrainsSavedPendingResultWithoutGivingItToNewPage() {
        chooser = newChooser(initiallyAwaitingResult = true)
        val premature = Callback()
        chooser.show(owner, 7, premature, Params())
        assertNull(premature.results.single())
        assertTrue(launches.isEmpty())
        chooser.onResult(Activity.RESULT_OK, result(document("old activity")))
        val fresh = Callback()
        chooser.show(owner, 7, fresh, Params())
        chooser.onResult(Activity.RESULT_OK, result(document("fresh")))
        assertArrayEquals(arrayOf(document("fresh")), fresh.results.single())
    }

    @Test
    fun missingPickerAndClosedHelperCancelInsteadOfLeavingCallbackPending() {
        val failure = Callback()
        val unavailable =
            CareerBrowserFileChooser(context, { throw ActivityNotFoundException() }, { _, _ -> true })
        assertTrue(unavailable.show(owner, 7, failure, Params()))
        assertNull(failure.results.single())
        assertFalse(unavailable.isAwaitingResult)

        val closed = Callback()
        chooser.close()
        assertTrue(chooser.show(owner, 7, closed, Params()))
        assertNull(closed.results.single())
        assertTrue(launches.isEmpty())
    }

    @Test
    fun closeResolvesPendingCallbackOnlyOnceAndIgnoresLateResult() {
        val callback = Callback()
        chooser.show(owner, 7, callback, Params())
        chooser.close()
        chooser.close()
        chooser.onResult(Activity.RESULT_OK, result(document("one")))
        assertEquals(1, callback.results.size)
        assertNull(callback.results.single())
    }

    @Test
    fun unsupportedSaveAndFolderModesCannotLaunchOrReadAnything() {
        for (mode in listOf(WebChromeClient.FileChooserParams.MODE_SAVE, 2)) {
            val callback = Callback()
            chooser.show(owner, 7, callback, Params(requestedMode = mode))
            assertNull(callback.results.single())
        }
        assertTrue(launches.isEmpty())
    }

    @Test
    fun fileNetworkOwnProviderAndUnresolvedProviderUrisAreRejected() {
        every { packages.resolveContentProvider("com.m57.hermescontrol.files", 0) } returns
            ProviderInfo().apply {
                packageName = "com.m57.hermescontrol"
                applicationInfo = ApplicationInfo().apply { uid = Process.myUid() }
            }
        every { packages.resolveContentProvider("missing.provider", 0) } returns null
        val rejectedUris =
            listOf(
                "file:///data/user/0/com.m57.hermescontrol/private",
                "https://example.test/photo.jpg",
                "content://com.m57.hermescontrol.files/private",
                "content://missing.provider/document/one",
                "content://0@fixture.documents/document/one",
            )
        for (value in rejectedUris) {
            val callback = Callback()
            chooser.show(owner, 7, callback, Params())
            chooser.onResult(Activity.RESULT_OK, result(Uri.parse(value)))
            assertNull(callback.results.single())
        }
    }

    @Test
    fun sharedUidProviderAndRevokedReadGrantAreRejected() {
        every { packages.resolveContentProvider("same.uid", 0) } returns
            ProviderInfo().apply {
                packageName = "different.package"
                applicationInfo = ApplicationInfo().apply { uid = Process.myUid() }
            }
        val sharedUid = Callback()
        chooser.show(owner, 7, sharedUid, Params())
        chooser.onResult(Activity.RESULT_OK, result(Uri.parse("content://same.uid/private")))
        assertNull(sharedUid.results.single())

        every { context.checkUriPermission(any(), any(), any(), Intent.FLAG_GRANT_READ_URI_PERMISSION) } returns
            PackageManager.PERMISSION_DENIED
        val revoked = Callback()
        chooser.show(owner, 7, revoked, Params())
        chooser.onResult(Activity.RESULT_OK, result(document("one")))
        assertNull(revoked.results.single())
    }

    @Test
    fun singleFileInputRejectsMultipleFilesRatherThanChoosingForUser() {
        val callback = Callback()
        val data = result(document("one"))
        data.clipData = ClipData.newRawUri("fixture", document("two"))
        chooser.show(owner, 7, callback, Params())
        chooser.onResult(Activity.RESULT_OK, data)
        assertNull(callback.results.single())
    }

    @Test
    fun anyInvalidClipItemCancelsEntireSelection() {
        val callback = Callback()
        val data = result(document("one"))
        data.clipData = ClipData.newPlainText("not a file", "untrusted text")
        chooser.show(owner, 7, callback, Params(multiple = true))
        chooser.onResult(Activity.RESULT_OK, data)
        assertNull(callback.results.single())
    }

    private fun newChooser(initiallyAwaitingResult: Boolean = false) =
        CareerBrowserFileChooser(
            context = context,
            launch = { launches += it },
            isOwnerCurrent = { view, document -> view === owner && document == currentDocument },
            initiallyAwaitingResult = initiallyAwaitingResult,
            onAwaitingResultChanged = { pendingChanges += it },
        )

    private fun document(id: String): Uri = Uri.parse("content://fixture.documents/document/$id")

    private fun result(uri: Uri): Intent = Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private class Callback : ValueCallback<Array<Uri>> {
        val results = mutableListOf<Array<Uri>?>()

        override fun onReceiveValue(value: Array<Uri>?) {
            results += value
        }
    }

    private class Params(
        private val accept: Array<String> = emptyArray(),
        private val multiple: Boolean = false,
        private val capture: Boolean = false,
        private val requestedMode: Int? = null,
    ) : WebChromeClient.FileChooserParams() {
        override fun getMode(): Int = requestedMode ?: if (multiple) MODE_OPEN_MULTIPLE else MODE_OPEN

        override fun getAcceptTypes(): Array<String> = accept

        override fun isCaptureEnabled(): Boolean = capture

        override fun getTitle(): CharSequence? = null

        override fun getFilenameHint(): String? = null

        override fun createIntent(): Intent = throw AssertionError("The helper must build its own upload-only intent")
    }
}
