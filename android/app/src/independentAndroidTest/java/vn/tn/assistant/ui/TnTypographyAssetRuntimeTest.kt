package vn.tn.assistant.ui

import androidx.core.content.res.ResourcesCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import vn.tn.assistant.R
import java.security.MessageDigest

/** Guards the user-visible Barlow faces against drifting from the original app resources. */
class TnTypographyAssetRuntimeTest {
    @Test fun onboardingAndLoginFontsUseTheExactOriginalBarlowFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertFont(context, R.font.tn_barlow_regular, "CD90F6856CB7CD099B881B6370B330710A8BF9D082B01FB9FF949DF01005BD87")
        assertFont(context, R.font.tn_barlow_semibold, "4E73C7C492611F36DE3EEC520C830BB8FD970BE7EDFFA670AE55311995DB8562")
        assertNotNull("The regular face used by onboarding and login must load natively", ResourcesCompat.getFont(context, R.font.tn_barlow_regular))
        assertNotNull("The semibold face used by onboarding headings must load natively", ResourcesCompat.getFont(context, R.font.tn_barlow_semibold))
    }

    private fun assertFont(context: android.content.Context, resource: Int, expected: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        val actual = context.resources.openRawResource(resource).use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02X".format(it) }
        }
        assertEquals("The bundled font resource must match the original Barlow file", expected, actual)
    }
}
