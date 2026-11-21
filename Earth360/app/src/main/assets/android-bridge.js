/* Android bridge: loaded AFTER the original page script.
   Only active inside the APK (window.Android exists). Original logic is untouched. */
(function () {
  'use strict';
  if (!window.Android) return;

  var $ = function (id) { return document.getElementById(id); };

  // ── 1. Save files through MediaStore (blob <a download> does not work in WebView) ──
  window.downloadBytes = function (bytes, name, mime) {
    try {
      Android.beginSave(name, mime || 'image/jpeg');
      var CHUNK = 3 * 256 * 1024;            // multiple of 3 -> clean base64 pieces
      var STEP = 0x8000;
      for (var i = 0; i < bytes.length; i += CHUNK) {
        var sub = bytes.subarray(i, Math.min(i + CHUNK, bytes.length));
        var bin = '';
        for (var j = 0; j < sub.length; j += STEP) {
          bin += String.fromCharCode.apply(null, sub.subarray(j, j + STEP));
        }
        Android.writeChunk(btoa(bin));
      }
      Android.endSave();
    } catch (e) {
      try { Android.abortSave(); } catch (_) {}
      setStatus('Save failed: ' + e, 'error');
    }
  };

  // ── 2. "Open in Maps" -> external browser/Maps app ──
  window.openInMaps = function () {
    if (currentMapsUrl) Android.openExternal(currentMapsUrl);
  };

  // ── 3. Automatic background link capture (hidden desktop-mode browser) ──
  var timer = null;
  var mode = 'maps';                         // 'maps' = fill Step 2, 'short' = resolve a short link
  var _onUrlChange = window.onUrlChange;
  var _showMapsBox = window.showMapsBox;
  var _clearAll = window.clearAll;

  function autoExtract(url, m) {
    if (!url) return;
    clearTimeout(timer);
    timer = setTimeout(function () {
      mode = m;
      setStatus('\u23F3 Opening Google Maps in the background (desktop mode) to grab the link\u2026', 'info');
      Android.startExtract(url);
    }, 500);
  }
  function cancelAuto() {
    clearTimeout(timer);
    try { Android.cancelExtract(); } catch (_) {}
  }

  // Maps box shown (coords only / user pano without token) -> start capturing automatically
  window.showMapsBox = function (det) {
    _showMapsBox(det);
    if (currentMapsUrl) {
      var ins = document.querySelector('.maps-instruction');
      if (ins) ins.innerHTML = '<strong>Auto-capture running</strong> in the background. ' +
        'If it fails, open the link, copy the address-bar URL and paste it below.';
      autoExtract(currentMapsUrl, 'maps');
    }
  };

  // Typing/pasting a new URL cancels any running capture; short links are resolved automatically
  window.onUrlChange = function () {
    cancelAuto();
    _onUrlChange();
    var val = $('urlInput').value.trim();
    if (val && !currentDetection && !currentMapsUrl &&
        /^https?:\/\/(maps\.app\.goo\.gl|goo\.gl\/maps|earth\.app\.goo\.gl|g\.co\/kgs)/i.test(val)) {
      autoExtract(val, 'short');
    }
  };

  window.clearAll = function () { cancelAuto(); _clearAll(); };

  // Called by native code for every URL seen by the hidden browser. Returns true when captured.
  window.onAutoUrl = function (url) {
    var r;
    try { r = extractFromUrl(url); } catch (e) { return false; }
    if (!r || !r.panoId) return false;
    if (r.panoType === 'user' && !r.lh3Url) return false;   // wait until the signed token appears
    if (mode === 'short') {
      $('urlInput').value = url;
      _onUrlChange();
    } else {
      $('step2Input').value = url;
      onStep2Change();
    }
    setStatus('\u2714 Link captured automatically in the background.\nPress Download.', 'success');
    return true;
  };

  window.onExtractFail = function (msg) {
    setStatus(msg + '\nOpen the link manually, copy the address-bar URL and paste it in Step 2.', 'warn');
  };
})();
