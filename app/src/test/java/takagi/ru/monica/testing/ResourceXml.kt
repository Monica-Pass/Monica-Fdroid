package takagi.ru.monica.testing

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** Resource assertions should inspect values rather than incidental XML attribute order. */
fun String.styleItem(styleName: String, itemName: String): String? {
    val factory = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }
    val styles = factory.newDocumentBuilder().parse(InputSource(StringReader(this))).getElementsByTagName("style")
    val style = (0 until styles.length).map { styles.item(it) as Element }
        .firstOrNull { it.getAttribute("name") == styleName } ?: return null
    val items = style.getElementsByTagName("item")
    return (0 until items.length).map { items.item(it) as Element }
        .firstOrNull { it.getAttribute("name") == itemName }?.textContent?.trim()
}
