package app.freevpn.core

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Читает не более max байт. Замена InputStream.readNBytes, которого нет на Android ниже 13. */
fun InputStream.readUpTo(max: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0
    while (total < max) {
        val n = read(buf, 0, minOf(buf.size, max - total))
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
    }
    return out.toByteArray()
}
