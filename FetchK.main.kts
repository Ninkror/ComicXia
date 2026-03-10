import okhttp3.OkHttpClient
import okhttp3.Request

fun main() {
    val client = OkHttpClient()
    val request = Request.Builder()
        .url("https://www.comicxia.com/rank")
        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .build()
        
    client.newCall(request).execute().use { response ->
        val html = response.body?.string() ?: ""
        val regex = "<a[^>]+href=[\"'](/comics/\\d+)[\"'][^>]*>([\\s\\S]*?)</a>".toRegex()
        val matches = regex.findAll(html).take(5).toList()
        println("Matches found: \${matches.size}")
        matches.forEach { result ->
            val url = result.groupValues[1]
            val innerHtml = result.groupValues[2]
            
            val imgRegex = "<img[^>]+src=[\"']([^\"']+)[\"']".toRegex()
            val img = imgRegex.find(innerHtml)?.groupValues?.get(1)
            
            val altRegex = "alt=[\"']([^\"']+)[\"']".toRegex()
            val title = altRegex.find(innerHtml)?.groupValues?.get(1)
            
            println("Url: \$url, Img: \$img, Title: \$title")
        }
    }
}
