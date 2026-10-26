package com.m57.hermescontrol.career

import org.junit.Assert.assertEquals
import org.junit.Test

class CareerBrowserDownloadTest {
    private val policy = checkNotNull(CareerBrowserPolicy.create("https://recruit.disco.co.jp/mypage/login"))

    @Test
    fun httpsFilesOnAnyPublicHostAreSaved() {
        listOf(
            "https://recruit.disco.co.jp/files/entry_sheet.pdf",
            "https://cdn.example.test/template.docx?download=1",
        ).forEach {
            assertEquals(it, CareerBrowserDownloadDecision.SAVE, careerBrowserDownloadDecision(policy, it))
        }
    }

    @Test
    fun pageGeneratedFilesAreExplainedInsteadOfBlocked() {
        listOf("blob:https://recruit.disco.co.jp/1234", "data:application/pdf;base64,AAAA").forEach {
            assertEquals(it, CareerBrowserDownloadDecision.PAGE_GENERATED, careerBrowserDownloadDecision(policy, it))
        }
    }

    @Test
    fun cleartextAndLocalFilesStayBlocked() {
        listOf(
            null,
            "http://recruit.disco.co.jp/files/entry_sheet.pdf",
            "https://100.103.190.68/file.pdf",
            "https://localhost/file.pdf",
            "https://recruit.disco.co.jp:8443/file.pdf",
            "ftp://example.test/file.pdf",
        ).forEach {
            assertEquals(it.toString(), CareerBrowserDownloadDecision.NOT_ALLOWED, careerBrowserDownloadDecision(policy, it))
        }
    }
}
