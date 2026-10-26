package com.m57.hermescontrol.career

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CareerSavedLoginDisplayTest {
    private fun account(id: String = "local", company: String = "company", login: String = "local-id") =
        ShukatsuLoginAccount(id, company, "Company", "MyPage", "", login)

    @Test fun serverOnlyLoginIsPlainAndSelectedWithoutLocalPersistence() {
        val rows = savedLoginAccounts(emptyList(), "company", "Company", "dummy-server-id")
        assertEquals("dummy-server-id", selectedLoginAccount(rows, null, "dummy-server-id")?.username)
        assertEquals("server-mypage:company", rows.single().sourceId)
    }

    @Test fun readFailurePreservesLocalAccountsAndDoesNotInventAnEmptyRecord() {
        val local = listOf(account())
        assertEquals(local, savedLoginAccounts(local, "company", "Company", null))
        assertEquals(emptyList<ShukatsuLoginAccount>(), savedLoginAccounts(emptyList(), "company", "Company", null))
    }

    @Test fun wrongCompanyRecordsAreNotDisplayedButImportedCompanyMappingIsSupported() {
        val rows = savedLoginAccounts(listOf(account(company = "other")), "company", "Company", null)
        assertEquals(emptyList<ShukatsuLoginAccount>(), rows)
        val imported = account(company = "raw-company")
        assertEquals(listOf(imported), savedLoginAccounts(listOf(imported), "shukatsu:Company:raw-company", "Company", null))
    }

    @Test fun multipleLoginsPreferRegisteredAccountWithoutGuessingWhenReadFails() {
        val rows = listOf(account("one", login = "first"), account("two", login = "second"))
        assertNull(selectedLoginAccount(rows, null, null))
        assertEquals("two", selectedLoginAccount(rows, null, "second")?.sourceId)
        assertEquals("one", selectedLoginAccount(rows, "one", "second")?.sourceId)
        assertNull(selectedLoginAccount(rows, "removed", "second"))
    }

    @Test fun matchingAccountIsNotDuplicatedAndEditedSelectionIsNotOverwritten() {
        val local = listOf(account(login = "same"))
        assertEquals(local, savedLoginAccounts(local, "company", "Company", "same"))
        val edited = account(login = "editing")
        val merged = savedLoginAccounts(listOf(edited), "company", "Company", "server")
        assertEquals("editing", selectedLoginAccount(merged, "local", "server")?.username)
    }

    @Test fun updatedServerAccountHasOneStableIdentity() {
        val old = savedLoginAccounts(emptyList(), "company", "Company", "old")
        val updated = savedLoginAccounts(old, "company", "Company", "new")
        assertEquals(1, updated.size)
        assertEquals("new", updated.single().username)
    }
}
