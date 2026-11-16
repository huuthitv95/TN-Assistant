package vn.tn.assistant.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TnVideoPackageCascadeTest {
    @Test fun automaticSelectionFollowsTheOriginalVideoClientPriorityWithoutReopeningKiki() {
        assertEquals(
            listOf(
                "app.revanced.android.youtube",
                "app.rvx.android.youtube",
                "com.aicity.vanced",
                "com.gold.android.youtube",
                "com.google.android.youtube",
                "com.google.android.youtube.tv",
                "com.google.android.youtube.tvkids",
                "com.gotech.android.youtube",
                "com.gotech.gotube",
                "com.liskovsoft.smartyoutubetv2.tv",
                "com.lochv.zestech.zestechyoutube",
                "com.teamsmart.videomanager.tv",
                "com.vanced.android.youtube",
                "free.tube.premium.advanced.tuber",
                "org.community.vtube",
                "org.schabi.newpipe",
                "com.lochv.zestech.ZTTube",
                "com.lochv.zestech.ZTTube2",
                "com.eononpro.tube.eononpro",
                "com.msport.msporttube",
                "vn.net.htd.entertainment",
                "com.aicity.htd.v2",
                "com.htd.tuber",
                "com.kt.apps.video",
            ),
            TnVideoPackageCascade.candidates(null),
        )
    }

    @Test fun explicitInstalledAppComesFirstAndDuplicatesAreRemoved() {
        val packages = TnVideoPackageCascade.candidates("  com.google.android.youtube  ")
        assertEquals("com.google.android.youtube", packages.first())
        assertEquals(packages.size, packages.distinct().size)
    }

    @Test fun invalidPreferenceIsIgnoredAndSourceKikiIsNeverAPlaybackTarget() {
        val packages = TnVideoPackageCascade.candidates("https://example.com")
        assertEquals("app.revanced.android.youtube", packages.first())
        assertFalse("ai.zalo.kiki.car" in packages)
        assertFalse("ai.zalo.kiki.car" in TnVideoPackageCascade.candidates("ai.zalo.kiki.car"))
        assertTrue(packages.contains("org.schabi.newpipe"))
    }
}
