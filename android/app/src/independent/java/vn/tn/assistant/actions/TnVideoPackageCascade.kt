package vn.tn.assistant.actions

/**
 * Preferred YouTube handlers mirrored from the original app's video-client order.
 * The original Kiki package is intentionally omitted because TN is a separate app
 * and must not route playback back into the source app.
 */
internal object TnVideoPackageCascade {
    private const val ORIGINAL_APP_PACKAGE = "ai.zalo.kiki.car"
    private val validPackage = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val originalPriority = listOf(
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
        ORIGINAL_APP_PACKAGE,
        "com.kt.apps.video",
    )

    fun candidates(selectedPackage: String?): List<String> = buildList {
        val selected = selectedPackage?.trim().orEmpty()
        if (selected.matches(validPackage) && selected != ORIGINAL_APP_PACKAGE) add(selected)
        originalPriority.forEach { candidate ->
            if (candidate != ORIGINAL_APP_PACKAGE && candidate !in this) add(candidate)
        }
    }
}
