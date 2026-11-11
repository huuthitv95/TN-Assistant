package vn.tn.assistant.audio.tn

import java.text.Normalizer
import java.util.Locale

/** Prefix recognition only; a phrase mentioned later in a sentence is not a wake. */
internal object WakePhrase {
    private val prefixes = listOf("ti en oi", "te en oi", "te no oi", "ti no oi", "ti em oi", "te em oi", "tn oi", "t n oi")
    // Competing ordinary speech is essential: a grammar containing only the wake phrase
    // otherwise forces unrelated Vietnamese words into the nearest permitted sounds.
    val recognitionGrammar = listOf(
        // The small-vn dictionary has no "nờ". Its existing nơ/nở pronunciations
        // keep all three syllables in the graph; unknown words would be dropped.
        "ti en ơi", "tê en ơi", "tê nơ ơi", "tê nở ơi", "ti nơ ơi", "ti em ơi", "tê em ơi", "[unk]",
        "xin chào", "xin chào mọi người", "chào bạn", "cảm ơn", "tạm biệt",
        "tên của bạn là gì", "bạn tên gì", "tôi tên", "tên", "tiền", "tiếng", "tình yêu",
        "kiki ơi", "ki ki ơi", "trời ơi", "em ơi", "anh ơi", "bạn ơi", "chị ơi", "ơi",
        "hôm nay trời đẹp quá", "hôm nay trời mưa", "thời tiết hôm nay", "ngày mai", "bây giờ",
        "mở bản đồ", "mở nhạc", "mở video", "dừng nhạc", "tăng âm lượng", "giảm âm lượng",
        "dẫn đường", "gọi điện", "hà nội", "đà nẵng", "sài gòn", "thành phố hồ chí minh",
        "không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín", "mười",
    )

    fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace('đ', 'd')
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    fun matches(value: String): Boolean {
        val text = normalize(value)
        return prefixes.any { text == it || text.startsWith("$it ") }
    }

    fun strip(value: String): String {
        val words = Regex("[\\p{L}\\p{N}]+").findAll(value).toList()
        for (count in 1..minOf(words.size, 6)) {
            val ending = words[count - 1].range.last + 1
            if (normalize(value.substring(0, ending)) in prefixes) {
                return value.substring(ending).trim(' ', ',', '.', '!', '?', ':')
            }
        }
        return value
    }

    /** A visible microphone tap is already activation; a wake prefix is optional. */
    fun commandFromMicrophoneTap(value: String): String = strip(value).trim()

    /** ASR may render a locally confirmed "Ti En" as "N". These are never acoustic wake phrases. */
    fun commandAfterConfirmedWake(value: String): String? {
        if (matches(value)) return strip(value)
        val aliases = setOf("n oi", "en oi", "tien oi")
        val words = Regex("[\\p{L}\\p{N}]+").findAll(value).toList()
        for (count in 1..minOf(words.size, 3)) {
            val end = words[count - 1].range.last + 1
            if (normalize(value.substring(0, end)) in aliases) return value.substring(end).trim(' ', ',', '.', '!', '?', ':')
        }
        return null
    }
}
