package vn.tn.assistant

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class TnAppRelease(
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val url: String,
    val sha256: String,
    val notes: String,
) {
    fun savedState(): String = JSONObject()
        .put("available", true)
        .put("application_id", applicationId)
        .put("version_code", versionCode)
        .put("version_name", versionName)
        .put("min_sdk", minSdk)
        .put("url", url)
        .put("sha256", sha256)
        .put("notes", notes)
        .toString()

    companion object {
        fun parse(
            response: JSONObject,
            expectedApplicationId: String,
            installedVersionCode: Long,
            deviceSdk: Int = Build.VERSION.SDK_INT,
        ): TnAppRelease? {
            if (!response.optBoolean("available")) return null
            val applicationId = response.optString("application_id").trim()
            val versionCode = response.optLong("version_code", 0)
            val versionName = response.optString("version_name").trim()
            val minSdk = response.optInt("min_sdk", 0)
            val url = response.optString("url").trim()
            val sha256 = response.optString("sha256").trim().lowercase(Locale.ROOT)
            if (applicationId != expectedApplicationId) throw IOException("Bản cập nhật dành cho gói ứng dụng khác.")
            if (versionCode <= installedVersionCode) return null
            if (versionCode <= 0 || versionName.isBlank() || minSdk < 21 ||
                !sha256.matches(Regex("^[a-f0-9]{64}$")) || url.isBlank()) {
                throw IOException("Thông tin bản cập nhật không hợp lệ.")
            }
            if (minSdk > deviceSdk) throw IOException("Bản cập nhật cần Android API " + minSdk + " trở lên.")
            return TnAppRelease(applicationId, versionCode, versionName, minSdk, url, sha256, response.optString("notes").trim())
        }

        fun restoreSavedState(
            state: String?,
            expectedApplicationId: String,
            installedVersionCode: Long,
            deviceSdk: Int = Build.VERSION.SDK_INT,
        ): TnAppRelease? {
            if (state.isNullOrBlank()) return null
            return parse(JSONObject(state), expectedApplicationId, installedVersionCode, deviceSdk)
        }
    }
}

internal object TnReleaseUpdater {
    private const val MAX_APK_BYTES = 200L * 1024L * 1024L
    private const val APK_MIME = "application/vnd.android.package-archive"

    @Suppress("DEPRECATION")
    private fun signerCertificates(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        if (signatures.isNullOrEmpty()) throw IOException("Không xác minh được chữ ký APK TN.")
        val certificates = signatures.map { it.toCharsString() }.toSet()
        if (certificates.size != signatures.size) throw IOException("Chữ ký APK TN không hợp lệ.")
        return certificates
    }

    fun download(activity: Activity, release: TnAppRelease, client: OkHttpClient): File {
        if (release.applicationId != activity.packageName) {
            throw IOException("Bản cập nhật dành cho gói ứng dụng khác.")
        }
        if (release.minSdk > Build.VERSION.SDK_INT) {
            throw IOException("Bản cập nhật cần Android API " + release.minSdk + " trở lên.")
        }
        val apiBase = BuildConfig.API_BASE_URL.toHttpUrlOrNull()
            ?: throw IOException("Địa chỉ máy chủ cập nhật không hợp lệ.")
        val downloadUrl = apiBase.resolve(release.url)
            ?: throw IOException("Đường dẫn bản cập nhật không hợp lệ.")
        if (downloadUrl.scheme != "https" || downloadUrl.host != apiBase.host || downloadUrl.port != 443 ||
            downloadUrl.username.isNotEmpty() || downloadUrl.password.isNotEmpty() ||
            downloadUrl.encodedQuery != null || downloadUrl.encodedFragment != null ||
            !downloadUrl.encodedPath.matches(Regex("^/downloads/[A-Za-z0-9][A-Za-z0-9._-]*\\.apk$"))) {
            throw IOException("Bản cập nhật phải tải từ thư mục HTTPS chính thức của TN.")
        }

        val directory = File(activity.cacheDir, "updates")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Không tạo được thư mục cập nhật.")
        val partial = File(directory, "tn-assistant-" + release.versionCode + ".part")
        val apk = File(directory, "tn-assistant-" + release.versionCode + ".apk")
        partial.delete()
        apk.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            val downloadClient = client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .readTimeout(120, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(downloadUrl).get().build()
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Không tải được APK (HTTP " + response.code + ").")
                val body = response.body ?: throw IOException("Máy chủ không gửi nội dung APK.")
                if (body.contentLength() > MAX_APK_BYTES) throw IOException("APK vượt quá giới hạn tải xuống.")
                body.byteStream().use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_APK_BYTES) throw IOException("APK vượt quá giới hạn tải xuống.")
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
            }
            if (total == 0L) throw IOException("APK tải về đang trống.")
            val actualHash = digest.digest()
            val expectedHash = release.sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            if (!MessageDigest.isEqual(actualHash, expectedHash)) throw IOException("SHA-256 APK không khớp.")

            @Suppress("DEPRECATION")
            val signatureFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
            val archive = activity.packageManager.getPackageArchiveInfo(partial.absolutePath, signatureFlags)
                ?: throw IOException("Tệp tải về không phải APK hợp lệ.")
            if (archive.packageName != release.applicationId ||
                PackageInfoCompat.getLongVersionCode(archive) != release.versionCode ||
                archive.versionName != release.versionName) {
                throw IOException("Thông tin APK không khớp bản phát hành.")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val apkMinSdk = archive.applicationInfo?.minSdkVersion
                    ?: throw IOException("APK không khai báo Android API tối thiểu.")
                if (apkMinSdk != release.minSdk) throw IOException("minSdk trong APK không khớp bản phát hành.")
                if (apkMinSdk > Build.VERSION.SDK_INT) {
                    throw IOException("APK cần phiên bản Android mới hơn thiết bị này.")
                }
            }
            val installed = activity.packageManager.getPackageInfo(activity.packageName, signatureFlags)
            if (signerCertificates(archive) != signerCertificates(installed)) {
                throw IOException("Chữ ký APK không khớp ứng dụng TN đang cài.")
            }
            if (!partial.renameTo(apk)) throw IOException("Không lưu được APK đã kiểm tra.")
            return apk
        } catch (error: Exception) {
            partial.delete()
            apk.delete()
            if (error is IOException) throw error
            throw IOException("Không tải được bản cập nhật TN.", error)
        }
    }

    fun install(activity: Activity, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(activity, activity.packageName + ".updates", apk)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(intent)
    }
}
