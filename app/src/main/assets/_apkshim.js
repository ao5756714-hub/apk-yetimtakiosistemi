(function(){
  var A = window.AndroidFiles;
  if (!A || window.__apkShim) return;
  window.__apkShim = true;

  function b64(file){
    return new Promise(function(res, rej){
      var r = new FileReader();
      r.onload = function(){ res(String(r.result).split(',')[1] || ''); };
      r.onerror = rej;
      r.readAsDataURL(file);
    });
  }
  try {
    Object.defineProperty(navigator, 'canShare', {configurable: true, writable: true, value: function(d){
      return !!d && ((d.files && d.files.length > 0) || !!d.text || !!d.url || !!d.title);
    }});
    Object.defineProperty(navigator, 'share', {configurable: true, writable: true, value: async function(d){
      d = d || {};
      var out = [];
      if (d.files) {
        for (var i = 0; i < d.files.length; i++) {
          var f = d.files[i];
          out.push({n: f.name || ('file' + i), m: f.type || 'application/octet-stream', b: await b64(f)});
        }
      }
      A.share(d.title || '', d.text || '', d.url || '', JSON.stringify(out));
    }});
  } catch(e){}
  window.print = function(){ A.print(); };

  try {
    var orig = navigator.clipboard;
    var clip = {
      writeText: function(t){ A.copy(String(t)); return Promise.resolve(); },
      readText: function(){ return Promise.resolve(A.paste()); },
      write: function(items){
        if (orig && orig.write) return orig.write(items);
        return Promise.reject(new Error('Desteklenmiyor'));
      },
      read: function(){
        if (orig && orig.read) return orig.read();
        return Promise.reject(new Error('Desteklenmiyor'));
      }
    };
    Object.defineProperty(navigator, 'clipboard', {configurable: true, value: clip});
  } catch(e){}
  try {
    var wl = {request: function(){
      A.keepAwake(true);
      var sentinel = {released: false, type: 'screen', addEventListener: function(){}, removeEventListener: function(){},
        release: function(){ A.keepAwake(false); this.released = true; return Promise.resolve(); }};
      return Promise.resolve(sentinel);
    }};
    Object.defineProperty(navigator, 'wakeLock', {configurable: true, value: wl});
  } catch(e){}

  try {
    var NS = function(title, opts){
      opts = opts || {};
      A.showNote(String(title), String(opts.body || ''), String(opts.tag || ''));
      this.title = String(title);
      this.body = opts.body || '';
      this.close = function(){};
      this.addEventListener = function(){};
      this.removeEventListener = function(){};
    };
    NS.permission = A.notifPerm();
    NS.requestPermission = function(cb){
      return new Promise(function(res){
        window.__notifResolve = function(p){ NS.permission = p; if (cb) cb(p); res(p); };
        A.notifRequest();
      });
    };
    Object.defineProperty(window, 'Notification', {configurable: true, writable: true, value: NS});
  } catch(e){}

  try {
    window.showSaveFilePicker = function(opts){
      opts = opts || {};
      var name = opts.suggestedName || 'dosya', mime = 'application/octet-stream', chunks = [];
      try { mime = Object.keys(opts.types[0].accept)[0] || mime; } catch(e){}
      var handle = {kind: 'file', name: name};
      handle.getFile = function(){ return Promise.resolve(new File(chunks, name, {type: mime})); };
      handle.createWritable = function(){
        chunks = [];
        return Promise.resolve({
          write: function(d){ if (d && d.type === 'write') d = d.data; chunks.push(d); return Promise.resolve(); },
          close: function(){ var b = new Blob(chunks, {type: mime}); return b64(b).then(function(x){ A.saveBlob(name, mime, x); }); },
          abort: function(){ chunks = []; return Promise.resolve(); }
        });
      };
      return Promise.resolve(handle);
    };
    window.showOpenFilePicker = function(opts){
      opts = opts || {};
      return new Promise(function(res, rej){
        var inp = document.createElement('input');
        inp.type = 'file';
        inp.multiple = !!opts.multiple;
        var acc = [];
        (opts.types || []).forEach(function(t){
          Object.keys(t.accept || {}).forEach(function(m){ acc.push(m); (t.accept[m] || []).forEach(function(x){ acc.push(x); }); });
        });
        if (acc.length) inp.accept = acc.join(',');
        inp.onchange = function(){
          var hs = Array.prototype.map.call(inp.files, function(f){
            return {kind: 'file', name: f.name, getFile: function(){ return Promise.resolve(f); }};
          });
          if (hs.length) res(hs); else rej(new DOMException('Iptal', 'AbortError'));
        };
        inp.addEventListener('cancel', function(){ rej(new DOMException('Iptal', 'AbortError')); });
        inp.click();
      });
    };
  } catch(e){}

  var blobs = {}, blobOrder = [];
  try {
    var oCreate = URL.createObjectURL.bind(URL);
    URL.createObjectURL = function(o){
      var u = oCreate(o);
      if (o instanceof Blob) {
        blobs[u] = o; blobOrder.push(u);
        while (blobOrder.length > 30) delete blobs[blobOrder.shift()];
      }
      return u;
    };
  } catch(e){}
  function saveBlobObj(blob, name){
    var mime = blob.type || 'application/octet-stream';
    var id = 'd' + Date.now() + '_' + Math.floor(Math.random() * 100000);
    A.beginSave(id, name, mime);
    var CH = 393216, pos = 0;
    function next(){
      if (pos >= blob.size) { A.endSave(id); return Promise.resolve(); }
      var part = blob.slice(pos, pos + CH);
      pos += CH;
      return b64(part).then(function(x){ A.chunkSave(id, x); return next(); });
    }
    return next();
  }
  window.__apkSaveBlob = saveBlobObj;
  function nameFor(a, blob){
    var n = a.getAttribute('download') || '';
    if (!n) { try { n = decodeURIComponent((a.href || '').split('?')[0].split('/').pop()); } catch(e){} }
    if (!n || n.indexOf('blob:') === 0) n = 'dosya';
    if (n.indexOf('.') < 0) {
      var ext = {'application/pdf': '.pdf', 'image/png': '.png', 'image/jpeg': '.jpg', 'image/webp': '.webp', 'text/plain': '.txt', 'text/html': '.html', 'application/json': '.json', 'text/csv': '.csv'}[blob.type];
      if (ext) n += ext;
    }
    return n;
  }
  function fetchSave(a){
    fetch(a.href).then(function(r){ return r.blob(); }).then(function(b){ saveBlobObj(b, nameFor(a, b)); });
  }
  function handleAnchor(a){
    if (!a || !a.hasAttribute || !a.hasAttribute('download')) return false;
    var h = a.href || '';
    if (h.indexOf('blob:') === 0) {
      var blob = blobs[h];
      if (blob) saveBlobObj(blob, nameFor(a, blob)); else fetchSave(a);
      return true;
    }
    if (h.indexOf('data:') === 0 || h.indexOf('file:') === 0) { fetchSave(a); return true; }
    return false;
  }
  try {
    var oClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function(){ if (handleAnchor(this)) return; return oClick.call(this); };
    var oDisp = HTMLAnchorElement.prototype.dispatchEvent;
    HTMLAnchorElement.prototype.dispatchEvent = function(ev){
      if (ev && ev.type === 'click' && handleAnchor(this)) return true;
      return oDisp.call(this, ev);
    };
    document.addEventListener('click', function(e){
      var a = e.target && e.target.closest ? e.target.closest('a') : null;
      if (a && handleAnchor(a)) { e.preventDefault(); e.stopPropagation(); }
    }, true);
    var oOpen = window.open;
    window.open = function(u){
      if (typeof u === 'string' && (u.indexOf('blob:') === 0 || u.indexOf('data:') === 0)) {
        var a = document.createElement('a');
        a.href = u; a.setAttribute('download', '');
        handleAnchor(a);
        return null;
      }
      return oOpen.apply(window, arguments);
    };
  } catch(e){}

  var inc = [];
  function accepts(inp, f){
    var a = (inp.getAttribute('accept') || '').toLowerCase();
    if (!a) return true;
    var n = f.name.toLowerCase(), t = (f.type || '').toLowerCase();
    return a.split(',').some(function(p){
      p = p.trim();
      if (!p) return false;
      if (p.charAt(0) === '.') return n.slice(-p.length) === p;
      if (p.slice(-2) === '/*') return t.indexOf(p.slice(0, -1)) === 0;
      return t === p;
    });
  }
  function deliver(files){
    var ev = new CustomEvent('apkfile', {detail: {files: files, file: files[0]}, cancelable: true});
    window.dispatchEvent(ev);
    if (ev.defaultPrevented) return;
    var ins = document.querySelectorAll('input[type=file]'), target = null;
    for (var i = 0; i < ins.length && !target; i++) { if (accepts(ins[i], files[0])) target = ins[i]; }
    if (!target) return;
    try {
      var dt = new DataTransfer();
      for (var j = 0; j < files.length; j++) { if (target.multiple || j === 0) dt.items.add(files[j]); }
      target.files = dt.files;
      target.dispatchEvent(new Event('input', {bubbles: true}));
      target.dispatchEvent(new Event('change', {bubbles: true}));
    } catch(e){}
  }
  window.__incomingFile = function(name, mime, b){
    var bin = atob(b), u8 = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
    inc.push(new File([u8], name, {type: mime || ''}));
  };
  window.__incomingDone = function(){
    var files = inc; inc = [];
    if (files.length) deliver(files);
  };
  window.__incomingText = function(t){
    window.dispatchEvent(new CustomEvent('apktext', {detail: {text: t}}));
  };
})();
