package com.ciphervault.callrecorder

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ciphervault.callrecorder.databinding.ActivityAboutBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AboutActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "CR_AboutActivity"
        const val ERROR_PREFIX = "CR-AB-"
        private const val GITHUB_REPO = "jnetai-clawbot/call-recorder"
        private const val GITHUB_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
        private const val GITHUB_RELEASES_URL = "https://github.com/$GITHUB_REPO/releases"
    }

    enum class ErrorCode(val code: String, val description: String) {
        AB001("AB001", "GitHub API connection failed"),
        AB002("AB002", "GitHub API response parse error"),
        AB003("AB003", "Version check HTTP error"),
        AB004("AB004", "Version check timeout"),
        AB005("AB005", "Network not available"),
        AB999("AB999", "Unknown about activity error")
    }

    private lateinit var binding: ActivityAboutBinding
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private fun reportError(code: ErrorCode, message: String, exception: Exception? = null) {
        val fullMsg = "[$ERROR_PREFIX${code.code}] ${code.description}: $message"
        Log.e(TAG, fullMsg, exception)
    }

    private fun logDebug(message: String) {
        Log.d(TAG, message)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = packageInfo.versionName ?: "1.0.0"
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            binding.tvVersionInfo.text = "Version: $versionName (Build $versionCode)"
            logDebug("App version: $versionName, build code: $versionCode")
        } catch (e: Exception) {
            reportError(ErrorCode.AB999, "Failed to get package info", e)
            binding.tvVersionInfo.text = "Version: Unknown"
        }

        binding.tvGithubLink.movementMethod = LinkMovementMethod.getInstance()
        binding.tvGithubLink.text = getString(
            com.ciphervault.callrecorder.R.string.about_github_link,
            GITHUB_RELEASES_URL
        )

        binding.tvReleaseInfo.text = "Checking for latest release..."
        checkLatestRelease()
    }

    private fun checkLatestRelease() {
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    fetchLatestRelease()
                }
                binding.tvReleaseInfo.text = result
            } catch (e: Exception) {
                reportError(ErrorCode.AB001, "Unexpected version check error", e)
                binding.tvReleaseInfo.text = "Unable to check for updates: ${e.message}"
            }
        }
    }

    private suspend fun fetchLatestRelease(): String {
        try {
            val url = URL(GITHUB_API_URL)
            val connection = withTimeout(15000) {
                url.openConnection() as HttpURLConnection
            }

            connection.apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                connectTimeout = 10000
                readTimeout = 10000
            }

            val responseCode = connection.responseCode
            logDebug("GitHub API response code: $responseCode")

            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorMsg = "GitHub API returned HTTP $responseCode"
                if (responseCode == 403) {
                    return "Rate limited. Check $GITHUB_RELEASES_URL"
                }
                if (responseCode == 404) {
                    return "No releases found. Check $GITHUB_RELEASES_URL"
                }
                reportError(ErrorCode.AB003, errorMsg)
                return "Unable to check ($responseCode). Visit $GITHUB_RELEASES_URL"
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val json = JSONObject(response)
            val latestTag = json.optString("tag_name", "Unknown")
            val releaseName = json.optString("name", latestTag)
            val publishedAt = json.optString("published_at", "")
            val htmlUrl = json.optString("html_url", GITHUB_RELEASES_URL)

            val pkgInfo = packageManager.getPackageInfo(packageName, 0)
            val currentVersion = pkgInfo.versionName ?: "0.0.0"

            val latestClean = latestTag.removePrefix("v")
            val status = compareVersions(currentVersion, latestClean)

            logDebug("Current: $currentVersion, Latest: $latestClean, Status: $status")

            return buildString {
                append("Latest Release: $releaseName\n")
                if (publishedAt.isNotEmpty()) {
                    append("Released: ${publishedAt.take(10)}\n")
                }
                append("Status: $status\n")
                append("View releases: $htmlUrl")
            }
        } catch (e: java.net.UnknownHostException) {
            reportError(ErrorCode.AB005, "Network unavailable", e)
            return "No network connection. Visit $GITHUB_RELEASES_URL"
        } catch (e: java.net.SocketTimeoutException) {
            reportError(ErrorCode.AB004, "Request timed out", e)
            return "Request timed out. Visit $GITHUB_RELEASES_URL"
        } catch (e: Exception) {
            reportError(ErrorCode.AB001, "Failed to check version", e)
            return "Unable to check. Visit $GITHUB_RELEASES_URL"
        }
    }

    private fun compareVersions(current: String, latest: String): String {
        try {
            val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
            val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
            val maxLen = maxOf(currentParts.size, latestParts.size)

            for (i in 0 until maxLen) {
                val c = currentParts.getOrElse(i) { 0 }
                val l = latestParts.getOrElse(i) { 0 }
                if (l > c) return "Update available (v$latest)"
                if (c > l) return "Pre-release (newer than latest)"
            }
            return "Up to date"
        } catch (e: Exception) {
            reportError(ErrorCode.AB999, "Version comparison error", e)
            return "Unknown"
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
