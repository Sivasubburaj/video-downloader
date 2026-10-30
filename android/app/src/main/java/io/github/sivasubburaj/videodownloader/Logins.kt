package io.github.sivasubburaj.videodownloader

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import java.io.File

/**
 * Sign-ins for sites that hide some videos from logged-out visitors. The user signs in inside the
 * app's own web view (the app never sees the password); before each yt-dlp run, the cookies of the
 * signed-in sites, plus any imported cookies.txt, go into a temporary cookies.txt.
 *
 * YouTube isn't offered: Google blocks sign-in from embedded web views.
 */
object Logins {
    class Site(val name: String, val loginUrl: String, val domain: String, private val sessionCookie: String) {
        fun signedIn() = cookies(domain).any { it.first == sessionCookie }
    }

    val sites = listOf(
        Site("Instagram", "https://www.instagram.com/accounts/login/", "instagram.com", "sessionid"),
        Site("Facebook", "https://m.facebook.com/login/", "facebook.com", "c_user"),
        Site("X (Twitter)", "https://x.com/i/flow/login", "x.com", "auth_token"),
    )

    private fun cookies(domain: String): List<Pair<String, String>> {
        val header = runCatching { CookieManager.getInstance().getCookie("https://www.$domain") }.getOrNull()
        return header.orEmpty().split(";").mapNotNull { part ->
            val pieces = part.trim().split("=", limit = 2)
            if (pieces.size == 2 && pieces[0].isNotBlank()) pieces[0] to pieces[1] else null
        }
    }

    fun signOut(site: Site) {
        val manager = CookieManager.getInstance()
        val url = "https://www.${site.domain}"
        for ((name, _) in cookies(site.domain)) {
            manager.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=.${site.domain}")
            manager.setCookie(url, "$name=; Max-Age=0; Path=/")
        }
        manager.flush()
    }

    fun importedFile(context: Context) = File(context.filesDir, "imported-cookies.txt")

    fun import(context: Context, uri: Uri) {
        val input = context.contentResolver.openInputStream(uri) ?: return
        input.use { stream -> importedFile(context).outputStream().use { stream.copyTo(it) } }
    }

    /** A temporary Netscape cookies.txt for yt-dlp, or null if the user hasn't signed in anywhere. */
    fun cookieFile(context: Context): File? {
        val expires = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        val lines = mutableListOf<String>()
        for (site in sites.filter { it.signedIn() }) {
            for ((name, value) in cookies(site.domain)) {
                lines += listOf(".${site.domain}", "TRUE", "/", "TRUE", "$expires", name, value).joinToString("\t")
            }
        }
        // keep "#HttpOnly_" lines: they are cookies, not comments
        lines += importedFile(context).takeIf { it.exists() }?.readLines().orEmpty()
            .filter { it.isNotBlank() && (!it.startsWith("#") || it.startsWith("#HttpOnly_")) }
        if (lines.isEmpty()) return null
        return File.createTempFile("cookies", ".txt", context.cacheDir).apply {
            writeText((listOf("# Netscape HTTP Cookie File") + lines).joinToString("\n", postfix = "\n"))
        }
    }
}
