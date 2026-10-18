package io.github.zapretdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviZibleTest {
    private fun release(vararg names: String) =
        Updater.Release("6.9.3", "v6.9.3", "", "", names.associateWith { "https://example/$it" })

    @Test
    fun picksTheFileOfTheArchitecture() {
        val r = release("InviZible_Pro_6.9.3_arm64-v8a.apk", "InviZible_Pro_6.9.3_armeabi-v7a.apk", "InviZible_Pro_6.9.3_x86_64.apk", "InviZible_Pro_6.9.3_x86.apk")
        assertEquals("InviZible_Pro_6.9.3_armeabi-v7a.apk", InviZible.assetFor(r, listOf("armeabi-v7a", "armeabi"))?.first)
        assertEquals("InviZible_Pro_6.9.3_arm64-v8a.apk", InviZible.assetFor(r, listOf("arm64-v8a", "armeabi-v7a"))?.first)
        assertEquals("InviZible_Pro_6.9.3_x86.apk", InviZible.assetFor(r, listOf("x86"))?.first)
        assertEquals("InviZible_Pro_6.9.3_x86_64.apk", InviZible.assetFor(r, listOf("x86_64"))?.first)
    }

    @Test
    fun otherSpellingsAndUniversal() {
        assertEquals("invizible.arm64.apk", InviZible.assetFor(release("invizible.arm64.apk", "invizible.armv7.apk"), listOf("arm64-v8a"))?.first)
        assertEquals("invizible.armv7.apk", InviZible.assetFor(release("invizible.arm64.apk", "invizible.armv7.apk"), listOf("armeabi-v7a"))?.first)
        assertEquals("InviZible_Pro_universal.apk", InviZible.assetFor(release("InviZible_Pro_universal.apk", "notes.txt"), listOf("arm64-v8a"))?.first)
        assertNull(InviZible.assetFor(release("InviZible_Pro_arm64-v8a.apk", "source.zip"), listOf("armeabi-v7a")))
    }
}
