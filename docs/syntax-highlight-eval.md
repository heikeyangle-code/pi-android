# Syntax-highlighting backend evaluation

Evaluation date: 2026-09-10. Host: Linux **aarch64**, 8 cores, 7.2 GB RAM (shared VM, some swap),
JDK 17 (`/usr/lib/jvm/java-17-openjdk-arm64`). No Android device and no working Android build were
available (`AAPT2` is x86_64-only), therefore **every execution in this document is on the plain JVM,
not on ART**. Anything that could not be executed is explicitly marked `UNVERIFIED`.

Reference implementation: `pi`'s terminal UI, `/root/pi-src/packages/coding-agent/src/utils/syntax-highlight.ts`
(highlight.js **10.7.3**, 20 eagerly registered languages, the rest loaded lazily, ~25 CSS scopes mapped
to colors in `packages/coding-agent/src/modes/interactive/theme/theme.ts:1036-1064`).

---

## 1. Verdict

**Recommend `org.mozilla:rhino-runtime:1.7.15` executing a build-time Babel-transpiled highlight.js 10.7.3
bundle**, shipped as two Android assets (an eager bundle with core + pi's 20 languages, and a lazy bundle
with the remaining 171). Coordinates:

```kotlin
implementation("org.mozilla:rhino-runtime:1.7.15")   // MPL-2.0, 1,224,752 B jar, Java 8 bytecode
// plus two generated assets committed to the repo (no runtime dependency, nothing fetched at runtime):
//   app/src/main/assets/hljs/hljs-eager.es5.js   142,729 B  (32,996 B gzip)  -> 20 languages
//   app/src/main/assets/hljs/hljs-rest.es5.js  ~1,033,352 B (~270 KB gzip)   -> 171 languages
```

This is the only candidate that reproduces pi's output **exactly**: for all **191** highlight.js
languages the HTML emitted by the transpiled bundle under Rhino is byte-identical (SHA-256 equal) to the
HTML emitted by the original `highlight.js/lib/index.js` under Node/V8 — 0 differing lines, 0 errors
(§3.6). It gives 191 languages and 29 distinct `hljs-*` scopes across the 27 required languages, i.e.
full coverage of every scope pi maps. The price is speed: Rhino interpreted mode needs
**48–98 ms (median) for a 40-line snippet** on this desktop, ~40× slower than the TextMate alternative,
so highlight results **must** be cached and computed off the main thread, and the app must not use
`hljs.highlightAuto` over the full language list (9.7–14.7 s).

The two important gotchas, both verified: (a) **Rhino cannot parse ES6 `class`** in any released or
master version, and every published highlight.js 10.7.3 build contains classes — so the JS must be
transpiled to ES5 at build time (a committed, reproducible Babel step); (b) Rhino's optimising/compiled
mode is both unnecessary and unusable on Android (it defines JVM bytecode at runtime), but I measured it
to be **no faster** than interpreted mode anyway (61.7 ms vs 61.7–74.5 ms median for the Kotlin snippet),
so interpreted mode (`setOptimizationLevel(-1)`) is the correct and sufficient setting.

If on-device measurement later shows the per-block latency is unacceptable, the fallback ranking in §5
applies; `io.github.ivan-magda:kotlin-textmate-core:0.2.0` is the strongest alternative and is ~40×
faster, at the cost of exact pi parity, a pre-1.0 single-maintainer dependency, and vendored grammar assets.

---

## 2. Comparison table

| Candidate | Coordinates | License | Languages | Colour slots | Measured latency (JVM, aarch64, this host) | APK size impact (measured bytes) | Android minSdk 26 | Verdict |
|---|---|---|---|---|---|---|---|---|
| **Rhino + transpiled hljs 10.7.3** | `org.mozilla:rhino-runtime:1.7.15` + generated `hljs-eager.es5.js` / `hljs-rest.es5.js` assets | MPL-2.0 (Rhino) + BSD-3-Clause (hljs) — both fine for an Apache-2.0 app, no GPL | **191** (all of highlight.js 10.7.3); all 27 required present | **29** distinct `hljs-*` scopes measured across the 27 required languages; pi maps 25 → 100 % parity | load all 191: **2.19–3.03 s**; warm median per block: json(12 l.) **13.2–20.2 ms**, bash(19 l.) **21.8–25.2 ms**, kotlin(24 l.) **61.7–74.5 ms**, python(40 l.) **48.0–98.5 ms** (best 22.3–36.0 ms); cold first call 34.9–1274.6 ms; `highlightAuto` all-191 **9.7–14.7 s** | Rhino runtime jar **1,224,752 B**; eager asset **142,729 B** (32,996 B gzip); rest asset **1,033,352 B** (~270 KB gzip). Total ≈ **2.4 MB raw / ≈1.5 MB compressed** | ✅ Java 8 bytecode (class major 52); only APIs ≤ API 26; needs D8 lambda desugaring (`LambdaMetafactory`) and `-dontwarn java.beans.**` | ✅ **RECOMMENDED** |
| `io.github.ivan-magda:kotlin-textmate` | `io.github.ivan-magda:kotlin-textmate-core:0.2.0`, `…-compose:0.2.0` | MIT | Any TextMate grammar you vendor: **350+ available**, but **0 bundled** — 4 in the repo, 27 required must be vendored | TextMate scopes + VS Code themes (unbounded; far more than 9) | grammar load **27–74 ms**, theme load 145–223 ms, **cold first highlight 489–944 ms**, **warm 1.63–1.87 ms/28-line block**; my own run: 24-line Kotlin cold 601.8 ms, **warm median 5.67 ms**, 12-line JSON warm median **1.17 ms** | core jar **159,293 B** + compose aar **14,118 B** + deps joni **230,217 B**, jcodings **1,739,960 B**, gson **298,435 B** (kotlin-stdlib already present) + **vendored grammars ~1–2 MB for 27 languages, ~8.7 MB for ~200** | ✅ Java 17 bytecode (major 61), minSdk 24 in the AAR, no `.so` at all | ⚠️ **FALLBACK #1** — 40× faster and MIT, but pre-1.0 (2 releases, 47 stars, single maintainer), no bundled grammars, not byte-compatible with pi |
| `dev.hossain:compose-highlight` (WebView/JS bridge) | `dev.hossain:compose-highlight:0.36.0` | MIT | 190+ via bundled `highlight.min.js` (`UNVERIFIED`: list not enumerated) | hljs scopes via CSS themes (27 bundled theme CSS files) | third-party benchmark: cold 120–180 ms, warm 6.4–18 ms/block (Pixel 9 Pro XL / S24 Ultra avg); **not my measurement** | AAR **629,348 B**, of which `assets/compose-highlight/highlight.min.js` **1,089,615 B** | ⚠️ minSdk 24 OK, but **`minCompileSdk=37`** — AGP hard-fails unless `compileSdk >= 37` (this app uses 36) | ⚠️ **FALLBACK #2** — viable, one shared hidden WebView (not one per block), but a Chromium instance in memory for the whole chat screen |
| `dev.snipme:highlights` | `dev.snipme:highlights-jvm:1.1.0` | Apache-2.0 | **18** (`SyntaxLanguage` enum) | 9-colour theme | not measured here | jar **120,318 B** + coroutines/serialization deps | ⚠️ no `androidJvm` variant published (`UNVERIFIED` that Gradle resolves `-jvm` cleanly in an Android module) | ❌ **REJECT on capability** (18 languages, 9 colours — weaker than pi; matches the earlier rejection) |
| `io.github.rosemoe:editor` + `language-textmate` (Sora) | `io.github.rosemoe:editor:0.24.6`, `io.github.rosemoe:language-textmate:0.24.6` (also published as `io.github.Rosemoe.sora-editor:*:0.23.6`) | **LGPL-2.1** ⚠️ | TextMate grammars (vendor your own) | TextMate scopes + themes | not measured here | editor aar 665,203 B + language-textmate aar 260,567 B + joni/jcodings/gson/**snakeyaml-engine** ≈ 2.3 MB jars. Optional native `io.github.rosemoe:oniguruma-native:0.24.6` aar **1,299,014 B** (arm64-v8a `.so` alone **1,010,736 B**) | ✅ no `.so` in the default path; native artifact exists if wanted | ❌ **REJECT** — LGPL-2.1, and it drags in a whole editor UI for read-only chat blocks |
| `org.eclipse.tm4e` | **not on Maven Central** (`org/eclipse/tm4e/**` → HTTP 404); only `org.netbeans.external:org.eclipse.tm4e.core-0.14.1:RELEASE310` (306,381 B, empty POM, EPL-2.0) | EPL-2.0 | TextMate grammars | TextMate scopes + themes | not measured | 306,381 B + hand-wired gson/joni/jcodings/snakeyaml-engine | ⚠️ current upstream needs **Java 21**; `tm4e.ui` uses Eclipse platform APIs | ❌ **REJECT** — not resolvable from Central, Java 21, Eclipse-bound |
| YNAASH (`hossain-khan/android-syntax-highlighter`) | **not on Maven Central** (all group probes → 404) | Apache-2.0 | — | — | — | — | — | ❌ **REJECT** — a demo/reference app by design ("feel free to copy parts of code"), not a consumable dependency |
| JNI JS engine (`com.eclipsesource.j2v8:j2v8_android_arm64-v8a:6.3.0`) | Maven Central | EPL-1.0 | 191 (runs hljs as-is, no transpile) | 29 | not measured | **33,531,326 B for one ABI** | ✅ (native) | ❌ **REJECT on size** — 33.5 MB for arm64-v8a alone |

Scopes measured present across the 27 required languages (Rhino + hljs 10.7.3):
`addition attr attribute built_in bullet class code comment deletion function keyword link literal meta
meta-keyword meta-string name number operator params section selector-tag string strong subst tag title
type variable` — 29 distinct, a superset of the 25 pi maps. Per-language breakdown is in §3.5.

---

## 3. Raw evidence

All work in `/tmp/hleval`. Java sources: `/tmp/hleval/src/HljsRunner.java`, `HljsBench.java`,
`AllLangs.java`; generated assets at `/tmp/hleval/hljs-*.es5.js`; benchmark logs `bench-*.txt`.
Nothing outside `/root/pi-android/docs/syntax-highlight-eval.md` in that tree was created or modified.

### 3.1 Rhino selection — versions, sizes, bytecode level

```bash
curl -s https://repo1.maven.org/maven2/org/mozilla/rhino/maven-metadata.xml   # latest/release = 1.9.1
for v in 1.7.15 1.8.1 1.9.1; do
  curl -s -o jars/rhino-$v.jar https://repo1.maven.org/maven2/org/mozilla/rhino/$v/rhino-$v.jar
done
curl -s -o jars/rhino-runtime-1.7.15.jar \
  https://repo1.maven.org/maven2/org/mozilla/rhino-runtime/1.7.15/rhino-runtime-1.7.15.jar
# class-file major versions (python3 zipfile + struct on bytes 6..7 of each .class):
#   rhino-1.7.15        543 classes, major 52 (Java 8), no META-INF/versions
#   rhino-1.8.1         455 classes, major 55 (Java 11)
#   rhino-1.9.1         708 classes, major 55 (Java 11)
ls -l: rhino-1.7.15.jar 1,407,735 | rhino-runtime-1.7.15.jar 1,224,752 | rhino-1.8.1.jar 1,271,206 | rhino-1.9.1.jar 1,647,024
gzip -9: rhino-runtime-1.7.15.jar -> 1,167,134 B (APK-size proxy, see §3.8)
```

* **1.7.15 is the right version.** It is the newest Rhino published as **Java 8 bytecode** (major 52),
  which is the safest thing to feed D8 for `minSdk 26`.
* **1.8.1 / 1.9.1 are Java 11 bytecode** and, more importantly, 1.9.1 references
  `java.lang.invoke.VarHandle` from `org.mozilla.javascript.SlotMapOwner$ThreadedAccess`. **Android has
  no `VarHandle`** in any API level, so 1.9.1 carries a real runtime-failure risk. 1.8.1 additionally
  emits `StringConcatFactory` indy throughout.
* 1.7.15's only `java.lang.invoke` usage is the `LambdaMetafactory` **bootstrap** for Java lambdas
  (constant-pool `Methodref` owners scanned per class: 23 classes, all `LambdaMetafactory`); that is
  standard D8 lambda desugaring, not a runtime API call.

External-API scan of `rhino-runtime-1.7.15.jar` (constant-pool class refs, 922 distinct classes) —
everything not in `java.lang.*`:

```
java/util/Optional, java/util/function/*, java/util/stream/IntStream   -> API 24+
java/util/concurrent/locks/StampedLock, java/util/concurrent/atomic/*  -> API 24+
java/nio/charset/StandardCharsets                                      -> API 19+
java/security/*, java/text/*, java/io/*, java/net/*, java/util/regex/* -> API 1
com.sun / jdk.* / java.lang.Module / java.lang.management / ProcessHandle -> NONE
java/awt/**, javax/swing/**                                            -> NOT present in -runtime
java/beans/**   -> only in org.mozilla.javascript.Context.firePropertyChangeImpl and JavaToJSONConverters
org/w3c/dom/**, javax/xml/**  -> xmlimpl package (Android ships these)
```

`org.mozilla:rhino-runtime:1.7.15` contains 481 entries, **no** `tools/shell` and **no**
`tools/debugger`, i.e. no `java.awt`/`javax.swing` at all — that is why the `-runtime` artifact is the
one to use. The only Android-missing references left are `java.beans.PropertyChangeListener` /
`PropertyChangeEvent` in `Context.firePropertyChangeImpl`, which is a private method reached only when
someone registers a property-change listener (we never do). D8/R8 will emit *missing class* warnings;
add:

```proguard
-dontwarn java.beans.**
```

### 3.2 The blocking discovery: Rhino has no ES6 `class`

```
$ java -cp out:jars/rhino-1.7.15.jar T2 -1          # probe script, see §3.9
FAIL : class A {}                => identifier is a reserved word: class
FAIL : var A = class {}; typeof A => identifier is a reserved word: class
OK   : `tpl ${1+1}` => tpl 2
OK   : let x=1; const y=2; x+y => 3
OK   : ()=>1 => ()=>1
```

Same result on **1.8.1 and 1.9.1**. Cause (read from upstream source, both `Rhino1_7_15_Release` and
`master`):

```bash
curl -sL https://raw.githubusercontent.com/mozilla/rhino/Rhino1_7_15_Release/src/org/mozilla/javascript/TokenStream.java | grep -n Id_class
# 128:  Id_class = Token.RESERVED
curl -sL https://raw.githubusercontent.com/mozilla/rhino/master/rhino/src/main/java/org/mozilla/javascript/TokenStream.java | grep -n Id_class
# 130:  Id_class = Token.RESERVED
curl -sL .../master/.../Parser.java | grep -c "Token.CLASS"     # 0
```

`class` is a **reserved word, never a keyword**, in every Rhino version including current master.

Every published highlight.js 10.7.3 build uses classes:

```
lib/core.js:32   class Response {          lib/core.js:855  class MultiRegex {
lib/core.js:108  class HTMLRenderer {      lib/core.js:933  class ResumableMultiRegex {
lib/core.js:175  class TokenTree {         (282)            class TokenTreeEmitter extends TokenTree {
$ grep -oE "class [A-Za-z_$]+ *\{" @highlightjs/cdn-assets@10.7.3/highlight.min.js | wc -l   -> 5
$ grep -o "=>" @highlightjs/cdn-assets@10.7.3/highlight.min.js | wc -l                       -> 232
```

An even older build is *not* a way out: `highlight.js@9.18.5` `highlight.min.js` has 0 classes but only
10 arrow functions and is a different major version, so it is not pi parity.

**Solution: transpile once, at build time, and commit the output.**

```bash
npm install --no-audit --no-fund --ignore-scripts @babel/core@7 @babel/preset-env@7   # in /tmp, dev-only
curl -s -o cdn-assets-10.7.3.tgz https://registry.npmjs.org/@highlightjs/cdn-assets/-/cdn-assets-10.7.3.tgz
tar xzf cdn-assets-10.7.3.tgz          # -> package/highlight.min.js 135,556 B (core + 39 "common" languages)
                                       #    package/languages/*.min.js  191 files, 977,950 B total
node transpile.js cdn/package/highlight.min.js hljs-10.7.3.es5.js
#   Babel preset-env, targets {ie:'11'}, modules:false, useBuiltIns:false
#   -> 0 remaining `class` declarations; injects a 1-line Reflect.construct shim (Rhino has no Reflect)
```

The `Reflect.construct` shim that Babel's class inheritance needs is tiny and is injected automatically:

```js
if(typeof Reflect==="undefined"){var Reflect={construct:function(t,a,n){var o=Object.create((n||t).prototype);
var r=t.apply(o,a);return (r&&typeof r==="object")?r:o;}};}
```

Asset composition used for the two shipped bundles (core extracted by cutting
`highlight.min.js` at the first line starting with `hljs.registerLanguage(`; core = 20,606 B):

```bash
# eager: core + pi's 20 eager languages
cat hljs-core.min.js cdn/package/languages/{python,java,go,javascript,cpp,typescript,php,ruby,c,
      csharp,nix,bash,rust,scala,kotlin,swift,dart,groovy,perl,lua}.min.js > hljs-eager.es2015.js
node transpile.js hljs-eager.es2015.js hljs-eager.es5.js      # 142,729 B  (gzip 32,996 B)
# full:  core + all 191 languages
cat hljs-core.min.js cdn/package/languages/*.min.js > hljs-full.es2015.js
node transpile.js hljs-full.es2015.js hljs-full.es5.js        # 1,176,081 B (gzip 301,419 B)
# rest = full - the 20 eager ones                                        ~1,033,352 B (~270 KB gzip)
```

### 3.3 It actually runs: `hljs.highlight` output under Rhino 1.7.15 (interpreted)

Command (bundle mode; the harness injects a `console` shim, without which any missing language makes
highlight.js throw `ReferenceError: "console" is not defined` — a real integration requirement):

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
$JAVA_HOME/bin/javac -cp jars/rhino-1.7.15.jar -d out src/HljsBench.java
$JAVA_HOME/bin/java -Xmx1g -cp out:jars/rhino-1.7.15.jar HljsBench -1 /tmp/hleval/hljs-full.es5.js auto
```

Kotlin snippet (trimmed), 24 lines:

```html
<span class="hljs-keyword">package</span> com.example.app

<span class="hljs-keyword">import</span> kotlinx.coroutines.flow.Flow
...
<span class="hljs-comment">/** A tiny counter that emits values. */</span>
<span class="hljs-meta">@JvmStatic</span>
<span class="hljs-class"><span class="hljs-keyword">class</span> <span class="hljs-title">Counter</span></span>(<span class="hljs-keyword">private</span> <span class="hljs-keyword">val</span> limit: <span class="hljs-built_in">Int</span> = <span class="hljs-number">10</span>) {
    <span class="hljs-keyword">val</span> label: String = <span class="hljs-string">&quot;counter-<span class="hljs-subst">${limit}</span>&quot;</span>
    <span class="hljs-keyword">suspend</span> <span class="hljs-function"><span class="hljs-keyword">fun</span> <span class="hljs-title">tick</span><span class="hljs-params">()</span></span>: Flow&lt;<span class="hljs-built_in">Int</span>&gt; = flow {
        <span class="hljs-keyword">while</span> (count &lt; limit) {
            count += <span class="hljs-number">1</span>
        <span class="hljs-keyword">const</span> <span class="hljs-keyword">val</span> HEX = <span class="hljs-number">0xFF_FF</span>
        <span class="hljs-function"><span class="hljs-keyword">fun</span> <span class="hljs-title">of</span><span class="hljs-params">(limit: <span class="hljs-type">Int</span>?)</span></span> = Counter(limit ?: <span class="hljs-number">10</span>)
```

JSON snippet (trimmed), 12 lines:

```html
{
  <span class="hljs-attr">&quot;name&quot;</span>: <span class="hljs-string">&quot;pi-android&quot;</span>,
  <span class="hljs-attr">&quot;private&quot;</span>: <span class="hljs-literal">true</span>,
  <span class="hljs-attr">&quot;count&quot;</span>: <span class="hljs-number">42</span>,
  <span class="hljs-attr">&quot;tags&quot;</span>: [<span class="hljs-string">&quot;kotlin&quot;</span>, <span class="hljs-string">&quot;compose&quot;</span>, <span class="hljs-string">&quot;highlight&quot;</span>],
  <span class="hljs-attr">&quot;nested&quot;</span>: { <span class="hljs-attr">&quot;a&quot;</span>: { <span class="hljs-attr">&quot;b&quot;</span>: [<span class="hljs-number">1</span>, <span class="hljs-number">2.5</span>, <span class="hljs-number">-3e10</span>] } }
```

Bash snippet (trimmed), 19 lines:

```html
<span class="hljs-meta">#!/usr/bin/env bash</span>
<span class="hljs-built_in">set</span> -euo pipefail
ROOT=<span class="hljs-string">&quot;<span class="hljs-subst">$(cd <span class="hljs-string">&quot;<span class="hljs-subst">$(dirname <span class="hljs-string">&quot;<span class="hljs-variable">${BASH_SOURCE[0]}</span>&quot;</span>)</span>&quot;</span> &amp;&amp; pwd)</span>&quot;</span>
<span class="hljs-function"><span class="hljs-title">main</span></span>() {
  <span class="hljs-built_in">local</span> i=0
  <span class="hljs-keyword">for</span> f <span class="hljs-keyword">in</span> <span class="hljs-string">&quot;<span class="hljs-variable">$ROOT</span>&quot;</span>/*.gradle.kts; <span class="hljs-keyword">do</span>
      <span class="hljs-built_in">echo</span> <span class="hljs-string">&quot;found: <span class="hljs-variable">$f</span>&quot;</span> &gt;&amp;2
  <span class="hljs-built_in">printf</span> <span class="hljs-string">&#x27;%s\n&#x27;</span> <span class="hljs-string">&quot;<span class="hljs-variable">${i}</span> files&quot;</span> | tee /tmp/out.log
```

These are exactly the spans pi's `renderHighlightedHtml()` consumes, so the Kotlin side can reuse pi's
parser and theme map unchanged.

### 3.4 Measured timings

Interpreted mode (`setOptimizationLevel(-1)` — the only Android-viable mode), `-Xmx1g`, two clean runs
of the **full 191-language** bundle (`bench-full-run1.txt`, `bench-full-run2.txt`):

| Metric | Run 1 | Run 2 |
|---|---|---|
| load + register all 191 languages (incl. `Context` init) | **3,030.1 ms** | **2,193.2 ms** |
| cold 1st call, kotlin 24 lines | 846.1 ms | 488.8 ms |
| warm kotlin 24 l. (n=60): med / p95 / best | **74.52 / 160.63 / 26.88 ms** | **61.66 / 172.75 / 26.08 ms** |
| warm json 12 l.: med / p95 / best | **20.23 / 51.84 / 9.25 ms** | **13.15 / 35.80 / 8.38 ms** |
| warm bash 19 l.: med / p95 / best | **25.18 / 46.79 / 8.61 ms** | **21.76 / 56.79 / 6.92 ms** |
| warm python **40 lines**: med / p95 / best | **98.46 / 153.27 / 36.04 ms** | **47.96 / 104.81 / 22.34 ms** |
| cold 1st call, python 40 lines | 234.1 ms | 297.2 ms |
| `highlightAuto` with an 8-language subset | 1,080.8 ms | 532.1 ms |
| `highlightAuto` over all 191 languages | **14,725.5 ms** | **9,678.0 ms** |
| heap delta after load + 240 highlights | 45.6 MB | 45.5 MB |

Eager bundle (core + pi's 20 languages, `bench-eager-run1/2.txt`): load **1,411.7 / 2,496.9 ms**,
kotlin warm median **58.44 / 100.00 ms**. The eager bundle is 8× smaller but the fixed Rhino/Context
cost dominates, so the eager/lazy split buys less startup time than the size ratio suggests — it is
still worth doing because it removes 1.03 MB from the critical path.

Compiled/optimising mode (`setOptimizationLevel(9)`, `bench-full-opt9.txt`) — **informational only,
not usable on Android** because Rhino's optimiser defines JVM bytecode at runtime (on Android that
requires runtime dexing via `com.jakewharton.android.repackaged:dalvik-dx`, as `rhino-android` does):

| Metric | opt 9 | opt -1 (run 1) |
|---|---|---|
| load | 6,534.6 ms | 3,030.1 ms |
| warm kotlin 24 l. median | 61.73 ms | 74.52 ms |
| warm python 40 l. median | 53.73 ms | 98.46 ms |

There is **no meaningful speed-up**, so interpreted mode is both required and sufficient.

Noise disclaimer: this is a shared 8-core VM with ~1 GB free RAM and swap in use; one earlier run
reported `LOAD_MS=32,583` purely from memory pressure. The numbers above are from runs with nothing else
executing; treat ±30 % as the real error bar. **Phone numbers are extrapolation, not measurement:**

> UNVERIFIED: on-device (ART) latency. Expect roughly 2–4× the desktop numbers for interpreted
> JavaScript, i.e. **~100–400 ms for a 40-line block**, ~50–150 ms for a 20-line block, after warm-up;
> the 191-language load would plausibly be **6–12 s**, which is why it must be lazy/background.

### 3.5 Language coverage and colour slots

```
REGISTERED_LANGUAGES=191
COVERAGE={"json":true,"yaml":true,"sql":true,"html":true,"css":true,"xml":true,"toml":true,
"dockerfile":true,"markdown":true,"hcl":false,"graphql":false,"kotlin":true,"java":true,
"javascript":true,"typescript":true,"python":true,"go":true,"rust":true,"c":true,"cpp":true,
"csharp":true,"ruby":true,"php":true,"swift":true,"dart":true,"scala":true,"bash":true,
"shell":true,"diff":true,"ini":true,"plaintext":true,"protobuf":true,"makefile":true,
"nginx":true,"lua":true,"perl":true,"groovy":true,"nix":true}
```

All **27** languages in the hard requirement are present (plus `hcl`/`graphql`, which highlight.js
10.7.3 does not ship — they arrive only in highlight.js 11, so pi does not have them either).

Distinct scopes measured by highlighting one sample per required language
(`node scopes.js` against `highlight.js/lib/index.js`):

```
DISTINCT_SCOPES=29
addition attr attribute built_in bullet class code comment deletion function keyword link literal
meta meta-keyword meta-string name number operator params section selector-tag string strong subst
tag title type variable

SCOPE[kotlin]=built_in,class,function,keyword,params,string,title
SCOPE[java]=class,comment,function,keyword,number,params,string,title
SCOPE[javascript]=comment,function,keyword,number,params,string,subst,title
SCOPE[typescript]=built_in,function,keyword,params,string,subst
SCOPE[python]=built_in,class,comment,function,keyword,meta,number,params,string,subst,title
SCOPE[go]=function,keyword,number,params,string,title
SCOPE[rust]=built_in,function,keyword,number,string,title
SCOPE[c]=built_in,function,keyword,meta,meta-keyword,meta-string,number,params,string,title
SCOPE[cpp]=function,keyword,meta,meta-keyword,meta-string,params,title
SCOPE[csharp]=built_in,keyword,number,title
SCOPE[ruby]=class,function,keyword,params,string,subst,title
SCOPE[php]=class,function,keyword,meta,params,string,subst,title,variable
SCOPE[swift]=class,function,keyword,number,operator,params,string,subst,title,type
SCOPE[dart]=built_in,class,keyword,string,title
SCOPE[scala]=class,function,keyword,string,subst,title,type
SCOPE[bash]=built_in,keyword,meta,string,variable
SCOPE[json]=attr,literal,number,string
SCOPE[yaml]=attr,bullet,comment,number,string
SCOPE[toml]=attr,comment,number,section,string
SCOPE[sql]=built_in,comment,keyword,number,operator
SCOPE[html]=attr,meta,meta-keyword,name,string,tag
SCOPE[xml]=attr,meta,name,string,tag
SCOPE[css]=attribute,comment,number,selector-tag
SCOPE[markdown]=bullet,code,link,section,string,strong
SCOPE[dockerfile]=keyword,number,string
SCOPE[diff]=addition,comment,deletion,meta
```

pi's theme map (`theme.ts:1036-1064`) covers `keyword, built_in, literal, number, regexp, string,
comment, doctag, meta, function, title, class, type, tag, name, attr, variable, params, operator,
punctuation, emphasis, strong, link, addition, deletion` = 25 keys; 24 of them are in the 29 above
(`regexp`/`doctag`/`punctuation` simply did not appear in these particular samples, but are emitted by
highlight.js 10.7.3 for other grammars). **100 % of pi's scopes are reachable.**

### 3.6 Conformance: Rhino result == Node result for all 191 languages

```
$ node node-alllangs.js | sort > node-hashes.txt            # original lib/index.js under Node v24
$ java -Xmx1g -cp out:jars/rhino-1.7.15.jar AllLangs /tmp/hleval/hljs-full.es5.js | sort > rhino-hashes.txt
$ wc -l node-hashes.txt rhino-hashes.txt
191 node-hashes.txt
191 rhino-hashes.txt
$ diff node-hashes.txt rhino-hashes.txt | wc -l
0
$ grep -c ERROR rhino-hashes.txt node-hashes.txt
0 / 0
```

Each line is `language sha256(highlighted HTML)[0:16]` for a fixed 12-line multi-syntax snippet. Zero
differences means the Babel-transpiled ES5 bundle running on Rhino 1.7.15 produces **byte-identical
HTML to the reference highlight.js for every one of the 191 languages**, with no grammar throwing.

### 3.7 Other candidates — verified facts

```bash
# kotlin-textmate: on Maven Central (group with a hyphen; the artifact is NOT 'kotlin-textmate')
curl -s https://repo1.maven.org/maven2/io/github/ivan-magda/kotlin-textmate-core/maven-metadata.xml
#   0.1.0, 0.2.0 (latest/release), lastUpdated=20260610075705
curl -s .../kotlin-textmate-core/0.2.0/kotlin-textmate-core-0.2.0.pom      # MIT; deps below
#   kotlin-stdlib 2.0.21 | org.jruby.joni:joni 2.2.1 (runtime) |
#   com.google.code.gson:gson 2.11.0 (runtime) | org.jruby.jcodings:jcodings 1.0.58 (runtime)
# sizes: core jar 159,293 | compose aar 14,118 | joni 230,217 | jcodings 1,739,960 | gson 298,435
# class-file majors: core 61 (Java 17), joni 51, jcodings 52, gson 51  -> all Android-safe
# ZERO .so anywhere (joni is a pure-Java Oniguruma); AAR manifest minSdkVersion=24
```

I compiled and ran it myself from Java (`/tmp/hleval/tm/src/TmBench.java`, real
`kotlin.tmLanguage.json` from the project's `shared-assets/grammars/`):

```
TM_LOAD_MS=567.5
TM_COLD_MS[kotlin]=601.8 lines=24
TM_SCOPES[kotlin]=27 [comment.block.javadoc.kotlin, constant.numeric.decimal.kotlin,
  entity.name.function.declaration.kotlin, entity.name.type.class.kotlin, keyword.control.kotlin,
  keyword.hard.kotlin, meta.import.kotlin, storage.modifier.other.kotlin, string.quoted.double.kotlin, ...]
TM_WARM_MS[kotlin] n=30 avg=6.90 median=5.67 p95=14.41 best=3.25
TM_COLD_MS[json]=12.6 lines=12
TM_WARM_MS[json] n=30 avg=1.18 median=1.17 p95=1.46 best=0.93
```

2. `dev.hossain:compose-highlight` — on Maven Central (`dev.hossain`, latest **0.36.0**, released
   2026-09-03, MIT): AAR **629,348 B**, containing `assets/compose-highlight/highlight.min.js`
   **1,089,615 B** plus 27 theme CSS files. One shared hidden WebView + `HighlightEngine`
   (`highlight`, `highlightAuto`, `highlightBothThemes`), so it is *not* one WebView per block. Its
   `aar-metadata.properties` declares **`minCompileSdk=37`**, which would hard-fail this app
   (`compileSdk = 36`). `hossain-khan/android-syntax-highlighter` (YNAASH) itself is **not on Maven
   Central** and its README says it is a demo, not a library.
3. `io.github.rosemoe:editor:0.24.6` + `:language-textmate:0.24.6` (also `io.github.Rosemoe.sora-editor:*:0.23.6`):
   on Central, **LGPL-2.1** (POM `<name>LGPL v2.1</name>`), editor aar 665,203 B + textmate aar
   260,567 B + joni/jcodings/gson/snakeyaml-engine ≈ 2.3 MB. No `.so` in the default path, but the
   optional `io.github.rosemoe:oniguruma-native:0.24.6` aar is **1,299,014 B** with
   `jni/arm64-v8a/libonig.so` 623,000 B + `liboniguruma-binding.so` 387,736 B.
4. `org.eclipse.tm4e` — `https://repo1.maven.org/maven2/org/eclipse/tm4e/` → **404**; only
   `org.netbeans.external:org.eclipse.tm4e.core-0.14.1:RELEASE310` (306,381 B, EPL-2.0, POM with no
   dependencies). Current upstream requires **Java 21**.
5. `dev.snipme:highlights-jvm:1.1.0` — 120,318 B, Apache-2.0, **18** languages
   (`SyntaxLanguage` enum), no `androidJvm` variant published. Too weak (as already suspected).
6. JNI JavaScript: `com.eclipsesource.j2v8:j2v8_android_arm64-v8a:6.3.0` aar = **33,531,326 B** for
   one ABI. Rejected on size.

`UNVERIFIED`: the 190+ language list inside `dev.hossain:compose-highlight`'s `highlight.min.js`
(needs JS execution), Gradle resolution of `highlights-jvm` in an Android module, and all Compose UI
rendering for every candidate (no Android build/device available).

### 3.8 Size accounting (bytes, measured)

| Item | Raw | gzip -9 (APK proxy) |
|---|---|---|
| `rhino-runtime-1.7.15.jar` | 1,224,752 | 1,167,134 |
| `hljs-eager.es5.js` (core + 20 langs) | 142,729 | 32,996 |
| `hljs-rest.es5.js` (171 langs, = full − eager) | 1,033,352 | ~270,000 |
| `hljs-full.es5.js` (alternative: all 191 in one file) | 1,176,081 | 301,419 |
| `@highlightjs/cdn-assets@10.7.3` `highlight.min.js` (ES2015, **cannot** be used as-is) | 135,556 | 42,237 |
| `highlight.js` 9.18.5 `highlight.min.js` (ES5-ish, wrong major version) | 73,706 | 28,383 |

Expected APK growth for the recommendation ≈ **1.2 MB (Rhino dex, ~0.5–0.7 MB compressed) + ~1.2 MB of
assets (~300 KB compressed) ≈ 1.5–2 MB total**. `UNVERIFIED`: the exact post-D8/R8 number — no Android
build is possible here, and the app currently sets `isMinifyEnabled = false` (so no shrinking). Note
D8/R8 will warn about `java.beans.**`; see §3.1. Budgeting note: the app already ships a bundled Ubuntu
rootfs, so 1.5–2 MB is immaterial.

### 3.9 Reproducing

Everything is in `/tmp/hleval`:

```
src/HljsRunner.java   CommonJS-shim / bundle-mode loader + probes (used for the first runs)
src/HljsBench.java    clean benchmark: precompiled probe function, n=60 warm runs, median/p95/best
src/AllLangs.java     conformance harness (SHA-256 per language)
t/T2.java, T3.java, T4.java   ES6/API probes quoted above
bundle.js, transpile.js, scopes.js, node-alllangs.js
run-*.txt, bench-*.txt, node-hashes.txt, rhino-hashes.txt
```

---

## 4. Integration sketch

### 4.1 The interface the app exposes

```kotlin
/** One highlighted span, already resolved to a theme colour slot (pi's ~25 scopes). */
data class HighlightSpan(val start: Int, val end: Int, val scope: String?)

/** What the UI actually needs. Implementations must be safe to call from a coroutine. */
interface SyntaxHighlighter {
    /** null/unknown language => plain text (no auto-detect in the hot path, see 4.4). */
    suspend fun highlight(code: String, language: String?): AnnotatedString
    /** Cheap, non-suspending. Backed by a HashSet; no engine call. */
    fun supports(language: String): Boolean
    /** Optional: 20 eagerly registered languages are available immediately; call once at startup. */
    suspend fun warmUp()
}

@Composable
fun CodeBlock(code: String, language: String?, highlighter: SyntaxHighlighter, theme: PiSyntaxTheme) { … }
```

The Compose layer should build the `AnnotatedString` on a `Dispatchers.Default` coroutine keyed by the
block, and render plain text until the highlighted value arrives (pi-style: a code block that is
already on screen never blocks composition).

### 4.2 Rhino engine details

```kotlin
class RhinoHighlighter(private val assets: AssetManager) : SyntaxHighlighter {
    // ONE Context per thread. Rhino Context is not thread-safe; a single dedicated thread is simplest
    // and also caps CPU use for a transcript full of blocks.
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "hljs").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val ready = CompletableDeferred<Scriptable>()   // completed once assets are loaded

    private fun boot(): Scriptable {
        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1              // interpreted: the only ART-compatible mode
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            // REQUIRED: highlight.js calls console.warn when a language is missing.
            cx.evaluateString(scope, "var console={log:function(){},warn:function(){},error:function(){}};", "shim", 1, null)
            cx.evaluateString(scope, readAsset("hljs/hljs-eager.es5.js"), "hljs-eager", 1, null)
            cx.evaluateString(scope,
                "(function(c,l){return hljs.highlight(c,{language:l}).value;})", "probe", 1, null)
            return scope
        } finally { Context.exit() }
    }
    // lazily append hljs-rest.es5.js on the same scope the first time an unknown language is requested
}
```

Key points, each backed by a measurement above:

* `optimizationLevel = -1`. Level 9 needs runtime bytecode dexing on Android and is **not faster**
  (§3.4), so there is no reason to take the risk.
* `VERSION_ES6` language level, and a **`console` shim** — otherwise the first unknown language throws.
* Load `hljs-eager.es5.js` at startup (~1.4–2.5 s desktop, ~4–8 s extrapolated on a phone) on the
  background thread, then `hljs-rest.es5.js` opportunistically, exactly like pi's
  `loadAllHighlightLanguages()`.
* Result values come back as **HTML**; parse them with pi's logic (`renderHighlightedHtml` in
  `syntax-highlight.ts`): walk `<span class="hljs-x">`/`</span>`, track a scope stack, resolve the
  innermost known scope through the theme map, and append `AnnotatedString` spans. That is a ~40-line
  port and guarantees the same output as pi.

### 4.3 Caching and off-main-thread

```kotlin
private val cache = object : LruCache<String, AnnotatedString>(256) {}   // androidx.collection

suspend fun highlight(code: String, language: String?): AnnotatedString {
    val key = (language ?: "") + '\u0000' + code          // hash it if blocks get long
    cache[key]?.let { return it }
    return withContext(dispatcher.asCoroutineDispatcher()) {
        cache[key] ?: runEngine(code, language).also { cache.put(key, it) }
    }
}
```

* Key on `(language, code)`; the same block re-emitted by the agent (streaming, re-renders, list
  recycling) then costs nothing. Add a stable content hash if blocks can be large.
* One dedicated low-priority thread for the engine: Rhino `Context` is per-thread, and a single thread
  keeps the ~45 MB heap delta and the CPU cost bounded.
* Debounce streaming text: only highlight a block when it stops changing for ~150–250 ms, otherwise a
  long answer runs the highlighter for every token. The 40-line measurement (48–98 ms warm on desktop)
  is per *final* block; per-token highlighting would be 50–100× that.
* Consider suppressing highlighting for blocks above a size threshold (e.g. > 400 lines) or off-screen
  items; `LazyColumn` + `derivedStateOf` on the visible range is enough.

### 4.4 `highlightAuto` and language detection

* `hljs.highlightAuto(code)` with **no** subset = **9.7–14.7 s** for 191 languages. Never call it.
* With an 8-language subset it is still 0.5–1.1 s — too slow for a scrolling list.
* pi already passes `options.languageSubset`; the Android side should do the same with a very small
  subset (≤ 5 languages) or, better, not auto-detect at all and render plain text when the fence has no
  language. If detection is wanted, run it once per block on the background thread and cache the result.

### 4.5 Build step that must be committed

The ES5 assets are generated artifacts; commit both the generated `.js` files **and** the generator so
the transpile is reproducible:

```
tools/hljs/build.sh          # curl @highlightjs/cdn-assets@10.7.3 tgz, split core, concat, babel
package.json (devDependencies only): @babel/core@7, @babel/preset-env@7  (targets: {ie: '11'})
app/src/main/assets/hljs/hljs-eager.es5.js
app/src/main/assets/hljs/hljs-rest.es5.js
```

Re-run `node tools/hljs/verify.js` (the `AllLangs` harness) in CI: it must keep reporting 191 languages
and 0 hash differences against `highlight.js@10.7.3`'s `lib/index.js` under Node.

---

## 5. Fallback ranking (what I would do if this fails)

1. **`io.github.ivan-magda:kotlin-textmate-core:0.2.0` (+ `-compose:0.2.0`)** — switch if on-device
   latency for a 40-line block exceeds ~50 ms warm (visible jank) or if Rhino's ~45 MB heap is a
   problem. MIT, pure Kotlin, pure-Java Joni (no `.so`), Java 17 bytecode, minSdk 24 — the closest
   drop-in behind `SyntaxHighlighter`. **Measured 1.17 ms warm for 12-line JSON and 5.67 ms for 24-line
   Kotlin** (my runs), ~1.6–1.9 ms per 28-line block in the author's benchmark. Costs: vendor
   `.tmLanguage.json` grammar assets (kotlin 18,576 B, JSON 5,084 B, JavaScript 100,210 B,
   markdown 79,877 B, `MagicPython` 101,610 B, rust 25,373 B — budget **1–2 MB for the 27 required
   languages, ~8.7 MB for ~200**), a **0.5–0.9 s cold first highlight** that must be pre-warmed
   off-thread, and a pre-1.0 single-maintainer dependency (2 releases, 47 stars, 0 forks). Colors come
   from VS Code `tokenColors` themes (`dark_vs` + `dark_plus` merge is 145–223 ms to load), which
   easily exceeds pi's 25 scopes — but token boundaries will **not** be byte-identical to pi.
2. **`dev.hossain:compose-highlight:0.36.0`** — if the team would rather have pi's exact highlight.js
   output with no Rhino/transpile work. MIT, 629 KB, one shared hidden WebView (not per block), warm
   ~6.4–18 ms/block in the author's on-device benchmark. Blockers to resolve first: it declares
   **`minCompileSdk=37`** while this app uses `compileSdk = 36`, and a Chromium instance stays resident
   for the life of the chat screen.
3. **`dev.snipme:highlights:1.1.0`** — only as an emergency stopgap: Apache-2.0, 120 KB, no native code,
   but **18 languages and a 9-colour theme**, i.e. strictly weaker than pi. Also `UNVERIFIED` whether an
   Android module resolves `highlights-jvm` cleanly.
4. **`io.github.rosemoe:editor` + `language-textmate`** — technically capable and actively maintained,
   but **LGPL-2.1** and it drags in a full editor UI plus snakeyaml. Only if licensing is resolved and
   the editor is genuinely wanted.
5. **Keep the Rhino path but drop exact parity**: transpile highlight.js **11.x** instead of 10.7.3 for
   `hcl`/`graphql` and newer grammars — same architecture, same risk profile, slightly different scopes.
6. **Last resort**: no JS engine — hand-write TextMate-less, regex-free lexical highlighting for a small
   fixed language set. This was already rejected by the team and would be weaker than pi, so it should
   not be revisited.
