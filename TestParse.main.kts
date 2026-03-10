import org.jsoup.Jsoup
import java.io.File

fun main() {
    val file = File("rank2.html")
    val doc = Jsoup.parse(file, "UTF-16LE")
    val links = doc.select("a[href^=/comics/]")
    println("Found \${links.size} comics links.")
    links.take(5).forEach { link ->
        println("Link: \${link.attr("href")}, Text: \${link.text()}")
        // Try finding image
        val img = link.selectFirst("img")
        println("  -> Image: \${img?.attr("src") ?: img?.attr("data-src")}")
    }
}
