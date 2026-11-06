package cn.jinjingle.android

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class WebPageActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web)
        val url = intent.getStringExtra("url").orEmpty()
        if (!url.matches(Regex("https://[A-Za-z0-9.-]+(:[0-9]+)?([/?#].*)?")) || url.contains('\\')) {
            toast("网页地址无效，请使用 HTTPS 地址"); finish(); return
        }
        findViewById<TextView>(R.id.title).text = intent.getStringExtra("title")?.take(20) ?: "网页"
        findViewById<WebView>(R.id.web).apply {
            webViewClient = WebViewClient()
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            loadUrl(url)
        }
    }
    override fun onDestroy() { findViewById<WebView>(R.id.web).destroy(); super.onDestroy() }
}
