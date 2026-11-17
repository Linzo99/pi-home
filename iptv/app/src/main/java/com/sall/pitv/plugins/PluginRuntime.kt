package com.sall.pitv.plugins

import com.sall.pitv.R
import com.sall.pitv.ui.*
import com.sall.pitv.player.*
import com.sall.pitv.iptv.*
import com.sall.pitv.addons.*
import com.sall.pitv.data.*
import com.sall.pitv.net.*

import android.content.res.AssetManager
import android.util.Base64
import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.TreeMap
import java.util.zip.GZIPInputStream

/**
 * Runs one of Nuvio's JavaScript scrapers: a QuickJS engine with the few globals those files
 * expect (console, fetch, atob/btoa, AbortController, module), plus the libraries Nuvio itself
 * lends them through require() (cheerio, crypto-js), bundled in assets/jsmodules by
 * jsmodules/build.sh.  The scripts are the user's choice, like the add-ons.
 *
 * fetch is answered from Kotlin with HttpURLConnection: the scripts use it synchronously through
 * a resolved Promise, so no timer loop is needed.  That is also why setTimeout is deliberately
 * absent: the scrapers fall back to their own deadline loops when it is missing.
 */
internal object PluginRuntime {
    private const val TAG = "PiTV"
    /**
     * The engine keeps one process-wide JNI cache (class refs and a plain counter) and rewrites
     * it on every create and on the last close. Created or closed at the same time from several
     * threads, two engines delete a class ref the other still holds, and the VM aborts with
     * "attempt to remove stale Global". So only the setup and teardown of engines are serialized
     * here; the scripts themselves keep running in parallel.
     */
    private val engineLifecycle = Any()

    /** require() name -> bundle in assets/jsmodules; a bundle is compiled once per process */
    private val MODULES = mapOf(
        "cheerio-without-node-native" to "cheerio",
        "cheerio" to "cheerio",
        "crypto-js" to "cryptojs"
    )
    private val compiled = HashMap<String, ByteArray>()
    /** the bundles a script turned out to need although its source does not name them */
    private val learned = HashMap<String, MutableSet<String>>()
    private var assets: AssetManager? = null

    @JvmStatic
    fun init(a: AssetManager) {
        assets = a
    }
    /** the longest one script may run, retries included */
    private const val RUN_MS = 75_000L
    private const val MAX_BODY = 16 * 1024 * 1024
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    /** the array of streams, or {"error": "..."} */
    @JvmStatic
    fun run(
        codeFile: File,
        cacheFile: File,
        scraperId: String,
        tmdbId: String,
        mediaType: String,
        season: Int,
        episode: Int,
        settingsJson: String?
    ): String {
        if (!codeFile.isFile) return "{\"error\":\"plugin not downloaded\"}"
        val source = codeFile.readText()
        val bundles = HashSet<String>()
        for ((name, bundle) in MODULES) {
            if (source.contains("'" + name + "'") || source.contains("\"" + name + "\"")) bundles.add(bundle)
        }
        synchronized(learned) { learned[codeFile.path]?.let { bundles.addAll(it) } }
        val deadline = System.currentTimeMillis() + RUN_MS
        while (true) {
            val result = attempt(codeFile, cacheFile, source, bundles, deadline, scraperId, tmdbId, mediaType, season, episode, settingsJson)
            // an obfuscated script names its module only at run time: run it again, in a fresh
            // engine, with that library, and remember it for the next runs
            val missing = Regex("module '([^']+)' is not available").find(result)?.groupValues?.get(1)
            val bundle = MODULES[missing] ?: return result
            if (!bundles.add(bundle)) return result
            synchronized(learned) { learned.getOrPut(codeFile.path) { HashSet() }.add(bundle) }
        }
    }

    private fun attempt(
        codeFile: File,
        cacheFile: File,
        source: String,
        bundles: Set<String>,
        deadline: Long,
        scraperId: String,
        tmdbId: String,
        mediaType: String,
        season: Int,
        episode: Int,
        settingsJson: String?
    ): String {
        var result = ""
        runBlocking(Dispatchers.IO) {
            val done = withTimeoutOrNull(RUN_MS) {
                var qjs: QuickJs? = null
                try {
                    qjs = synchronized(engineLifecycle) { QuickJs.create(Dispatchers.IO) }
                    var captured = ""
                    qjs.bindConsole(scraperId)
                    qjs.function("__capture_result") { args ->
                        captured = args.getOrNull(0)?.toString() ?: ""
                        null
                    }
                    qjs.function("__native_fetch") { args ->
                        // a script blocked in fetch cannot be cancelled from outside: past the
                        // deadline every request fails at once, so the script ends and frees its thread
                        val left = deadline - System.currentTimeMillis()
                        if (left <= 0) return@function "{\"status\":0,\"statusText\":\"deadline\",\"headers\":{},\"bodyText\":\"\",\"bodyB64\":\"\"}"
                        nativeFetch(
                            timeLeft = left.toInt(),
                            method = args.getOrNull(0)?.toString() ?: "GET",
                            url = args.getOrNull(1)?.toString() ?: "",
                            headersJson = args.getOrNull(2)?.toString() ?: "{}",
                            body = args.getOrNull(3)?.toString(),
                            followRedirects = args.getOrNull(4)?.toString() != "false"
                        )
                    }
                    qjs.function("__b64decode") { args ->
                        String(Base64.decode(args.getOrNull(0)?.toString() ?: "", Base64.DEFAULT), Charsets.ISO_8859_1)
                    }
                    qjs.function("__b64encode") { args ->
                        Base64.encodeToString((args.getOrNull(0)?.toString() ?: "").toByteArray(Charsets.ISO_8859_1), Base64.NO_WRAP)
                    }
                    qjs.evaluate<Any?>(SHIMS, "shims.js", false)
                    qjs.evaluate<Any?>(settingsScript(settingsJson), "settings.js", false)
                    for (bundle in bundles) qjs.evaluate<Any?>(module(qjs, bundle))
                    var bytecode: ByteArray? = null
                    if (cacheFile.isFile) {
                        try {
                            bytecode = cacheFile.readBytes()
                        } catch (e: Exception) {
                            Log.w(TAG, "plugin cache " + scraperId, e)
                        }
                    }
                    if (bytecode == null) {
                        bytecode = qjs.compile(source, scraperId + ".js", false)
                        writeCache(cacheFile, bytecode, scraperId)
                    }
                    // no second try in this engine: a script that failed half-way has already
                    // declared its globals.  The cache folder is per install, so never stale.
                    qjs.evaluate<Any?>(bytecode)
                    qjs.evaluate<Any?>(callScript(tmdbId, mediaType, season, episode, scraperId), "call.js", false)
                    result = captured
                } catch (e: Exception) {
                    Log.w(TAG, "plugin " + scraperId, e)
                    result = "{\"error\":" + JSONObject.quote(e.message ?: e.javaClass.simpleName) + "}"
                } finally {
                    qjs?.let { q -> synchronized(engineLifecycle) { q.close() } }
                }
                true
            }
            if (done == null) {
                Log.w(TAG, "plugin " + scraperId + " timed out")
                result = "{\"error\":\"timeout\"}"
            }
        }
        return result
    }

    private fun module(qjs: QuickJs, bundle: String): ByteArray {
        synchronized(compiled) { compiled[bundle]?.let { return it } }
        val a = assets ?: throw IllegalStateException("PluginRuntime.init was not called")
        val code = a.open("jsmodules/$bundle.js").use { it.readBytes().toString(Charsets.UTF_8) }
        val bytes = qjs.compile(code, "$bundle.js", false)
        synchronized(compiled) { compiled[bundle] = bytes }
        return bytes
    }

    private fun com.dokar.quickjs.QuickJs.bindConsole(scraperId: String) {
        val log = { args: Array<Any?> ->
            Log.d(TAG, "plugin[" + scraperId + "] " + args.joinToString(" ") { arg ->
                when (arg) {
                    null -> "null"
                    is String -> arg
                    else -> try {
                        JSONObject.wrap(arg)?.toString() ?: arg.toString()
                    } catch (e: Exception) {
                        arg.toString()
                    }
                }
            })
            null
        }
        define("console") {
            function("log") { args -> log(args) }
            function("info") { args -> log(args) }
            function("warn") { args -> log(args) }
            function("debug") { args -> log(args) }
            function("error") { args -> log(args) }
            function("trace") { args -> log(args) }
            function("group") { args -> log(args) }
            function("groupEnd") { _ -> null }
            function("table") { _ -> null }
        }
    }

    private fun settingsScript(json: String?): String {
        val settings = try {
            if (json.isNullOrBlank()) JSONObject() else JSONObject(json)
        } catch (e: Exception) {
            JSONObject()
        }
        return "globalThis.SCRAPER_SETTINGS = " + settings + ";"
    }

    private fun callScript(tmdbId: String, mediaType: String, season: Int, episode: Int, scraperId: String): String {
        val args = ArrayList<String>(4)
        args.add(JSONObject.quote(tmdbId))
        args.add(JSONObject.quote(mediaType))
        args.add(if (season > 0) season.toString() else "null")
        args.add(if (episode > 0) episode.toString() else "null")
        return """
            (async function () {
              try {
                var fn = globalThis.getStreams ||
                  (typeof module !== 'undefined' && module.exports && module.exports.getStreams) ||
                  (typeof exports !== 'undefined' && exports.getStreams) || null;
                if (!fn) { __capture_result(JSON.stringify({error: 'getStreams missing'})); return; }
                var r = await fn(${args.joinToString(",")});
                if (r && !Array.isArray(r) && Array.isArray(r.streams)) r = r.streams;
                __capture_result(JSON.stringify(Array.isArray(r) ? r : []));
              } catch (e) {
                __capture_result(JSON.stringify({error: String((e && (e.stack || e.message)) || e)}));
              }
            })();
        """.trimIndent()
    }

    private fun nativeFetch(timeLeft: Int, method: String, url: String, headersJson: String, body: String?, followRedirects: Boolean): String {
        val requestHeaders = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        try {
            val h = JSONObject(headersJson)
            for (k in h.keys()) {
                val v = h.opt(k)
                if (v != null && v != JSONObject.NULL) requestHeaders[k] = v.toString()
            }
        } catch (e: Exception) {
            Log.w(TAG, "plugin headers", e)
        }
        if (!requestHeaders.containsKey("User-Agent")) requestHeaders["User-Agent"] = UA
        if (!requestHeaders.containsKey("Accept")) requestHeaders["Accept"] = "*/*"
        if (!requestHeaders.containsKey("Accept-Language")) requestHeaders["Accept-Language"] = "fr-FR,fr;q=0.9,en;q=0.8"
        if (!requestHeaders.containsKey("Accept-Encoding")) requestHeaders["Accept-Encoding"] = "gzip"

        var current = url
        var verb = method.uppercase()
        var payload = body
        var redirects = 0
        while (true) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(current).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = minOf(15_000, timeLeft)
                conn.readTimeout = minOf(25_000, timeLeft)
                conn.requestMethod = verb
                for ((k, v) in requestHeaders) conn.setRequestProperty(k, v)
                if (payload != null && verb != "GET" && verb != "HEAD") {
                    val bytes = payload.toByteArray(Charsets.UTF_8)
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(bytes.size)
                    conn.outputStream.use { it.write(bytes) }
                }
                val status = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (followRedirects && status in 301..308 && !location.isNullOrBlank() && redirects < 8) {
                    val next = URL(URL(current), location).toString()
                    if (status != 307 && status != 308) {
                        if (status == 303 || (status <= 302 && verb != "HEAD")) {
                            verb = "GET"
                            payload = null
                            requestHeaders.remove("Content-Type")
                            requestHeaders.remove("Content-Length")
                        }
                    }
                    current = next
                    redirects++
                    conn.disconnect()
                    continue
                }
                val bytes = try {
                    (if (status >= 400) conn.errorStream else conn.inputStream)?.use { readBody(it, conn.contentEncoding) } ?: ByteArray(0)
                } catch (e: Exception) {
                    conn.errorStream?.use { readBody(it, conn.contentEncoding) } ?: ByteArray(0)
                }
                val text = decode(bytes, conn.contentType)
                val headers = JSONObject()
                for ((k, values) in conn.headerFields) {
                    if (k.isNullOrEmpty() || values.isNullOrEmpty()) continue
                    headers.put(k.lowercase(), values.joinToString(", "))
                }
                val out = JSONObject()
                out.put("status", status)
                out.put("statusText", statusText(status))
                out.put("url", current)
                out.put("redirected", redirects > 0)
                out.put("headers", headers)
                out.put("bodyText", text)
                out.put("bodyB64", if (isTextual(conn.contentType) || bytes.size > 512 * 1024) "" else Base64.encodeToString(bytes, Base64.NO_WRAP))
                return out.toString()
            } catch (e: Exception) {
                val out = JSONObject()
                out.put("status", 0)
                out.put("statusText", e.message ?: e.javaClass.simpleName)
                out.put("url", current)
                out.put("headers", JSONObject())
                out.put("bodyText", "")
                out.put("bodyB64", "")
                return out.toString()
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun readBody(stream: java.io.InputStream, encoding: String?): ByteArray {
        var input = stream
        if ("gzip".equals(encoding, ignoreCase = true)) input = GZIPInputStream(input)
        input.use {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BODY) break
            }
            return out.toByteArray()
        }
    }

    private fun isTextual(type: String?): Boolean {
        val t = type?.lowercase() ?: return true
        return t.startsWith("text/") || t.contains("json") || t.contains("xml") ||
                t.contains("javascript") || t.contains("x-www-form-urlencoded")
    }

    private fun decode(bytes: ByteArray, contentType: String?): String {
        val charset = Regex("charset=([A-Za-z0-9_\\-]+)").find(contentType ?: "")?.groupValues?.get(1)
        return try {
            if (charset != null) String(bytes, java.nio.charset.Charset.forName(charset.trim())) else String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            String(bytes, Charsets.UTF_8)
        }
    }

    private fun writeCache(file: File, bytes: ByteArray, scraperId: String) {
        try {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        } catch (e: Exception) {
            Log.w(TAG, "plugin cache write " + scraperId, e)
        }
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> ""
    }

    private const val SHIMS = """
(function () {
  if (typeof globalThis === 'undefined') { this.globalThis = this; }
  var g = globalThis;
  g.global = g; g.window = g; g.self = g;
  g.module = { exports: {} };
  g.exports = g.module.exports;
  g.process = { env: {}, platform: 'android', version: '', versions: {}, nextTick: function (f) { Promise.resolve().then(f); } };
  g.TMDB_API_KEY = '';
  g.SCRAPER_ID = '';
  g.SCRAPER_SETTINGS = {};

  function str(v) {
    if (typeof v === 'string') return v;
    try { return JSON.stringify(v); } catch (e) { return String(v); }
  }
  g.atob = function (s) { return __b64decode(String(s).replace(/[^A-Za-z0-9+/=]/g, '')); };
  g.btoa = function (s) { return __b64encode(String(s)); };

  function AbortSignalLike() {
    this.aborted = false;
    this.reason = undefined;
    this._listeners = [];
    this.addEventListener = function (type, fn) { if (type === 'abort' && fn) this._listeners.push(fn); };
    this.removeEventListener = function (type, fn) {
      if (type !== 'abort') return;
      var i = this._listeners.indexOf(fn); if (i >= 0) this._listeners.splice(i, 1);
    };
    this.throwIfAborted = function () { if (this.aborted) throw this.reason || new Error('Aborted'); };
  }
  AbortSignalLike.timeout = function () { return new AbortSignalLike(); };
  AbortSignalLike.abort = function () { var s = new AbortSignalLike(); s.aborted = true; return s; };
  g.AbortSignal = AbortSignalLike;
  g.AbortController = function () {
    var self = this;
    this.signal = new AbortSignalLike();
    this.abort = function (reason) {
      if (self.signal.aborted) return;
      self.signal.aborted = true;
      self.signal.reason = reason;
      var l = self.signal._listeners.slice();
      for (var i = 0; i < l.length; i++) { try { l[i](); } catch (e) {} }
    };
  };
  g.clearTimeout = function () {};

  function HeaderBag(obj) {
    this._h = {};
    var src = obj || {};
    if (src && typeof src.forEach === 'function' && !Array.isArray(src)) {
      var self = this;
      src.forEach(function (v, k) { self._h[String(k).toLowerCase()] = String(v); });
    } else if (Array.isArray(src)) {
      for (var i = 0; i < src.length; i++) {
        var p = src[i];
        if (p && p.length >= 2) this._h[String(p[0]).toLowerCase()] = String(p[1]);
      }
    } else {
      for (var k in src) if (Object.prototype.hasOwnProperty.call(src, k) && src[k] != null) this._h[String(k).toLowerCase()] = String(src[k]);
    }
  }
  HeaderBag.prototype.get = function (n) { var v = this._h[String(n).toLowerCase()]; return v == null ? null : v; };
  HeaderBag.prototype.has = function (n) { return this._h[String(n).toLowerCase()] != null; };
  HeaderBag.prototype.forEach = function (cb) { for (var k in this._h) cb(this._h[k], k); };
  HeaderBag.prototype.entries = function () {
    var out = [], h = this._h;
    for (var k in h) out.push([k, h[k]]);
    return out[Symbol.iterator] ? out[Symbol.iterator]() : out;
  };
  HeaderBag.prototype.keys = function () { return Object.keys(this._h).values(); };

  function responseFrom(r, fallbackUrl) {
    var headers = new HeaderBag(r.headers || {});
    var text = r.bodyText == null ? '' : r.bodyText;
    var b64 = r.bodyB64 || '';
    var res = {
      ok: r.status >= 200 && r.status < 300,
      status: r.status,
      statusText: r.statusText || '',
      url: r.url || fallbackUrl,
      redirected: !!r.redirected,
      type: 'basic',
      headers: headers,
      text: function () { return Promise.resolve(text); },
      json: function () { try { return Promise.resolve(JSON.parse(text)); } catch (e) { return Promise.reject(e); } },
      blob: function () { return Promise.resolve({ size: b64 ? b64.length : text.length, type: headers.get('content-type') || '' }); },
      arrayBuffer: function () {
        var s = b64 ? __b64decode(b64) : text;
        var u = new Uint8Array(s.length);
        for (var i = 0; i < s.length; i++) u[i] = s.charCodeAt(i) & 255;
        return Promise.resolve(u.buffer);
      },
      clone: function () { return responseFrom(r, fallbackUrl); }
    };
    return res;
  }

  g.fetch = function (input, init) {
    init = init || {};
    return new Promise(function (resolve, reject) {
      try {
        var url = (typeof input === 'string') ? input : (input && input.url) || String(input);
        var method = (init.method || (input && input.method) || 'GET').toUpperCase();
        var signal = init.signal || (input && input.signal);
        if (signal && signal.aborted) { var e = new Error('Aborted'); e.name = 'AbortError'; reject(e); return; }
        var rawHeaders = init.headers || (input && input.headers) || {};
        var bag = new HeaderBag(rawHeaders);
        var headersObj = {};
        bag.forEach(function (v, k) { headersObj[k] = v; });
        var body = init.body;
        if (body != null && typeof body !== 'string') {
          if (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) body = body.toString();
          else body = str(body);
        }
        var raw = __native_fetch(method, url, JSON.stringify(headersObj), body == null ? '' : String(body), init.redirect !== 'manual');
        var r = JSON.parse(raw);
        if (!r.status) {
          reject(new Error(r.statusText || ('fetch failed: ' + url)));
          return;
        }
        resolve(responseFrom(r, url));
      } catch (err) {
        reject(err);
      }
    });
  };

  function URLSearchParamsPoly(init) {
    this._p = [];
    if (typeof init === 'string') {
      var s = init.charAt(0) === '?' ? init.slice(1) : init;
      var parts = s ? s.split('&') : [];
      for (var i = 0; i < parts.length; i++) {
        if (!parts[i]) continue;
        var j = parts[i].indexOf('=');
        var k = j < 0 ? parts[i] : parts[i].slice(0, j);
        var v = j < 0 ? '' : parts[i].slice(j + 1);
        this._p.push([decodeURIComponent(k.replace(/\+/g, ' ')), decodeURIComponent(v.replace(/\+/g, ' '))]);
      }
    } else if (init && typeof init === 'object') {
      for (var key in init) if (Object.prototype.hasOwnProperty.call(init, key)) this._p.push([key, String(init[key])]);
    }
  }
  URLSearchParamsPoly.prototype.append = function (k, v) { this._dirty = true; this._p.push([String(k), String(v)]); };
  URLSearchParamsPoly.prototype.set = function (k, v) {
    this._dirty = true;
    k = String(k);
    for (var i = 0; i < this._p.length; i++) if (this._p[i][0] === k) { this._p.splice(i, 1); break; }
    this._p.push([k, String(v)]);
  };
  URLSearchParamsPoly.prototype.get = function (k) {
    for (var i = 0; i < this._p.length; i++) if (this._p[i][0] === String(k)) return this._p[i][1];
    return null;
  };
  URLSearchParamsPoly.prototype.has = function (k) { return this.get(k) != null; };
  URLSearchParamsPoly.prototype.delete = function (k) {
    this._dirty = true;
    for (var i = this._p.length - 1; i >= 0; i--) if (this._p[i][0] === String(k)) this._p.splice(i, 1);
  };
  URLSearchParamsPoly.prototype.toString = function () {
    var out = [];
    for (var i = 0; i < this._p.length; i++) out.push(encodeURIComponent(this._p[i][0]) + '=' + encodeURIComponent(this._p[i][1]));
    return out.join('&');
  };
  URLSearchParamsPoly.prototype.forEach = function (cb, self) {
    for (var i = 0; i < this._p.length; i++) cb.call(self, this._p[i][1], this._p[i][0], this);
  };
  g.URLSearchParams = URLSearchParamsPoly;

  function resolveUrl(u, base) {
    u = String(u);
    if (/^[a-z][a-z0-9+.\-]*:/i.test(u)) return u;
    if (!base) return u;
    var b = String(base);
    if (u.slice(0, 2) === '//') return b.split(':')[0] + ':' + u;
    var m = b.match(/^([a-z][a-z0-9+.\-]*:)\/\/([^\/?#]+)([^?#]*)/i);
    if (!m) return u;
    if (u.charAt(0) === '/') return m[1] + '//' + m[2] + u;
    var dir = m[3].replace(/[^\/]*$/, '');
    return m[1] + '//' + m[2] + dir + u;
  }
  // href, search and origin are worked out from the parts, so changing searchParams or
  // pathname changes the link, as in a browser
  function URLPoly(u, base) {
    var full = resolveUrl(u, base);
    var m = full.match(/^([a-z][a-z0-9+.\-]*:)\/\/([^\/?#]*)([^?#]*)(\?[^#]*)?(#.*)?$/i);
    this._raw = m ? '' : full;
    this.protocol = m ? m[1] : '';
    this.host = m ? m[2] : '';
    this.hostname = m ? m[2].replace(/:\d+$/, '') : '';
    this.port = m && /:(\d+)$/.test(m[2]) ? m[2].replace(/^.*:/, '') : '';
    this.pathname = m ? (m[3] || '/') : full;
    this.search = m && m[4] ? m[4] : '';
    this.hash = m && m[5] ? m[5] : '';
  }
  Object.defineProperty(URLPoly.prototype, 'search', {
    get: function () {
      if (!this.searchParams._dirty) return this._search;
      var q = this.searchParams.toString();
      return q ? '?' + q : '';
    },
    set: function (v) {
      v = String(v);
      this._search = v && v.charAt(0) !== '?' ? '?' + v : (v === '?' ? '' : v);
      this.searchParams = new URLSearchParamsPoly(this._search);
    }
  });
  Object.defineProperty(URLPoly.prototype, 'origin', {
    get: function () { return this.protocol && this.host ? this.protocol + '//' + this.host : ''; }
  });
  Object.defineProperty(URLPoly.prototype, 'href', {
    get: function () { return this._raw || this.protocol + '//' + this.host + this.pathname + this.search + this.hash; }
  });
  URLPoly.prototype.toString = function () { return this.href; };
  URLPoly.prototype.toJSON = function () { return this.href; };
  g.URL = URLPoly;

  // the part of axios the scrapers use, over fetch: data is parsed JSON when it can be
  function axiosRequest(cfg) {
    var url = cfg.url;
    if (cfg.params) {
      var q = new URLSearchParamsPoly(cfg.params).toString();
      if (q) url += (url.indexOf('?') < 0 ? '?' : '&') + q;
    }
    var body = cfg.data;
    var headers = cfg.headers || {};
    if (body != null && typeof body === 'object' && !(body instanceof URLSearchParamsPoly)) {
      body = JSON.stringify(body);
      if (!new HeaderBag(headers).has('content-type')) headers = Object.assign({ 'Content-Type': 'application/json' }, headers);
    }
    return g.fetch(url, { method: cfg.method || 'GET', headers: headers, body: body,
        redirect: cfg.maxRedirects === 0 ? 'manual' : 'follow' }).then(function (r) {
      return r.text().then(function (text) {
        var data = text;
        if (cfg.responseType !== 'text') { try { data = JSON.parse(text); } catch (e) {} }
        var out = { data: data, status: r.status, statusText: r.statusText, headers: r.headers._h, config: cfg, request: { responseURL: r.url } };
        var ok = cfg.validateStatus ? cfg.validateStatus(r.status) : r.status >= 200 && r.status < 300;
        if (ok) return out;
        var err = new Error('Request failed with status code ' + r.status);
        err.response = out;
        throw err;
      });
    });
  }
  function axios(a, b) {
    return axiosRequest(typeof a === 'string' ? Object.assign({}, b, { url: a }) : a);
  }
  ['get', 'delete', 'head'].forEach(function (m) {
    axios[m] = function (url, cfg) { return axiosRequest(Object.assign({}, cfg, { url: url, method: m.toUpperCase() })); };
  });
  ['post', 'put', 'patch'].forEach(function (m) {
    axios[m] = function (url, data, cfg) { return axiosRequest(Object.assign({}, cfg, { url: url, data: data, method: m.toUpperCase() })); };
  });
  axios.create = function (defaults) {
    defaults = defaults || {};
    function merged(cfg) {
      cfg = Object.assign({}, defaults, cfg);
      cfg.headers = Object.assign({}, defaults.headers, cfg && cfg.headers);
      if (defaults.baseURL && !/^[a-z]+:/i.test(cfg.url)) cfg.url = resolveUrl(cfg.url, defaults.baseURL.replace(/\/?$/, '/'));
      return cfg;
    }
    var inst = function (a, b) { return axiosRequest(merged(typeof a === 'string' ? Object.assign({}, b, { url: a }) : a)); };
    ['get', 'delete', 'head'].forEach(function (m) {
      inst[m] = function (url, cfg) { return axiosRequest(merged(Object.assign({}, cfg, { url: url, method: m.toUpperCase() }))); };
    });
    ['post', 'put', 'patch'].forEach(function (m) {
      inst[m] = function (url, data, cfg) { return axiosRequest(merged(Object.assign({}, cfg, { url: url, data: data, method: m.toUpperCase() }))); };
    });
    return inst;
  };
  axios.default = axios;
  g.axios = axios;

  g.require = function (name) {
    if (name === 'util') return { types: { isArrayBuffer: function (x) { return x instanceof ArrayBuffer; } } };
    var m = g.__modules || {};
    if ((name === 'cheerio-without-node-native' || name === 'cheerio') && m.cheerio) return m.cheerio;
    if (name === 'crypto-js' && m.cryptojs) return m.cryptojs;
    if (name === 'axios') return axios;
    if (name === 'url') return { URL: g.URL, URLSearchParams: g.URLSearchParams };
    throw new Error("module '" + name + "' is not available");
  };
})();
"""
}
