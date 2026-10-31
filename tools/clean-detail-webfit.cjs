// 去掉上一步没生效的「按宽度缩放」死代码，只留高度测量。
const fs = require('fs')
const p = 'app/src/main/java/com/otscup/otsmail/ui/DetailScreen.kt'
let s = fs.readFileSync(p, 'utf8')
const start = s.indexOf('                            var scaled = false')
const end = s.indexOf('                        }\n                    },', start)
if (start < 0 || end < 0) { console.log('MISS block'); process.exit(1) }
const replacement = `                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    val v = view ?: return
                                    // 正文 JS 关着，只能用 contentHeight 量高度；高度上限按屏幕剩余空间夹取
                                    val px = v.contentHeight.toFloat()
                                    if (px > 0f) bodyH = (px / density.density + 8f).coerceIn(120f, 2400f).dp
                                }
                            }
`
s = s.slice(0, start) + replacement + s.slice(end)
fs.writeFileSync(p, s, 'utf8')
console.log('cleaned')
