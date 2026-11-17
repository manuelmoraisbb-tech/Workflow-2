/*
 * MB Animes — "modo app".
 * Injetado pelo app Android em cada página do site para tirar a aparência de site:
 * esconde cabeçalho/rodapé web (o app já tem barra superior e inferior nativas),
 * remove seleção de texto, realce de toque e barras de scroll, e adiciona feedback ao toque.
 *
 * Para ajustar sem mexer em Kotlin: edita HIDE_SELECTORS ou HIDE_CHROME abaixo.
 */
(function () {
  var HIDE_CHROME = true;               // false = não esconder nada do site
  var HIDE_SELECTORS = [                // seletores certos do teu site (acrescenta os teus aqui)
    'body > header', 'body > nav', 'body > footer', 'footer',
    '.navbar', '.site-header', '.site-footer', '.site-nav', '#header', '#footer', '#navbar'
  ];

  var CSS = [
    'html.mb-app{-webkit-tap-highlight-color:transparent;overscroll-behavior:none;background:#0B0B0F}',
    'html.mb-app body{animation:mbFade .22s ease-out}',
    '@keyframes mbFade{from{opacity:0}to{opacity:1}}',
    'html.mb-app *:not(input):not(textarea){-webkit-user-select:none;user-select:none;-webkit-touch-callout:none}',
    'html.mb-app img{-webkit-user-drag:none}',
    'html.mb-app ::-webkit-scrollbar{display:none}',
    'html.mb-app a img,html.mb-app a:active{transition:transform .14s ease}',
    'html.mb-app a:active img{transform:scale(.965)}',
    'html.mb-app [data-mb-hide]{display:none!important}',
    'html.mb-app.mb-show-chrome [data-mb-hide]{display:revert!important}'
  ].join('\n');

  if (window.__mbApp) { window.__mbApp.setChrome(!!window.__MB_SHOW_CHROME); window.__mbApp.run(); return; }

  var showChrome = !!window.__MB_SHOW_CHROME;
  var timer = null;

  function addStyle() {
    var root = document.documentElement;
    if (!root) return false;
    root.classList.add('mb-app');
    if (!document.getElementById('mb-app-style')) {
      var st = document.createElement('style');
      st.id = 'mb-app-style';
      st.textContent = CSS;
      root.appendChild(st);
    }
    return true;
  }

  function mark(el) {
    if (!el || el.hasAttribute('data-mb-hide')) return;
    // não esconder se tiver vídeo/iframe (player) ou campos de pesquisa
    if (el.querySelector('video,iframe,input,textarea,select')) return;
    el.setAttribute('data-mb-hide', '');
  }

  function hideChrome() {
    if (!HIDE_CHROME || !document.body) return;
    try {
      HIDE_SELECTORS.forEach(function (sel) {
        document.querySelectorAll(sel).forEach(mark);
      });
      // barras fixas coladas ao topo/fundo com vários links (menus do site)
      var w = window.innerWidth, h = window.innerHeight, nodes = [];
      Array.prototype.forEach.call(document.body.children, function (c) {
        nodes.push(c);
        Array.prototype.forEach.call(c.children, function (cc) { nodes.push(cc); });
      });
      nodes.forEach(function (el) {
        var cs = getComputedStyle(el);
        if (cs.position !== 'fixed' && cs.position !== 'sticky') return;
        var r = el.getBoundingClientRect();
        if (r.width < w * 0.9 || r.height > 130 || r.height < 24) return;
        var atTop = r.top <= 2, atBottom = r.bottom >= h - 2;
        if (!atTop && !atBottom) return;
        if (el.querySelectorAll('a').length < 2) return;
        mark(el);
        // remove o espaço que o site reservava para essa barra
        var pad = parseFloat(getComputedStyle(document.body)[atTop ? 'paddingTop' : 'paddingBottom']) || 0;
        if (Math.abs(pad - r.height) < 6) {
          document.body.style.setProperty(atTop ? 'padding-top' : 'padding-bottom', '0', 'important');
        }
      });
    } catch (e) { /* nunca partir a página */ }
  }

  function run() {
    if (!addStyle()) return;
    document.documentElement.classList.toggle('mb-show-chrome', showChrome);
    hideChrome();
  }

  function schedule() {
    if (timer) return;
    timer = setTimeout(function () { timer = null; run(); }, 150);
  }

  window.__mbApp = {
    run: run,
    setChrome: function (v) { showChrome = !!v; run(); }
  };

  run();
  document.addEventListener('DOMContentLoaded', run);
  window.addEventListener('load', run);
  window.addEventListener('resize', schedule);
  try {
    new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
  } catch (e) {}
})();
