package eu.kanade.tachiyomi.extension.en.hentai2read

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

/**
 * Springboard that accepts https://hentai2read.com/xxxx intents
 * and redirects them to the main Tachiyomi process.
 */
class Hentai2ReadActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pathSegments = intent?.data?.pathSegments.orEmpty()
        val type = pathSegments.firstOrNull()?.takeIf { it in setOf("doujinshi", "original", "imageset") }
        val idSegment = when {
            type != null -> pathSegments.getOrNull(1)
            pathSegments.firstOrNull() == "reader" -> pathSegments.getOrNull(1)
            else -> pathSegments.firstOrNull()
        }
        val id = idSegment?.substringBefore('-')?.takeIf { it.toLongOrNull() != null }

        if (id != null) {
            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.SEARCH"
                val typePrefix = type?.let { "$it:" }.orEmpty()
                putExtra("query", "${Hentai2Read.PREFIX_ID_SEARCH}$typePrefix$id")
                putExtra("filter", packageName)
            }

            try {
                startActivity(mainIntent)
            } catch (e: ActivityNotFoundException) {
                Log.e("Hentai2ReadActivity", e.toString())
            }
        } else {
            Log.e("Hentai2ReadActivity", "Could not parse URI from intent $intent")
        }

        finish()
        exitProcess(0)
    }
}
