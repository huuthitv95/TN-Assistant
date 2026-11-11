package vn.tn.assistant.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.xmlpull.v1.XmlPullParser

/**
 * Inflates test-only source XML and reads its constraint graph by attribute name.
 * The instrumentation APK assigns library attributes different numeric IDs from
 * the tested APK. Android attributes still inflate normally; resolving custom
 * constraint names avoids silently testing a graph whose edges were discarded.
 * No TN production layout code or measured output is used to build this oracle.
 */
internal object TnSourceConstraintOracle {
    fun inflate(context: Context, resourceName: String): ConstraintLayout {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val resourceId = testContext.resources.getIdentifier(resourceName, "layout", testContext.packageName)
        require(resourceId != 0) { "Missing source XML oracle: $resourceName" }
        val themedContext = ContextThemeWrapper(testContext.createConfigurationContext(context.resources.configuration),
            android.R.style.Theme_Material_Light_NoActionBar)
        val root = testContext.resources.getLayout(resourceId).use {
            LayoutInflater.from(themedContext).inflate(it, null, false) as ConstraintLayout
        }
        testContext.resources.getLayout(resourceId).use { parser ->
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.depth == 2) {
                    val childId = parser.getAttributeResourceValue("http://schemas.android.com/apk/res/android", "id", View.NO_ID)
                    val child = root.findViewById<View>(childId)
                    requireNotNull(child) { "Source XML child has no inflated counterpart: $childId" }
                    val lp = child.layoutParams as ConstraintLayout.LayoutParams
                    for (index in 0 until parser.attributeCount) {
                        val attribute = parser.getAttributeName(index)
                        val target = if (parser.getAttributeValue(index) == "parent") 0
                            else parser.getAttributeResourceValue(index, parser.getAttributeIntValue(index, -1))
                        when (attribute) {
                            "layout_constraintStart_toStartOf" -> lp.startToStart = target
                            "layout_constraintStart_toEndOf" -> lp.startToEnd = target
                            "layout_constraintEnd_toStartOf" -> lp.endToStart = target
                            "layout_constraintEnd_toEndOf" -> lp.endToEnd = target
                            "layout_constraintTop_toTopOf" -> lp.topToTop = target
                            "layout_constraintTop_toBottomOf" -> lp.topToBottom = target
                            "layout_constraintBottom_toTopOf" -> lp.bottomToTop = target
                            "layout_constraintBottom_toBottomOf" -> lp.bottomToBottom = target
                            "layout_constraintHorizontal_bias" -> lp.horizontalBias = parser.getAttributeFloatValue(index, .5f)
                            "layout_constraintVertical_bias" -> lp.verticalBias = parser.getAttributeFloatValue(index, .5f)
                            "layout_constraintHorizontal_chainStyle" -> lp.horizontalChainStyle = parser.getAttributeIntValue(index, 0)
                            "layout_constraintVertical_chainStyle" -> lp.verticalChainStyle = parser.getAttributeIntValue(index, 0)
                            "layout_constraintWidth_default" -> lp.matchConstraintDefaultWidth = parser.getAttributeIntValue(index, 0)
                            "layout_goneMarginEnd" -> {
                                val value = testContext.resources.obtainAttributes(parser,
                                    intArrayOf(parser.getAttributeNameResource(index)))
                                try { lp.goneEndMargin = value.getDimensionPixelSize(0, 0) }
                                finally { value.recycle() }
                            }
                        }
                    }
                    child.layoutParams = lp
                }
                parser.next()
            }
        }
        return root
    }
}
