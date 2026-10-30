const fs = require('fs')
const p = 'E:/DeepSeek workers/otsmail-android/app/build.gradle.kts'
let s = fs.readFileSync(p, 'utf8')
const line = 'implementation("androidx.compose.material:material-icons-core")'
if (s.includes('material-icons-extended')) {
  console.log('already')
} else if (s.includes(line)) {
  s = s.replace(line, line + '\n    implementation("androidx.compose.material:material-icons-extended")')
  fs.writeFileSync(p, s)
  console.log('added')
} else {
  console.log('anchor not found')
}
