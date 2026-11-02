package app.pi.ui.render.rich

// Bare-JVM harness for `PiLinkCard.kt` — the URL-only link-card model.
//
// This file exists because the card makes claims the compiler cannot check: that
// `hostForDisplay` never leaks a `user:password@` prefix, that a default port is
// not shown while a non-default one is, that a query/fragment is represented only
// as `?…`/`#…` (its text is never rendered), that a scheme the card does not
// render is refused rather than styled as trustworthy, and that the 8,192-char
// bound is exactly at 8,192. Every one of those is a silent failure on a device —
// the card simply says something about a URL that the URL did not say — so the
// checks are field-level assertions, not "did it parse".
//
// Run it by hand:
//
//   tools/run-app-pure-checks.sh

private var failures = 0
private var checks = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    checks++
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private fun info(url: String): PiLinkInfo? {
    val result = PiLinkCard.parse(url)
    check("(fixture) `$url` should parse to Info, got $result", result is LinkCardResult.Info, true)
    return (result as? LinkCardResult.Info)?.info
}

private fun failureOf(url: String): LinkCardFailure? = (PiLinkCard.parse(url) as? LinkCardResult.Failure)?.reason

fun main() {
    // =================================================== A. the simplest URL
    run {
        val info = info("https://example.com")!!
        check("A1 scheme", info.scheme, "https")
        check("A2 host", info.host, "example.com")
        check("A3 default port is null", info.port, null)
        check("A4 hostForDisplay has no port", info.hostForDisplay, "example.com")
        check("A5 no path", info.path, "")
        check("A6 pathForDisplay is `/` for no path", info.pathForDisplay, "/")
        check("A7 kind", info.kind, LinkKind.WebPage)
        check("A8 no extension", info.fileExtension, null)
        check("A9 no query", info.hasQuery, false)
        check("A10 no fragment", info.hasFragment, false)
        check("A11 the url is kept as given", info.url, "https://example.com")
    }

    // =================================================== B. port display
    run {
        val nonDefault = info("http://example.com:8080/x.pdf")!!
        check("B1 a non-default port is kept", nonDefault.port, 8080)
        check("B2 hostForDisplay carries it", nonDefault.hostForDisplay, "example.com:8080")

        val httpsDefault = info("https://example.com:443/")!!
        check("B3 the https default port is hidden", httpsDefault.port, null)
        check("B4 hostForDisplay has no port", httpsDefault.hostForDisplay, "example.com")
        check("B5 its path is `/`", httpsDefault.pathForDisplay, "/")

        val httpDefault = info("http://example.com:80/")!!
        check("B6 the http default port is hidden", httpDefault.port, null)
        check("B7 hostForDisplay has no port", httpDefault.hostForDisplay, "example.com")

        val http443 = info("http://example.com:443/")!!
        check("B8 443 is not default for http, so it is kept", http443.port, 443)
        check("B9 hostForDisplay carries it", http443.hostForDisplay, "example.com:443")

        val https80 = info("https://x:80/a")!!
        check("B10 80 is not default for https, so it is kept", https80.port, 80)
        check("B11 hostForDisplay carries it", https80.hostForDisplay, "x:80")
    }

    // =================================================== C. userinfo never leaks
    run {
        val withCredentials = info("https://user:pass@example.com/secret.js")!!
        check("C1 the userinfo is not in the host", withCredentials.host, "example.com")
        check("C2 hostForDisplay is just the host", withCredentials.hostForDisplay, "example.com")
        check("C3 the password is nowhere in hostForDisplay", withCredentials.hostForDisplay.contains("pass"), false)
        check("C4 the user is nowhere in hostForDisplay", withCredentials.hostForDisplay.contains("user"), false)
        check("C5 the path is still read", withCredentials.path, "/secret.js")
        check("C6 a .js path is code", withCredentials.kind, LinkKind.Code)
        check("C7 the original url is untouched", withCredentials.url, "https://user:pass@example.com/secret.js")

        val userOnly = info("https://user@example.com/x")!!
        check("C8 a username-only prefix is dropped too", userOnly.host, "example.com")
    }

    // =================================================== D. query and fragment
    run {
        val both = info("https://example.com/docs/page.html?q=1#frag")!!
        check("D1 the path excludes the query", both.path, "/docs/page.html")
        check("D2 query is recorded", both.hasQuery, true)
        check("D3 fragment is recorded", both.hasFragment, true)
        check("D4 the query/fragment text is not rendered", both.pathForDisplay, "/docs/page.html?…#…")
        check("D5 the query text does not appear", both.pathForDisplay.contains("q=1"), false)
        check("D6 the fragment text does not appear", both.pathForDisplay.contains("frag"), false)
        check("D7 a .html path is a web page", both.kind, LinkKind.WebPage)
        check("D8 its extension", both.fileExtension, "html")

        val queryOnly = info("https://example.com/search?q=term")!!
        check("D9 a query-only marker", queryOnly.pathForDisplay, "/search?…")
        check("D10 no fragment marker", queryOnly.pathForDisplay.contains('#'), false)

        val fragmentOnly = info("https://example.com/a#section")!!
        check("D11 a fragment-only marker", fragmentOnly.pathForDisplay, "/a#…")
        check("D12 no query marker", fragmentOnly.pathForDisplay.contains('?'), false)

        val noPathQuery = info("https://example.com?q=1")!!
        check("D13 a query without a path", noPathQuery.pathForDisplay, "/?…")

        val emptyQuery = info("https://x/p?")!!
        check("D14 an empty query is not a query", emptyQuery.hasQuery, false)
        check("D15 and adds no marker", emptyQuery.pathForDisplay, "/p")

        val emptyFragment = info("https://x/p#")!!
        check("D16 an empty fragment is not a fragment", emptyFragment.hasFragment, false)
        check("D17 and adds no marker", emptyFragment.pathForDisplay, "/p")
    }

    // =================================================== E. kinds from the path
    run {
        check("E1 png is an image", info("https://x/a.png")!!.kind, LinkKind.Image)
        check("E2 pdf is a document", info("https://x/a.pdf")!!.kind, LinkKind.Document)
        check("E3 tar.gz is an archive", info("https://x/a.tar.gz")!!.kind, LinkKind.Archive)
        check("E4 gz is the extension", info("https://x/a.tar.gz")!!.fileExtension, "gz")
        check("E5 mp4 is video", info("https://x/v.mp4")!!.kind, LinkKind.Video)
        check("E6 mp3 is audio", info("https://x/a.mp3")!!.kind, LinkKind.Audio)
        check("E7 an unknown extension is Other", info("https://x/a.xyz")!!.kind, LinkKind.Other)
        check("E8 no extension with segments is a web page", info("https://x/docs/getting-started")!!.kind, LinkKind.WebPage)
        check("E9 trailing slash is a web page", info("https://x/")!!.kind, LinkKind.WebPage)
        check("E10 its pathForDisplay", info("https://x/")!!.pathForDisplay, "/")
        check("E11 the visible labels", LinkKind.WebPage.label, "网页")
        check("E12 image label", LinkKind.Image.label, "图片")
        check("E13 document label", LinkKind.Document.label, "文档")
        check("E14 archive label", LinkKind.Archive.label, "压缩包")
        check("E15 code label", LinkKind.Code.label, "代码")
        check("E16 video label", LinkKind.Video.label, "视频")
        check("E17 audio label", LinkKind.Audio.label, "音频")
        check("E18 other label", LinkKind.Other.label, "链接")
    }

    // =================================================== F. schemes and malformed URLs
    run {
        check("F1 mailto is not rendered as a card", failureOf("mailto:a@b.c"), LinkCardFailure.UnsupportedScheme)
        check("F2 tel is not rendered", failureOf("tel:+8613800138000"), LinkCardFailure.UnsupportedScheme)
        check("F3 file is not rendered", failureOf("file:///tmp/x"), LinkCardFailure.UnsupportedScheme)
        check("F4 javascript is not rendered", failureOf("javascript:alert(1)"), LinkCardFailure.UnsupportedScheme)
        check("F5 data is not rendered", failureOf("data:text/plain;base64,AAAA"), LinkCardFailure.UnsupportedScheme)

        check("F6 a relative path is not a URL", failureOf("docs/a"), LinkCardFailure.NotUrl)
        check("F7 a fragment-only string is not a URL", failureOf("#frag"), LinkCardFailure.NotUrl)
        check("F8 a scheme-relative URL is not a URL", failureOf("//example.com/x"), LinkCardFailure.NotUrl)
        check("F9 a bare scheme with no authority", failureOf("https://"), LinkCardFailure.NotUrl)
        check("F10 an empty authority", failureOf("https:///path"), LinkCardFailure.NotUrl)
        check("F11 an out-of-range port", failureOf("https://x:99999/"), LinkCardFailure.NotUrl)
        check("F12 a zero port", failureOf("https://x:0/"), LinkCardFailure.NotUrl)

        check("F13 a space inside the URL", failureOf("https://exa mple.com/"), LinkCardFailure.NotUrl)
        check("F14 a space in the path", failureOf("https://example.com/a b"), LinkCardFailure.NotUrl)
        check("F15 a tab inside the URL", failureOf("https://x/\ty"), LinkCardFailure.NotUrl)
        check("F16 a newline inside the URL", failureOf("https://x/\ny"), LinkCardFailure.NotUrl)

        check("F17 an empty string", failureOf(""), LinkCardFailure.Empty)
        check("F18 a whitespace-only string", failureOf("   "), LinkCardFailure.Empty)

        val trimmed = info("  https://example.com  ")!!
        check("F19 surrounding whitespace is trimmed", trimmed.url, "https://example.com")
        check("F20 the trimmed path", trimmed.pathForDisplay, "/")
    }

    // =================================================== G. the 8,192 bound
    run {
        check("G1 the constant is 8,192", PiLinkCard.MAX_URL_CHARS, 8_192)
        val prefix = "https://example.com/"
        val atBound = prefix + "a".repeat(PiLinkCard.MAX_URL_CHARS - prefix.length)
        check("G2 the constructed URL is exactly at the bound", atBound.length, 8_192)
        check("G3 exactly maxChars is accepted", PiLinkCard.parse(atBound) is LinkCardResult.Info, true)
        check("G4 one longer is over", atBound.length + 1, 8_193)

        val overBound = prefix + "a".repeat(PiLinkCard.MAX_URL_CHARS + 1 - prefix.length)
        check("G5 the constructed URL is one over", overBound.length, 8_193)
        check("G6 one character over falls back", failureOf(overBound), LinkCardFailure.TooLong)
    }

    // =================================================== H. the 20 hand-checked URLs
    run {
        check("H1 https://example.com keeps its path empty", info("https://example.com")!!.pathForDisplay, "/")

        val docs = info("https://example.com/docs/page.html?q=1#frag")!!
        check("H2 docs page file", docs.path, "/docs/page.html")
        check("H3 docs page markers", docs.pathForDisplay, "/docs/page.html?…#…")

        val pdf = info("http://example.com:8080/x.pdf")!!
        check("H4 pdf host", pdf.hostForDisplay, "example.com:8080")
        check("H5 pdf kind", pdf.kind, LinkKind.Document)

        check("H6 https default port", info("https://example.com:443/")!!.port, null)

        val creds = info("https://user:pass@example.com/secret.js")!!
        check("H7 credentials are stripped", creds.host, "example.com")
        check("H8 secret path", creds.path, "/secret.js")

        val ipv6 = info("https://[::1]:8443/a.png")!!
        check("H9 IPv6 host keeps brackets", ipv6.host, "[::1]")
        check("H10 IPv6 port", ipv6.port, 8443)
        check("H11 IPv6 hostForDisplay", ipv6.hostForDisplay, "[::1]:8443")
        check("H12 IPv6 image", ipv6.kind, LinkKind.Image)

        check("H13 javascript:", failureOf("javascript:alert(1)"), LinkCardFailure.UnsupportedScheme)
        check("H14 mailto:", failureOf("mailto:a@b.c"), LinkCardFailure.UnsupportedScheme)

        check("H15 relative path", failureOf("docs/a"), LinkCardFailure.NotUrl)
        check("H16 bare https", failureOf("https://"), LinkCardFailure.NotUrl)
        check("H17 empty authority", failureOf("https:///path"), LinkCardFailure.NotUrl)
        check("H18 invalid port", failureOf("https://x:99999/"), LinkCardFailure.NotUrl)

        check("H19 unknown extension", info("https://x/a.xyz")!!.kind, LinkKind.Other)
        check("H20 route-like path", info("https://x/docs/getting-started")!!.kind, LinkKind.WebPage)
        check("H21 root path", info("https://x/")!!.pathForDisplay, "/")

        val upper = info("HTTPS://EXAMPLE.COM/Path")!!
        check("H22 the scheme is lower-cased", upper.scheme, "https")
        check("H23 the host is kept as typed", upper.host, "EXAMPLE.COM")
        check("H24 the path is kept as typed", upper.path, "/Path")

        val archive = info("https://x/a.tar.gz")!!
        check("H25 archive extension", archive.fileExtension, "gz")
        check("H26 archive kind", archive.kind, LinkKind.Archive)

        check("H27 https:80 keeps the port", info("https://x:80/a")!!.hostForDisplay, "x:80")
        check("H28 trailing empty query", info("https://x/p?")!!.pathForDisplay, "/p")
        check("H29 trailing empty fragment", info("https://x/p#")!!.pathForDisplay, "/p")
    }

    if (failures == 0) {
        println("\nharness: OK ($checks checks)")
    } else {
        println("\nharness: FAILED ($failures of $checks checks)")
        kotlin.system.exitProcess(1)
    }
}
