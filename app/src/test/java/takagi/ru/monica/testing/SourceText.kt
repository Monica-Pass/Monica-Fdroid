package takagi.ru.monica.testing

import java.io.File

/** Repository assertions must behave identically with Git LF and Windows CRLF checkouts. */
fun File.readSourceText(): String = readText(Charsets.UTF_8).replace("\r\n", "\n")
