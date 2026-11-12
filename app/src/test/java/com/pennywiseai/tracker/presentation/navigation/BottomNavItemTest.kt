package com.pennywiseai.tracker.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomNavItemTest {

    private val allRoutes = BottomNavItem.all.map { it.route }.toSet()

    @Test
    fun `nothing hidden by default shows every tab in order`() {
        assertEquals(
            listOf(BottomNavItem.Home, BottomNavItem.Transactions, BottomNavItem.Shared, BottomNavItem.Analytics, BottomNavItem.Chat),
            BottomNavItem.visible(emptySet())
        )
    }

    @Test
    fun `home stays even when every route is hidden`() {
        assertEquals(listOf(BottomNavItem.Home), BottomNavItem.visible(allRoutes))
    }

    @Test
    fun `hidden tabs drop out and the rest keep their order`() {
        assertEquals(
            listOf(BottomNavItem.Home, BottomNavItem.Shared, BottomNavItem.Analytics),
            BottomNavItem.visible(setOf("transactions", "chat"))
        )
    }

    @Test
    fun `shared can be hidden like the other tabs`() {
        assertTrue(BottomNavItem.Shared in BottomNavItem.hideable)
        assertEquals(listOf(BottomNavItem.Home, BottomNavItem.Transactions), BottomNavItem.visible(setOf("shared", "analytics", "chat")))
        assertEquals("home", BottomNavItem.fallbackFor("shared", setOf("shared")))
    }

    @Test
    fun `home is not offered as hideable`() {
        assertFalse(BottomNavItem.Home in BottomNavItem.hideable)
        assertEquals(BottomNavItem.all.size - 1, BottomNavItem.hideable.size)
    }

    @Test
    fun `being on a tab that was just hidden falls back to home`() {
        assertEquals("home", BottomNavItem.fallbackFor("chat", setOf("chat")))
        assertEquals("home", BottomNavItem.fallbackFor("transactions", setOf("transactions", "chat")))
    }

    @Test
    fun `visible tabs and home stay put`() {
        assertNull(BottomNavItem.fallbackFor("analytics", setOf("chat")))
        assertNull(BottomNavItem.fallbackFor("home", allRoutes))
    }
}
