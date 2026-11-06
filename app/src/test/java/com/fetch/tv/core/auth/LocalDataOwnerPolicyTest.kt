package com.fetch.tv.core.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDataOwnerPolicyTest {

    @Test
    fun `same account signing back in keeps local data`() {
        assertFalse(shouldResetLocalDataForSignIn("user-a", "user-a"))
    }

    @Test
    fun `different account signing in clears the previous account's data`() {
        assertTrue(shouldResetLocalDataForSignIn("user-a", "user-b"))
    }

    @Test
    fun `no recorded owner keeps local data`() {
        assertFalse(shouldResetLocalDataForSignIn(null, "user-a"))
        assertFalse(shouldResetLocalDataForSignIn("", "user-a"))
    }
}
