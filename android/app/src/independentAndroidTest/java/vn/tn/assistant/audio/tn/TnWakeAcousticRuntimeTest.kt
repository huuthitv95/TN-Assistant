package vn.tn.assistant.audio.tn

import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.pm.PackageInfoCompat
import android.content.ContextWrapper
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.tn.assistant.audio.NativeAudio
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.security.MessageDigest

/** Opt-in PCM fixtures are provisioned privately; actual Vosk/Silero inference, not a fake detector. */
class TnWakeAcousticRuntimeTest {
    companion object {
        private const val SYNTHETIC_MANIFEST_SHA256 =
            "e39e1b5b1db889a663fc5b00bbb296a8f5d1e20ba1766afdfa6b440d35d01b5e"
        private const val SYNTHETIC_SAMPLE_COUNT = 75

        private fun syntheticNames(): List<String> = buildList {
            for (prefix in listOf("p", "n")) {
                val count = if (prefix == "p") 5 else 20
                for (index in 0 until count) {
                    for (repeat in 0..2) add("$prefix${index.toString().padStart(2, '0')}-r$repeat.pcm")
                }
            }
        }
    }

    @Test fun truncatedSyntheticManifestIsRejected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.filesDir, "tn-wake-integrity-${java.util.UUID.randomUUID()}")
        try {
            folder.mkdirs()
            File(folder, "manifest.json").writeText("[]")
            try {
                verifiedCorpus(context, "synthetic", folder)
                fail("A truncated synthetic manifest must be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("canonical"))
            }
        } finally {
            assertTrue("Remove integrity-test corpus", folder.deleteRecursively())
        }
    }

    @Test fun symlinkedCorpusDirectoryIsRejected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "tn-wake-integrity-${java.util.UUID.randomUUID()}")
        val target = File(context.filesDir, "tn-wake-integrity-target-${java.util.UUID.randomUUID()}")
        try {
            assertTrue(target.mkdirs())
            Os.symlink(target.absolutePath, root.absolutePath)
            try {
                verifiedCorpus(context, "synthetic", root)
                fail("A symlinked corpus directory must be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("Provision corpus"))
            }
        } finally {
            root.delete()
            assertTrue("Remove integrity-test target", target.deleteRecursively())
        }
    }

    @Test fun readingACorpusSampleReleasesItsFileDescriptor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("tn-wake-fd-", ".pcm", context.cacheDir)
        try {
            val pcm = byteArrayOf(1, 0)
            file.writeBytes(pcm)
            assertArrayEquals(pcm, readCorpusFile(file.parentFile!!, file.name))
            val sample = Os.stat(file.path)
            val descriptors = requireNotNull(File("/proc/self/fd").listFiles())
            assertFalse("Sample file descriptor remains open", descriptors.any {
                val opened = runCatching { Os.stat(it.path) }.getOrNull()
                opened?.st_dev == sample.st_dev && opened.st_ino == sample.st_ino
            })
        } finally {
            assertTrue("Remove test sample", file.delete())
        }
    }

    @Test fun damagedCacheWithMatchingMarkerRecoversFromApk() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(targetContext.cacheDir, "wake-cache-" + java.util.UUID.randomUUID())
        val isolated = object : ContextWrapper(targetContext) {
            override fun getNoBackupFilesDir() = directory
        }
        try {
            NativeAudio(isolated).use { it.init() }
            val model = File(directory, "tn-models/vosk-small-vn-0.4-v1/am/final.mdl")
            val fullSize = model.length()
            assertTrue(fullSize > 0)
            model.writeBytes(byteArrayOf())
            NativeAudio(isolated).use { engine ->
                engine.init()
                assertFalse(engine.wake(ShortArray(512), 512))
            }
            assertEquals(fullSize, model.length())
        } finally {
            assertTrue("Remove test model cache", directory.deleteRecursively())
        }
    }

    @Test fun actualPackagedModelsRecognizeWakeAndRejectTheNegativeCorpus() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val corpus = verifiedCorpus(context)
        val manifest = corpus.manifest
        val results = JSONArray()
        NativeAudio(context, 1000).use { engine ->
            engine.init()
            for (index in 0 until manifest.length()) {
                engine.reset()
                val item = manifest.getJSONObject(index)
                val bytes = corpus.snapshots[item.getString("file")]!!
                var detected = false
                var endpoint = false
                var offset = 0
                while (offset < bytes.size + 32000) {
                    val count = minOf(512, (bytes.size + 32000 - offset) / 2)
                    val frame = ShortArray(count) { sample ->
                        val pos = offset + sample * 2
                        if (pos + 1 < bytes.size) ((bytes[pos].toInt() and 255) or (bytes[pos + 1].toInt() shl 8)).toShort() else 0
                    }
                    detected = engine.wake(frame, count) || detected
                    endpoint = engine.endpoint(frame, count) || endpoint
                    offset += count * 2
                }
                results.put(JSONObject().put("file", item.getString("file"))
                    .put("expected_wake", item.getBoolean("expected_wake")).put("detected", detected).put("vad_endpoint", endpoint))
            }
        }
        val report = if (corpus.folder.name == "tn-wake-cc0-negative") "tn-wake-cc0-negative.json" else "tn167-wake-acoustic.json"
        File(context.cacheDir, report).writeText(corpus.provenance.put("results", results).toString(2))
        for (index in 0 until results.length()) {
            val item = results.getJSONObject(index)
            assertEquals("Real acoustic decision for ${item.getString("file")}", item.getBoolean("expected_wake"), item.getBoolean("detected"))
        }
    }

    @Test fun diagnosticGrammarProbeForThePermittedCorpus() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val corpus = verifiedCorpus(context)
        val folder = corpus.folder
        val manifest = corpus.manifest
        val results = JSONArray()
        val modelPath = File(context.noBackupFilesDir, "tn-models/vosk-small-vn-0.4-v1")
        NativeAudio(context, 1000).use { it.init() }
        val grammar = arguments.getString("tnWakeDiagnosticGrammarBase64")
            ?.let { String(android.util.Base64.decode(it, android.util.Base64.NO_WRAP), Charsets.UTF_8) }
            ?: JSONArray(WakePhrase.recognitionGrammar).toString()
        val leadingSilenceMillis = (arguments.getString("tnWakeLeadingSilenceMs") ?: "0").toInt()
        require(leadingSilenceMillis in 0..2000)
        val split = arguments.getString("tnWakeDiagnosticSplit")
        require(split == null || split in setOf("development", "holdout"))
        org.vosk.Model(modelPath.absolutePath).use { model ->
            for (index in 0 until manifest.length()) {
                val item = manifest.getJSONObject(index)
                if (split != null && item.optString("split") != split) continue
                val bytes = corpus.snapshots[item.getString("file")]!!
                val signal = ByteArray(leadingSilenceMillis * 32 + bytes.size + 32000)
                System.arraycopy(bytes, 0, signal, leadingSilenceMillis * 32, bytes.size)
                org.vosk.Recognizer(model, 16000f, grammar).use { decoder ->
                    decoder.setWords(true)
                    decoder.setPartialWords(true)
                    val observations = JSONArray()
                    var previous = ""
                    var observedText = ""
                    var detectedWake = false
                    for (offset in signal.indices step 1024) {
                        val frame = signal.copyOfRange(offset, minOf(offset + 1024, signal.size))
                        val complete = decoder.acceptWaveForm(frame, frame.size)
                        val result = JSONObject(if (complete) decoder.result else decoder.partialResult)
                        val text = result.optString(if (complete) "text" else "partial")
                        if (text.isNotBlank()) observedText = text
                        detectedWake = detectedWake || WakePhrase.matches(text)
                        if (text != previous) { observations.put(result); previous = text }
                    }
                    results.put(JSONObject().put("file", item.getString("file"))
                        .put("leading_silence_ms", leadingSilenceMillis)
                        .put("observations", observations).put("decoded", JSONObject(decoder.finalResult))
                        .put("observed_text", observedText).put("detected_wake", detectedWake))
                }
            }
        }
        val report = if (folder.name == "tn-wake-cc0-negative") "tn-wake-cc0-diagnostic.json" else "tn167-wake-diagnostic.json"
        File(context.cacheDir, report).writeText(corpus.provenance.put("results", results).toString(2))
        val selectedCount = (0 until manifest.length()).count { split == null || manifest.getJSONObject(it).optString("split") == split }
        assertEquals(selectedCount, results.length())
    }

    private data class VerifiedCorpus(
        val folder: File,
        val manifest: JSONArray,
        val snapshots: Map<String, ByteArray>,
        val provenance: JSONObject,
    )

    private fun verifiedCorpus(context: android.content.Context): VerifiedCorpus {
        val arguments = InstrumentationRegistry.getArguments()
        val selector = arguments.getString("tnWakeCorpus")
        if (selector == null) {
            assumeTrue("tnWakeCorpus omitted; corpus evaluation is opt-in", false)
        }
        require(selector == "synthetic" || selector == "cc0-negative") {
            "tnWakeCorpus must be synthetic or cc0-negative"
        }
        return verifiedCorpus(context, selector, File(context.filesDir,
            if (selector == "cc0-negative") "tn-wake-cc0-negative" else "tn-wake-test"))
    }

    private fun verifiedCorpus(
        context: android.content.Context,
        selector: String,
        folder: File,
    ): VerifiedCorpus {
        val arguments = InstrumentationRegistry.getArguments()
        val directoryFd = try {
            Os.open(folder.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        } catch (_: ErrnoException) {
            throw IllegalArgumentException("Provision corpus before selecting tnWakeCorpus=$selector")
        }
        try {
            require((Os.fstat(directoryFd).st_mode and OsConstants.S_IFMT) == OsConstants.S_IFDIR) {
                "Corpus root must be a directory"
            }
            // API 21 has no public openat/openat2. Child opens are pathname-based;
            // identity checks detect root replacement at checkpoints but cannot close
            // the final check/open race. Strict descriptor-relative containment needs
            // a native helper, so this test deliberately states the narrower invariant.
            fun requireStableRoot() {
                val opened = Os.fstat(directoryFd)
                val current = Os.stat(folder.absolutePath)
                require(opened.st_dev == current.st_dev && opened.st_ino == current.st_ino) {
                    "Corpus root was replaced during verification"
                }
            }
            requireStableRoot()
            val manifestBytes = try {
                readCorpusFile(folder, "manifest.json")
            } catch (_: ErrnoException) {
                throw IllegalArgumentException("Provision corpus before selecting tnWakeCorpus=$selector")
            }
            val manifestSha256 = sha256(manifestBytes)
            if (selector == "synthetic") {
                require(manifestSha256 == SYNTHETIC_MANIFEST_SHA256) {
                    "Synthetic manifest is not the pinned canonical manifest"
                }
            }
            requireStableRoot()
            val manifest = JSONArray(String(manifestBytes, Charsets.UTF_8))
            if (selector == "synthetic") {
                require(manifest.length() == SYNTHETIC_SAMPLE_COUNT) {
                    "Synthetic manifest must contain exactly $SYNTHETIC_SAMPLE_COUNT samples"
                }
            } else {
                require(manifest.length() > 0) { "Corpus manifest is empty" }
            }
            val snapshots = linkedMapOf<String, ByteArray>()
            val names = mutableSetOf<String>()
            val digest = MessageDigest.getInstance("SHA-256")
            val expectedNames = if (selector == "synthetic") syntheticNames() else null
            for (index in 0 until manifest.length()) {
                val item = manifest.getJSONObject(index)
                if (expectedNames != null) {
                    require(item.optString("file") == expectedNames[index]) {
                        "Synthetic manifest filename/order differs from canonical sample set"
                    }
                    require(item.getBoolean("expected_wake") == (index < 15)) {
                        "Synthetic manifest expected_wake differs from canonical labels"
                    }
                }
                val name = item.optString("file")
                require(name.matches(Regex("[A-Za-z0-9.-]+")) && names.add(name)) { "Invalid or duplicate corpus filename" }
                require(item.opt("expected_wake") is Boolean) { "expected_wake must be Boolean" }
                require(selector != "cc0-negative" || !item.getBoolean("expected_wake")) { "Expected a non-wake sample" }
                require(item.getInt("sample_rate") == 16000 && item.getInt("channels") == 1 && item.getInt("sample_width") == 2) { "Unexpected PCM metadata" }
                val bytes = try {
                    readCorpusFile(folder, name)
                } catch (_: ErrnoException) {
                    throw IllegalArgumentException("Unsafe or missing corpus sample: $name")
                }
                require(bytes.size == item.getInt("bytes") && bytes.size % 2 == 0) { "PCM byte count differs: $name" }
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                require(sha == item.getString("sha256")) { "PCM checksum differs: $name" }
                requireStableRoot()
                snapshots[name] = bytes
                digest.update(name.toByteArray(Charsets.UTF_8)); digest.update(0)
                digest.update(item.getBoolean("expected_wake").toString().toByteArray()); digest.update(0)
                digest.update(item.getInt("bytes").toString().toByteArray()); digest.update(0)
                digest.update(sha.toByteArray()); digest.update(0)
            }
            require(expectedNames == null || names.toList() == expectedNames) {
                "Synthetic manifest sample set differs from canonical sample set"
            }
            val manifestDigest = digest.digest().joinToString("") { "%02x".format(it) }
            val apkInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val files = JSONArray().apply {
                snapshots.forEach { (name, bytes) ->
                    put(JSONObject().put("file", name).put("bytes", bytes.size).put("sha256", sha256(bytes)))
                }
            }
            val provenance = JSONObject().put("selector", selector).put("manifest_sha256", manifestSha256)
                .put("canonical_manifest_sha256", if (selector == "synthetic") SYNTHETIC_MANIFEST_SHA256 else JSONObject.NULL)
                .put("selected_sample_count", manifest.length()).put("files", files)
                .put("manifest_digest", manifestDigest).put("package", context.packageName)
                .put("apk_version", PackageInfoCompat.getLongVersionCode(apkInfo)).put("device", Build.FINGERPRINT)
                .put("audio", JSONObject().put("sample_rate", 16000).put("channels", 1).put("sample_width", 2))
                .put("instrumentation_args", JSONObject().apply { arguments.keySet().forEach { put(it, arguments.getString(it)) } })
            return VerifiedCorpus(folder, manifest, snapshots, provenance)
        } finally {
            Os.close(directoryFd)
        }
    }

    private fun readCorpusFile(folder: File, name: String): ByteArray {
        require(name.matches(Regex("[A-Za-z0-9.-]+"))) { "Invalid corpus filename" }
        val file = File(folder, name)
        val fileFd = Os.open(file.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try {
            require((Os.fstat(fileFd).st_mode and OsConstants.S_IFMT) == OsConstants.S_IFREG) {
                "Corpus entry must be a regular file"
            }
            // On Android FileInputStream(FileDescriptor) borrows the descriptor.
            return java.io.FileInputStream(fileFd).use { it.readBytes() }
        } finally {
            Os.close(fileFd)
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
