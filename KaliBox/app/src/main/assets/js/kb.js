(function () {
  if (window.__kbInstalled) { return; }
  window.__kbInstalled = true;
  window.__kbTouchpad = false;

  function cv() {
    return document.querySelector('#noVNC_container canvas') || document.querySelector('canvas');
  }

  // ---- synthetic keyboard (noVNC listens on the canvas) ----
  function fire(type, key, code, kc) {
    var c = cv();
    if (!c) { return; }
    c.focus();
    c.dispatchEvent(new KeyboardEvent(type, {
      key: key, code: code, keyCode: kc, which: kc, bubbles: true, cancelable: true
    }));
  }
  window.kbKey = function (key, code, kc, mode) {
    if (mode === 'down') { fire('keydown', key, code, kc); }
    else if (mode === 'up') { fire('keyup', key, code, kc); }
    else { fire('keydown', key, code, kc); fire('keyup', key, code, kc); }
  };

  // ---- virtual touchpad ----
  var px = 200, py = 200, last = null, startT = 0, moved = false, fingers = 0;
  var lpTimer = null, lpFired = false, lastTap = 0, scrollLast = null;

  function ptr(type, button, x, y) {
    var c = cv();
    if (!c) { return; }
    var r = c.getBoundingClientRect();
    c.dispatchEvent(new MouseEvent(type, {
      bubbles: true, cancelable: true,
      clientX: r.left + x, clientY: r.top + y,
      button: button,
      buttons: type === 'mousedown' ? (button === 2 ? 2 : 1) : 0
    }));
  }
  function bounds() {
    var c = cv();
    if (!c) { return { w: 800, h: 600 }; }
    var r = c.getBoundingClientRect();
    return { w: r.width, h: r.height };
  }
  function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

  window.kbSetTouchpad = function (on) {
    window.__kbTouchpad = !!on;
    if (on) { var b = bounds(); px = b.w / 2; py = b.h / 2; }
  };
  window.kbHold = function (on) { ptr(on ? 'mousedown' : 'mouseup', 0, px, py); };

  function ts(e) {
    if (!window.__kbTouchpad) { return; }
    e.preventDefault(); e.stopPropagation();
    fingers = e.touches.length;
    if (fingers === 1) {
      last = { x: e.touches[0].clientX, y: e.touches[0].clientY };
      startT = Date.now(); moved = false; lpFired = false;
      clearTimeout(lpTimer);
      lpTimer = setTimeout(function () {
        if (!moved) { ptr('mousedown', 2, px, py); ptr('mouseup', 2, px, py); lpFired = true; }
      }, 600);
    } else if (fingers === 2) {
      clearTimeout(lpTimer);
      scrollLast = (e.touches[0].clientY + e.touches[1].clientY) / 2;
      moved = true;
    }
  }
  function tm(e) {
    if (!window.__kbTouchpad) { return; }
    e.preventDefault(); e.stopPropagation();
    var b = bounds();
    if (e.touches.length === 1 && last) {
      var dx = e.touches[0].clientX - last.x, dy = e.touches[0].clientY - last.y;
      last = { x: e.touches[0].clientX, y: e.touches[0].clientY };
      if (Math.abs(dx) + Math.abs(dy) > 2) { moved = true; clearTimeout(lpTimer); }
      px = clamp(px + dx * 1.6, 0, b.w - 1);
      py = clamp(py + dy * 1.6, 0, b.h - 1);
      ptr('mousemove', 0, px, py);
    } else if (e.touches.length === 2) {
      var mid = (e.touches[0].clientY + e.touches[1].clientY) / 2;
      var d = mid - scrollLast;
      if (Math.abs(d) > 6) {
        var c = cv();
        if (c) {
          var r = c.getBoundingClientRect();
          c.dispatchEvent(new WheelEvent('wheel', {
            bubbles: true, cancelable: true, deltaMode: 0, deltaY: -d * 3,
            clientX: r.left + px, clientY: r.top + py
          }));
        }
        scrollLast = mid;
      }
    }
  }
  function te(e) {
    if (!window.__kbTouchpad) { return; }
    e.preventDefault(); e.stopPropagation();
    clearTimeout(lpTimer);
    if (e.touches.length === 0) {
      if (fingers === 1 && !moved && !lpFired && Date.now() - startT < 300) {
        ptr('mousedown', 0, px, py); ptr('mouseup', 0, px, py);  // tap = click; two taps = double click
        lastTap = Date.now();
      }
      fingers = 0;
    }
  }
  var opt = { capture: true, passive: false };
  document.addEventListener('touchstart', ts, opt);
  document.addEventListener('touchmove', tm, opt);
  document.addEventListener('touchend', te, opt);
  document.addEventListener('touchcancel', te, opt);
})();
