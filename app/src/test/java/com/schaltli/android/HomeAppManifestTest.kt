package com.schaltli.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The kiosk that survives a restart rests on two lines of the manifest
 * (HomeApp.kt): MainActivity offered as a home app, and launched singleTask so
 * the Home key returns to the running panel instead of stacking a second one.
 * Neither shows up anywhere but on a phone after a reboot, so they are held
 * here.
 */
class HomeAppManifestTest {
    private val activity: Element by lazy {
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
            .map(::File).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest)
        val activities = doc.getElementsByTagName("activity")
        (0 until activities.length).map { activities.item(it) as Element }
            .first { it.getAttributeNS(ANDROID, "name") == ".MainActivity" }
    }

    @Test
    fun `MainActivity can be chosen as the home app`() {
        val filters = activity.getElementsByTagName("intent-filter")
        val home = (0 until filters.length).map { filters.item(it) as Element }.any { filter ->
            val categories = filter.getElementsByTagName("category")
            val names = (0 until categories.length).map { (categories.item(it) as Element).getAttributeNS(ANDROID, "name") }
            "android.intent.category.HOME" in names && "android.intent.category.DEFAULT" in names
        }
        assertTrue("MainActivity has no HOME + DEFAULT intent filter", home)
    }

    @Test
    fun `the Home key returns to the running panel`() {
        assertEquals("singleTask", activity.getAttributeNS(ANDROID, "launchMode"))
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
