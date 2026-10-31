// 读信页正文横向被裁：页面比 WebView 宽时按比例缩到视口宽度，再量高度。
const fs = require('fs')
const p = 'app/src/main/java/com/otscup/otsmail/ui/DetailScreen.kt'
let s = fs.readFileSync(p, 'utf8')
const old = `                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    // 用 contentHeight 而不是注入 JS：正文里 JS 是关着的（安全考虑）
                                    val px = view?.contentHeight?.toFloat() ?: 0f
                                    if (px > 0f) {
                                        bodyH = (px / density.density + 8f).coerceIn(120f, 720f).dp
                                    }
                                }
                            }`
if (!s.includes(old)) { console.log('MISS webViewClient block'); process.exit(1) }
const neu = `                            var scaled = false
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    val v = view ?: return
                                    // 页面比视口宽时先按比例缩到宽度，再量高度（正文 JS 关着，不能用脚本量）
                                    val cw = v.contentWidth
                                    if (!scaled && cw > 0 && v.width > 0 && cw > v.width) {
                                        scaled = true
                                        v.setInitialScale((v.width * 100) / cw)
                                        v.postDelayed({ measure(v) }, 120)
                                        return
                                    }
                                    measure(v)
                                }

                                private fun measure(v: WebView) {
                                    val px = v.contentHeight.toFloat()
                                    if (px > 0f) bodyH = (px / density.density + 8f).coerceIn(120f, 2400f).dp
                                }
                            }`
s = s.replace(old, neu)
fs.writeFileSync(p, s, 'utf8')
console.log('patched webViewClient')
