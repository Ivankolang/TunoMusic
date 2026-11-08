package com.ivankolang.tunomusic

import android.graphics.Color as AndroidColor
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

/** Dedicated Sefon browser surface; it never reuses or replaces SoundCloud's WebView. */
@Composable
internal fun SefonWebView(
    query: String,
    onQueryConsumed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val latestConsumed by rememberUpdatedState(onQueryConsumed)
    var pageReady by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier.fillMaxSize().clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF080B0A))
            .border(1.dp, Color(0xFF26352C), RoundedCornerShape(20.dp)),
        factory = { context ->
            WebView(context).apply {
                tag = "sefon.org"
                setBackgroundColor(AndroidColor.rgb(8, 11, 10))
                overScrollMode = android.view.View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.loadsImagesAutomatically = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        if (!url.startsWith("https://sefon.org")) return
                        pageReady = true
                        // Keep the site's own scripts and resources intact: blocking ad-network
                        // hosts at WebView level can also block assets required by search/results.
                        view.evaluateJavascript("""
                            (function(){
                              var css=":root{color-scheme:dark!important}html,body{background:#080b0a!important;color:#f4fff7!important;font-family:Roboto,Arial,sans-serif!important}body{padding:8px!important;overflow-x:hidden!important}header,.header,.navbar,.top-menu,footer,.footer,.site-footer{display:none!important}a{color:#9be7b1!important;text-decoration:none!important}article,section,li,.item,.track,.song,.card,.music-item,[class*=track],[class*=song],[class*=music],[class*=album],[class*=result]{background:#151b18!important;color:#f4fff7!important;border:1px solid #2c4936!important;border-radius:15px!important}input,select,textarea{max-width:100%!important;background:#111915!important;color:#fff!important;border:1px solid #385440!important;border-radius:13px!important;padding:11px!important}button,input[type=submit]{background:#244b32!important;color:#f4fff7!important;border:1px solid #477653!important;border-radius:13px!important;padding:10px 15px!important}[class*=advert],[id*=advert],[class*=ad-banner],[id*=ad-banner],[class*=adsbygoogle],.adsbygoogle,[data-ad],[data-ad-slot],[class*=sponsor],[id*=sponsor],iframe[src*=doubleclick],iframe[src*=googlesyndication],iframe[src*=googleadservices],iframe[src*=taboola],iframe[src*=outbrain]{display:none!important;visibility:hidden!important;height:0!important;pointer-events:none!important}";
                              var s=document.getElementById('tuno-sefon-style');if(!s){s=document.createElement('style');s.id='tuno-sefon-style';document.head.appendChild(s);}s.textContent=css;
                              function hideAds(){document.querySelectorAll('[id],[class],[data-ad],iframe').forEach(function(el){var id=(el.id||'').toLowerCase(),cl=(typeof el.className==='string'?el.className:'').toLowerCase(),src=(el.getAttribute('src')||'').toLowerCase();if(/advert|ad-banner|adsbygoogle|sponsor|taboola|outbrain|doubleclick|googlesyndication|googleadservices/.test(id+' '+cl+' '+src))el.style.setProperty('display','none','important');});}
                              hideAds();if(window.__tunoSefonObserver)window.__tunoSefonObserver.disconnect();window.__tunoSefonObserver=new MutationObserver(hideAds);window.__tunoSefonObserver.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['class','id','src']});
                            })();
                        """.trimIndent(), null)
                    }
                }
                loadUrl("https://sefon.org/")
            }
        },
        update = { web ->
            if (pageReady && query.isNotBlank() && web.tag != "submitted:$query") {
                submitSefonSearch(web, query.trim()) { latestConsumed() }
            }
        }
    )
}

private fun submitSefonSearch(web: WebView, query: String, onSubmitted: () -> Unit) {
    web.tag = "submitted:$query"
    val script = "(function(){var q=" + JSONObject.quote(query) + ";" +
        "var a=[].slice.call(document.querySelectorAll('input')).filter(function(x){return x.offsetWidth>0&&x.offsetHeight>0;});" +
        "var el=a.find(function(x){return x.type==='search'||/поиск|песня|исполнитель|трек/i.test((x.placeholder||'')+' '+(x.name||'')+' '+(x.getAttribute('aria-label')||''));})||a.find(function(x){return x.type==='text';});" +
        "if(!el)return false;el.focus();el.value=q;el.dispatchEvent(new Event('input',{bubbles:true}));" +
        "el.dispatchEvent(new Event('change',{bubbles:true}));var f=el.form;" +
        "if(f){if(f.requestSubmit)f.requestSubmit();else f.submit();}" +
        "else{el.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));el.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));}return true;})();"
    web.evaluateJavascript(script) { result -> if (result == "true") onSubmitted() }
}
