package com.example.xraycore

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.Properties

/** Extract only the two packaged rule sets, once per content revision, before native startup. */
internal object CoreRoutingAssets {
    fun directory(context: Context): File {
        val manifest = Properties().apply {
            context.assets.open("routing-assets.properties").use { load(it) }
        }
        val names = listOf("geoip", "geosite")
        val revision = names.joinToString("-") { manifest.getProperty("$it.sha256").take(16) }
        val directory = File(context.filesDir, "xray-routing-$revision")
        check(directory.isDirectory || directory.mkdirs()) { "Unable to prepare routing assets" }
        for (name in names) {
            val size = manifest.getProperty("$name.size").toLong()
            val destination = File(directory, "$name.dat")
            if (destination.isFile && destination.length() == size) continue
            val temporary = File(directory, "$name.tmp")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                context.assets.open("$name.dat").use { source ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(temporary.length() == size && actual == manifest.getProperty("$name.sha256")) {
                    "Invalid packaged routing asset"
                }
                check(temporary.renameTo(destination)) { "Unable to install routing asset" }
            } finally {
                temporary.delete()
            }
        }
        return directory
    }
}
