package com.example.a1

import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * JavaScript <-> Android bridge that receives the JSON payload produced by the
 * feature extraction script and converts values into nullable Floats.
 */
class WebFeatureExtractor(private val callback: (WebFeatures) -> Unit) {

    @JavascriptInterface
    fun receiveFeatures(featuresJson: String) {
        try {
            Log.d("WebFeatureExtractor", "RAW_FEATURES_JSON: $featuresJson")

            val jsonObject = JSONObject(featuresJson)
            val features = mutableMapOf<String, Float?>()
            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (jsonObject.isNull(key)) {
                    features[key] = null
                    continue
                }

                val value = jsonObject.get(key)
                features[key] = when (value) {
                    is Number -> value.toFloat()
                    is Boolean -> if (value) 1.0f else 0.0f
                    is String -> {
                        val s = value.trim()
                        s.toFloatOrNull()?.also {
                            Log.d("WebFeatureExtractor", "Parsed numeric-string for $key: $s")
                        } ?: run {
                            Log.d("WebFeatureExtractor", "Non-numeric value for $key: '$s'")
                            null
                        }
                    }
                    else -> {
                        Log.d("WebFeatureExtractor", "Unexpected type for $key: ${value?.javaClass?.name}")
                        null
                    }
                }
            }

            val presentCount = features.count { it.value != null }
            val nullCount = features.count { it.value == null }
            Log.d("WebFeatureExtractor", "Parsed features: total=${features.size}, present=$presentCount, null=$nullCount")
            callback(features)
        } catch (e: Exception) {
            Log.e("WebFeatureExtractor", "Failed to parse feature JSON", e)
        }
    }

    fun getFeatureExtractionScript(): String {
        // The very large JS string is kept here intentionally — it mirrors the
        // Python feature extraction logic exactly (including the same heuristics)
        return """
            javascript:(function() {
                try {
                    function normalizeUrl(raw) {
                        try {
                            return new URL(raw, window.location.href);
                        } catch (e) {
                            return null;
                        }
                    }

                    var url = window.location.href;
                    var hostLower = window.location.hostname.toLowerCase();
                    var pathLower = window.location.pathname.toLowerCase();
                    var hostParts = hostLower.split('.');
                    var subdomainPart = hostParts.length > 2 ? hostParts.slice(0, hostParts.length - 2).join('.') : '';
                    var domainLabel = hostParts.length > 1 ? hostParts[hostParts.length - 2] : hostLower;
                    var tld = hostParts.length > 0 ? hostParts[hostParts.length - 1] : '';

                    var splitRegex = /[\-\.\/\?\=\@\&\%\:\_]/;
                    var urlForWords = window.location.hostname + window.location.pathname + window.location.search;
                    var urlWords = urlForWords.split(splitRegex).filter(function(w){ return w && w.length > 0; });
                    var hostWords = window.location.hostname.split(splitRegex).filter(function(w){ return w && w.length > 0; });
                    var pathWords = (window.location.pathname + window.location.search).split(splitRegex).filter(function(w){ return w && w.length > 0; });

                    var features = {};

                    features.length_url = url.length;
                    features.length_hostname = window.location.hostname.length;
                    features.ip = /^(\d{1,3}\.){3}\d{1,3}$/.test(window.location.hostname) ? 1 : 0;
                    features.nb_dots = (url.match(/\./g) || []).length;
                    features.nb_hyphens = (url.match(/-/g) || []).length;
                    features.nb_at = (url.match(/@/g) || []).length;
                    features.nb_qm = (url.match(/\?/g) || []).length;
                    features.nb_and = (url.match(/&/g) || []).length;
                    features.nb_or = (url.match(/\|/g) || []).length;
                    features.nb_eq = (url.match(/=/g) || []).length;
                    features.nb_underscore = (url.match(/_/g) || []).length;
                    features.nb_tilde = (url.match(/~/g) || []).length;
                    features.nb_percent = (url.match(/%/g) || []).length;
                    features.nb_slash = (url.match(/\//g) || []).length;
                    features.nb_star = (url.match(/\*/g) || []).length;
                    features.nb_colon = (url.match(/:/g) || []).length;
                    features.nb_comma = (url.match(/,/g) || []).length;
                    features.nb_semicolumn = (url.match(/;/g) || []).length;
                    features.nb_dollar = (url.match(/\$/g) || []).length;
                    features.nb_space = (url.match(/ /g) || []).length + (url.match(/%20/g) || []).length;

                    var wwwCount = 0;
                    for (var wi = 0; wi < urlWords.length; wi++) {
                        if (urlWords[wi].toLowerCase().indexOf('www') !== -1) wwwCount++;
                    }
                    features.nb_www = wwwCount;

                    var comCount = 0;
                    for (var ci = 0; ci < urlWords.length; ci++) {
                        if (urlWords[ci].toLowerCase().indexOf('com') !== -1) comCount++;
                    }
                    features.nb_com = comCount;

                    var slashMatches = [];
                    var slashRegex = /\/\//g;
                    var match;
                    while ((match = slashRegex.exec(url)) !== null) {
                        slashMatches.push(match.index);
                    }
                    if (slashMatches.length > 0 && slashMatches[slashMatches.length - 1] > 6) {
                        features.nb_dslash = 1;
                    } else {
                        features.nb_dslash = 0;
                    }

                    features.http_in_path = pathLower.includes('http') ? 1 : 0;
                    features.https_token = window.location.protocol === 'https:' ? 0 : 1;
                    features.ratio_digits_url = (url.match(/\d/g) || []).length / Math.max(url.length, 1);
                    features.ratio_digits_host = (window.location.hostname.match(/\d/g) || []).length / Math.max(window.location.hostname.length, 1);
                    features.punycode = (url.startsWith('http://xn--') || url.startsWith('https://xn--')) ? 1 : 0;
                    features.port = /^[a-z][a-z0-9+\-.]*:\/\/([a-z0-9\-._~%!$&'()*+,;=]+@)?([a-z0-9\-._~%]+|\[[a-z0-9\-._~%!$&'()*+,;=:]+\]):([0-9]+)/.test(url) ? 1 : 0;
                    features.tld_in_path = pathLower.indexOf(tld) !== -1 ? 1 : 0;
                    features.tld_in_subdomain = subdomainPart.toLowerCase().indexOf(tld) !== -1 ? 1 : 0;
                    features.abnormal_subdomain = /(http[s]?:\/\/(w[w]?|\d))([w]?(\d|-))/.test(url) ? 1 : 0;

                    var dotCount = (url.match(/\./g) || []).length;
                    if (dotCount == 1) {
                        features.nb_subdomains = 1;
                    } else if (dotCount == 2) {
                        features.nb_subdomains = 2;
                    } else {
                        features.nb_subdomains = 3;
                    }

                    features.prefix_suffix = /https?:\/\/[^
                    \-]+\-[^\-]+\//.test(url) ? 1 : 0;
                    features.random_domain = (domainLabel && domainLabel.length >= 5 && (domainLabel.replace(/[aeiou]/gi,'').length / domainLabel.length) > 0.6) ? 1 : 0;

                    var shortenerHosts = ['bit.ly','tinyurl.com','t.co','goo.gl','ow.ly','is.gd','s.id','rebrand.ly','buff.ly','cutt.ly','lnkd.in'];
                    features.shortening_service = shortenerHosts.includes(hostLower) ? 1 : 0;

                    features.path_extension = window.location.pathname.endsWith('.txt') ? 1 : 0;

                    var redirectChainLength = 0;
                    try {
                        if (window.performance && window.performance.getEntriesByType) {
                            var navEntries = window.performance.getEntriesByType('navigation');
                            if (navEntries && navEntries.length > 0 && typeof navEntries[0].redirectCount === 'number') {
                                redirectChainLength = navEntries[0].redirectCount;
                            } else if (window.performance.navigation && typeof window.performance.navigation.redirectCount === 'number') {
                                redirectChainLength = window.performance.navigation.redirectCount;
                            }
                        }
                    } catch (redirectErr) {
                        redirectChainLength = 0;
                    }
                    features.nb_redirection = redirectChainLength;
                    features.nb_external_redirection = 0;
                    features.length_words_raw = urlWords.length;

                    function countCharRepeat(words) {
                        var repeatCounts = {2: 0, 3: 0, 4: 0, 5: 0};
                        for (var wi = 0; wi < words.length; wi++) {
                            var word = words[wi];
                            for (var len = 2; len <= 5; len++) {
                                for (var i = 0; i <= word.length - len; i++) {
                                    var substr = word.substr(i, len);
                                    var allSame = true;
                                    for (var c = 1; c < substr.length; c++) {
                                        if (substr[c] !== substr[0]) { allSame = false; break; }
                                    }
                                    if (allSame) repeatCounts[len]++;
                                }
                            }
                        }
                        return repeatCounts[2] + repeatCounts[3] + repeatCounts[4] + repeatCounts[5];
                    }
                    features.char_repeat = countCharRepeat(urlWords);

                    var urlWordLengths = urlWords.map(function(w) { return w.length; });
                    features.shortest_words_raw = urlWordLengths.length > 0 ? Math.min.apply(null, urlWordLengths) : 0;
                    var hostWordLengths = hostWords.map(function(w) { return w.length; });
                    features.shortest_word_host = hostWordLengths.length > 0 ? Math.min.apply(null, hostWordLengths) : 0;
                    var pathWordLengths = pathWords.map(function(w) { return w.length; });
                    features.shortest_word_path = pathWordLengths.length > 0 ? Math.min.apply(null, pathWordLengths) : 0;
                    features.longest_words_raw = urlWordLengths.length > 0 ? Math.max.apply(null, urlWordLengths) : 0;
                    features.longest_word_host = hostWordLengths.length > 0 ? Math.max.apply(null, hostWordLengths) : 0;
                    features.longest_word_path = pathWordLengths.length > 0 ? Math.max.apply(null, pathWordLengths) : 0;
                    function calcAvg(arr) {
                        if (!arr || arr.length === 0) return 0;
                        var sum = 0;
                        for (var i = 0; i < arr.length; i++) sum += arr[i];
                        return sum / arr.length;
                    }
                    features.avg_words_raw = calcAvg(urlWordLengths);
                    features.avg_word_host = calcAvg(hostWordLengths);
                    features.avg_word_path = calcAvg(pathWordLengths);

                    var phishKeywords = ['wp','login','includes','admin','content','site','images','js','alibaba','css','myaccount','dropbox','themes','plugins','signin','view'];
                    var urlLower = url.toLowerCase();
                    var phishHintCount = 0;
                    for (var pk = 0; pk < phishKeywords.length; pk++) {
                        if (urlLower.indexOf(phishKeywords[pk]) !== -1) phishHintCount++;
                    }
                    features.phish_hints = phishHintCount;

                    var brandKeywords = ['paypal','naver','apple','bank','google','microsoft','kakao','facebook','instagram','amazon','ebay','netflix','samsung'];
                    features.domain_in_brand = brandKeywords.includes(domainLabel) ? 1 : 0;

                    features.brand_in_subdomain = 0;
                    for (var b = 0; b < brandKeywords.length; b++) {
                        if (subdomainPart.indexOf('.' + brandKeywords[b] + '.') !== -1) {
                            features.brand_in_subdomain = 1;
                            break;
                        }
                    }

                    features.brand_in_path = 0;
                    for (var b = 0; b < brandKeywords.length; b++) {
                        if (pathLower.indexOf('.' + brandKeywords[b] + '.') !== -1) {
                            features.brand_in_path = 1;
                            break;
                        }
                    }

                    var suspiciousTlds = ['fit','tk','gp','ga','work','ml','date','wang','men','icu','online','click','xyz','top','zip','country','stream','download','xin','racing','jetzt','ren','mom','party','review','trade','accountants','science','ninja','faith','cricket','win','accountant','realtor','christmas','gdn','link','asia','club','la','ae','exposed','pe','rs','audio','website','bj','mx','media'];
                    features.suspecious_tld = suspiciousTlds.includes(tld) ? 1 : 0;
                    features.statistical_report = 0;

                    var allHrefElements = document.querySelectorAll('[href]');
                    var allSrcElements = document.querySelectorAll('[src]');
                    features.nb_hyperlinks = allHrefElements.length + allSrcElements.length;

                    var anchors = Array.prototype.slice.call(document.querySelectorAll('a[href]'));
                    var totalAnchors = anchors.length;
                    var internalCount = 0;
                    var externalCount = 0;
                    var nullCount = 0;
                    for (var a = 0; a < anchors.length; a++) {
                        var href = anchors[a].getAttribute('href');
                        if (!href || href.trim() === '' || href.startsWith('#') || href.toLowerCase().startsWith('javascript:')) {
                            nullCount++;
                            continue;
                        }
                        var n = normalizeUrl(href);
                        if (!n || !n.hostname) {
                            nullCount++;
                            continue;
                        }
                        if (n.hostname === window.location.hostname) internalCount++; else externalCount++;
                    }
                    features.ratio_intHyperlinks = totalAnchors === 0 ? 0 : (internalCount / totalAnchors);
                    features.ratio_extHyperlinks = totalAnchors === 0 ? 0 : (externalCount / totalAnchors);
                    features.ratio_nullHyperlinks = totalAnchors === 0 ? 0 : (nullCount / totalAnchors);

                    var cssLinks = document.querySelectorAll('link[rel="stylesheet"]');
                    var extCSSCount = 0;
                    for (var ci = 0; ci < cssLinks.length; ci++) {
                        var cssHref = cssLinks[ci].getAttribute('href');
                        if (cssHref) {
                            var cssUrl = normalizeUrl(cssHref);
                            if (cssUrl && cssUrl.hostname && cssUrl.hostname !== window.location.hostname) {
                                extCSSCount++;
                            }
                        }
                    }
                    features.nb_extCSS = extCSSCount;

                    features.ratio_intRedirection = 0;
                    features.ratio_extRedirection = 0;
                    features.ratio_intErrors = 0;
                    features.ratio_extErrors = 0;

                    var forms = document.getElementsByTagName('form');
                    var hasExternalOrNullForm = false;
                    var hasPhpForm = false;
                    for (var i = 0; i < forms.length; i++) {
                        var action = (forms[i].getAttribute('action') || '').trim();
                        if (!action || action === '' || action === '#' || action === 'about:blank' || action.startsWith('javascript:')) {
                            hasExternalOrNullForm = true;
                        } else if (action.indexOf('http') === 0) {
                            var formUrl = normalizeUrl(action);
                            if (formUrl && formUrl.hostname && formUrl.hostname !== window.location.hostname) {
                                hasExternalOrNullForm = true;
                            }
                        }
                        if (/([a-zA-Z0-9_])+\.php/.test(action)) {
                            hasPhpForm = true;
                        }
                    }
                    features.login_form = (hasExternalOrNullForm || hasPhpForm) ? 1 : 0;

                    var faviconLinks = document.querySelectorAll('link[rel*="icon"]');
                    var hasExternalFavicon = false;
                    for (var fi = 0; fi < faviconLinks.length; fi++) {
                        var faviHref = faviconLinks[fi].getAttribute('href');
                        if (faviHref && faviHref.indexOf('http') === 0) {
                            var favUrl = normalizeUrl(faviHref);
                            if (favUrl && favUrl.hostname && favUrl.hostname !== window.location.hostname) {
                                hasExternalFavicon = true;
                                break;
                            }
                        }
                    }
                    features.external_favicon = hasExternalFavicon ? 1 : 0;

                    var linkElements = document.querySelectorAll('link[href]');
                    var internalLinks = 0;
                    var externalLinks = 0;
                    for (var li = 0; li < linkElements.length; li++) {
                        var linkHref = linkElements[li].getAttribute('href');
                        if (!linkHref) continue;
                        var linkUrl = normalizeUrl(linkHref);
                        if (!linkUrl || !linkUrl.hostname) continue;
                        if (linkUrl.hostname === window.location.hostname) internalLinks++; else externalLinks++;
                    }
                    var totalLinks = internalLinks + externalLinks;
                    features.links_in_tags = totalLinks === 0 ? 0 : ((internalLinks / totalLinks) * 100);

                    var hasEmailSubmit = false;
                    for (var i = 0; i < forms.length; i++) {
                        var action = (forms[i].getAttribute('action') || '').toLowerCase();
                        if (action.indexOf('mailto:') !== -1 || action.indexOf('mail()') !== -1) {
                            hasEmailSubmit = true;
                        } else {
                            hasEmailSubmit = false;
                        }
                        break;
                    }
                    features.submit_email = hasEmailSubmit ? 1 : 0;

                    var mediaEls = Array.prototype.slice.call(document.querySelectorAll('img, video, audio, source'));
                    var totalMedia = mediaEls.length;
                    var internalMedia = 0;
                    var externalMedia = 0;
                    for (var m = 0; m < mediaEls.length; m++) {
                        var src = mediaEls[m].getAttribute('src') || mediaEls[m].getAttribute('data-src');
                        if (!src) continue;
                        var nm = normalizeUrl(src);
                        if (!nm || !nm.hostname) continue;
                        if (nm.hostname === window.location.hostname) internalMedia++; else externalMedia++;
                    }
                    features.ratio_intMedia = totalMedia === 0 ? 0 : ((internalMedia / totalMedia) * 100);
                    features.ratio_extMedia = totalMedia === 0 ? 0 : ((externalMedia / totalMedia) * 100);

                    var unsafeForms = 0;
                    for (var f = 0; f < forms.length; f++) {
                        var action = forms[f].getAttribute('action') || '';
                        var trimmed = action.trim().toLowerCase();
                        if (!trimmed || trimmed === '#' || trimmed === 'about:blank' || trimmed.startsWith('javascript:')) {
                            unsafeForms++; continue;
                        }
                        if (trimmed.indexOf('http') === 0) {
                            var urlA = normalizeUrl(trimmed);
                            if (urlA && urlA.hostname && urlA.hostname !== window.location.hostname) unsafeForms++;
                        }
                    }
                    features.sfh = forms.length === 0 ? 0 : (unsafeForms / forms.length);

                    var iframes = document.getElementsByTagName('iframe');
                    var invisibleIframeCount = 0;
                    for (var ifi = 0; ifi < iframes.length; ifi++) {
                        var iframe = iframes[ifi];
                        var width = iframe.getAttribute('width') || iframe.width || '';
                        var height = iframe.getAttribute('height') || iframe.height || '';
                        var border = iframe.getAttribute('frameborder') || iframe.getAttribute('border') || '';
                        var style = iframe.getAttribute('style') || '';
                        if ((width === '0' || width === 0) && (height === '0' || height === 0)) {
                            invisibleIframeCount++;
                        }
                        if (border === '0' && style.indexOf('border:none') !== -1 && (width === '0' || height === '0')) {
                            invisibleIframeCount++;
                        }
                    }
                    features.iframe = invisibleIframeCount > 0 ? 1 : 0;

                    var hasPopup = false;
                    var scripts = document.getElementsByTagName('script');
                    for (var si = 0; si < scripts.length && !hasPopup; si++) {
                        var scriptContent = scripts[si].textContent || '';
                        if (scriptContent.indexOf('prompt(') !== -1) hasPopup = true;
                    }
                    features.popup_window = hasPopup ? 1 : 0;

                    var safeAnchors = externalCount;
                    var unsafeAnchors = nullCount;
                    var totalForSafe = safeAnchors + unsafeAnchors;
                    features.safe_anchor = totalForSafe === 0 ? 0 : ((unsafeAnchors / totalForSafe) * 100);

                    var hasOnmouseover = (document.querySelectorAll('[onmouseover]').length > 0);
                    if (!hasOnmouseover && document.body) {
                        hasOnmouseover = document.body.innerHTML.toLowerCase().indexOf('onmouseover="window.status=') !== -1;
                    }
                    features.onmouseover = hasOnmouseover ? 1 : 0;

                    var hasRightClick = false;
                    if (document.body && document.body.oncontextmenu) hasRightClick = true;
                    if (document.querySelectorAll('[oncontextmenu]').length > 0) hasRightClick = true;
                    if (document.body && document.body.innerHTML.match(/event\.button\s*==\s*2/)) hasRightClick = true;
                    features.right_clic = hasRightClick ? 1 : 0;

                    features.empty_title = (document.title.trim() === '') ? 1 : 0;

                    var titleLower = document.title.toLowerCase();
                    var mainDomain = hostParts.length >= 2 ? hostParts[hostParts.length - 2] : hostParts[0];
                    features.domain_in_title = (titleLower.indexOf(mainDomain) !== -1) ? 0 : 1;

                    var bodyTextForCopy = (document.body && document.body.innerText) ? document.body.innerText.toLowerCase() : '';
                    var hasCopyright = (bodyTextForCopy.indexOf('©') !== -1 || bodyTextForCopy.indexOf('copyright') !== -1);
                    features.domain_with_copyright = (hasCopyright && bodyTextForCopy.indexOf(mainDomain) !== -1) ? 0 : 1;

                    Android.receiveFeatures(JSON.stringify(features));
                } catch (e) {
                    console.error('Feature extraction error:', e);
                    Android.receiveFeatures(JSON.stringify({ error: e.message }));
                }
            })();
        """.trimIndent()
    }
}
