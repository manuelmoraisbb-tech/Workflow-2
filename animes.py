#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""animes.py - tudo num só script (só biblioteca padrão, funciona no Termux).

Comandos:
  db      [LISTAS.html...] [-o animes_db.json]   TUDO: todos os animes/filmes -> base JSON completa
  eps     PAGINA [-o blogger.txt]     lista de episódios -> "ep|link do blogger"
  tokens  ENTRADAS... [-o f.txt|.json] links blogger.com/video.g?token=... de HTML/MHTML/URL/JSON
  videos  ENTRADAS... [--fetch] [--json f.json]   links m3u8/mp4/... e episódios
  serie   resultado.html [-o links.json]          SERIES_INDEX (Base64 + XOR 92) -> JSON
  crawl   SEMENTES.html... [-o f.json]            navega o domínio e extrai episódios/vídeos/sinopse

Exemplos:
  python animes.py db                          # dublados + legendados + filmes, tudo
  python animes.py db --match "black clover"   # teste rápido com um título
  python animes.py db --fases 12               # só catálogo (sem links dos episódios)
  python animes.py eps te__2_.html
  python animes.py eps https://animesdigital.org/anime/a/xxxx --delay 1
  python animes.py tokens pagina.html outra.html -o blogger.txt
  python animes.py videos t1.html --fetch --json links.json
  python animes.py serie resultado.html
  python animes.py crawl te__2_.html --max-pages 50
"""
import argparse, base64, email, html, json, re, sys, time
from concurrent.futures import ThreadPoolExecutor
from collections import deque
from email import policy
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urldefrag, urljoin, urlparse
from urllib.request import Request, urlopen

UA = ("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
DOMINIO = "animesdigital.org"

RE_TOKEN = re.compile(r"https?://(?:www\.)?blogger\.com/video\.g\?token=[A-Za-z0-9_\-]+(?:&(?:amp;)?[A-Za-z0-9_.=%\-]+)*")
RE_B64 = re.compile(r"https?://[^/\s\"'<>]+/([A-Za-z0-9+/_-]{20,}={0,2})/(\d+)/[^/?\s\"'<>]*\.mp4")
RE_EP = re.compile(r"""<a[^>]+href=["'](https?://[^"']+/video/[^"']+)["'][^>]*>(.*?)</a>""", re.I | re.S)
EXT = r"(?:m3u8|mp4|mkv|webm|mpd|flv|mov)"
RE_MEDIA = re.compile(r"https?://[^\s\"'<>\\()&]+\." + EXT + r"(?:\?[^\s\"'<>\\()]*)?", re.I)
RE_WRAP = re.compile(r"https?://[^\s\"'<>\\]+?[?&](?:d|url|u|src|file|link|video)=(https?[^\s\"'<>\\]+)", re.I)
RE_PLAYER = re.compile(
    r"https?://[^\s\"'<>\\]*(?:blogger\.com/video\.g\?token=|/videohls\.php|/embed/|/player/|/file/)[^\s\"'<>\\]*", re.I)
RE_ATTR = re.compile(r"""(?:src|data-src|data-url|data-video|href)\s*=\s*["']([^"']+)["']""", re.I)
EP_NUM = re.compile(r"(?:epis[oó]dio|episode|ep\.?)[\s:#-]*(\d+(?:\.\d+)?)", re.I)


# ----------------------------------------------------------------- comum
def baixar(url, tentativas=3, ref=None):
    for i in range(tentativas):
        try:
            req = Request(url, headers={"User-Agent": UA, "Referer": ref or url})
            with urlopen(req, timeout=25) as r:
                return r.read().decode("utf-8", "replace")
        except Exception:
            if i == tentativas - 1:
                raise
            time.sleep(2)


def ler(o):
    """URL, HTML ou MHTML ('Saved by Blink') -> texto."""
    if o.startswith(("http://", "https://")):
        return baixar(o)
    raw = open(o, "rb").read()
    if raw[:300].lstrip().startswith(b"From:") and b"MIME-Version" in raw[:2000]:
        out = []
        for p in email.message_from_bytes(raw, policy=policy.default).walk():
            loc = p.get("Content-Location")
            if loc and not str(loc).startswith("cid:"):
                out.append(str(loc))
            if p.get_content_maintype() == "text" or p.get_content_type() in (
                    "application/javascript", "application/json"):
                out.append((p.get_payload(decode=True) or b"").decode(p.get_content_charset() or "utf-8", "replace"))
        return "\n".join(out)
    return raw.decode("utf-8", "replace")


def limpar(t):
    t = html.unescape(t)
    return t.replace("\\u003d", "=").replace("\\u003D", "=").replace("\\u0026", "&").replace("\\/", "/")


def limpa_url(u):
    return u.rstrip("\\.,;)'\"").replace("&amp;", "&")


def uniq(seq):
    return list(dict.fromkeys(seq))


def episodios(texto):
    """[{'ep': int, 'titulo': str, 'url': str}] ordenado; ignora links de navegação."""
    vistos, eps = set(), []
    for m in RE_EP.finditer(limpar(texto)):
        url = re.sub(r"/feed/?$", "", m.group(1).split("#")[0]).rstrip("/")
        if url in vistos:
            continue
        corpo = m.group(2)
        alt = re.search(r'alt="([^"]+)"', corpo)
        tit = alt.group(1).replace("Assistir ", "") if alt else re.sub(r"<[^>]+>", " ", corpo)
        tit = re.sub(r"\s+", " ", tit).strip()
        n = re.search(r"Epis\S*\s*(\d+)", tit, re.I) or re.search(r"(\d+)\s*Epis", tit, re.I)
        if not n:
            continue
        vistos.add(url)
        eps.append({"ep": int(n.group(1)), "titulo": tit, "url": url})
    return sorted(eps, key=lambda e: e["ep"])


def tokens(texto):
    return uniq(m.group(0) for m in RE_TOKEN.finditer(limpar(texto)))


def post_do_link(texto):
    """.../<base64 do post blogspot>/<N>/bg.mp4 -> (url_do_post, N)"""
    m = RE_B64.search(limpar(texto))
    if not m:
        return None
    try:
        b = m.group(1).replace("-", "+").replace("_", "/")
        post = base64.b64decode(b + "=" * (-len(b) % 4)).decode("utf-8")
    except Exception:
        return None
    return (post, int(m.group(2))) if post.startswith("http") else None


# ------------------------------------------------------------------- eps
def cmd_eps(a):
    eps = episodios(ler(a.pagina))
    if not eps:
        sys.exit("Não encontrei episódios nessa página.")
    print(f"{len(eps)} episódios encontrados\n")
    cache, linhas, falhas = {}, [], []
    for e in eps:
        n, link = e["ep"], None
        try:
            pg = baixar(e["url"])
            d = post_do_link(pg)
            if d:
                post, idx = d
                if post not in cache:
                    cache[post] = tokens(baixar(post))
                    time.sleep(a.delay)
                if idx < len(cache[post]):
                    link = cache[post][idx]
            if not link:  # alguns eps trazem o token direto
                t = tokens(pg)
                link = t[0] if t else None
        except Exception as ex:
            print(f"  ✗ ep {n}: {ex}")
        if link:
            linhas.append(f"{n}|{link}")
            print(f"  ✓ ep {n}")
        else:
            falhas.append(n)
            print(f"  ✗ ep {n}: sem link")
        time.sleep(a.delay)
    Path(a.o).write_text("\n".join(linhas) + "\n", encoding="utf-8")
    print(f"\n{len(linhas)}/{len(eps)} links guardados em {a.o}")
    if falhas:
        print("Sem link:", ", ".join(map(str, falhas)))


# ---------------------------------------------------------------- tokens
def itens_json(dados):
    """Percorre qualquer JSON e devolve [(rotulo, url)] com todos os URLs http."""
    out, vistos = [], set()

    def rot(d):
        for k in ("ep", "episodio", "episode", "numero", "titulo", "title", "nome", "name"):
            if k in d and isinstance(d[k], (str, int)):
                return str(d[k])
        return ""

    def walk(x, r=""):
        if isinstance(x, dict):
            r2 = rot(x) or r
            for v in x.values():
                walk(v, r2)
        elif isinstance(x, list):
            for v in x:
                walk(v, r)
        elif isinstance(x, str) and x.startswith(("http://", "https://")):
            if x not in vistos and not re.search(r"\.(jpe?g|png|gif|webp|css|js)(\?|$)", x, re.I):
                vistos.add(x)
                out.append((r, x))
    walk(dados)
    return out


def cmd_tokens(a):
    tarefas, a_json = [], False
    for e in a.entradas:
        if e.lower().endswith(".json"):
            a_json = True
            tarefas += itens_json(json.load(open(e, encoding="utf-8")))
        else:
            tarefas.append(("", e))
    if a.listar:
        for r, u in tarefas:
            print(f"{r[:30]:<30} {u}")
        print(f"\n{len(tarefas)} URLs")
        return
    res, cache = [], {}
    for r, o in tarefas:
        ls = []
        try:
            m = RE_B64.match(o)
            d = post_do_link(o) if m else None
            if d:
                post, n = d
                if post not in cache:
                    cache[post] = tokens(ler(post))
                    time.sleep(a.delay)
                if n < len(cache[post]):
                    ls = [cache[post][n]]
                elif cache[post]:
                    print(f"[aviso] {r or o}: índice {n} fora do post ({len(cache[post])} vídeos)", file=sys.stderr)
            elif "blogger.com/video.g?token=" in o:
                ls = tokens(o)
            elif a_json:
                continue  # m3u8, páginas de episódio, etc.
            else:
                ls = tokens(ler(o))
                if o.startswith("http"):
                    time.sleep(a.delay)
        except Exception as ex:
            print(f"[erro] {r or o}: {ex}", file=sys.stderr)
            continue
        print(f"# Ep {r or o} ({len(ls)})")
        for u in ls:
            print(u)
            res.append({"ep": r, "origem": o, "blogger": u})
    if a.o:
        if a.o.lower().endswith(".json"):
            json.dump(res, open(a.o, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
        else:
            open(a.o, "w").write("\n".join(x["blogger"] for x in res) + "\n")
        print(f"\nGuardado em {a.o} ({len(res)} links)")


# ---------------------------------------------------------------- videos
def extrair_videos(texto):
    t = limpar(texto)
    diretos, players = [], []
    for m in RE_WRAP.finditer(t):  # ...videohls.php?d=https://.../index.m3u8
        mm = RE_MEDIA.match(unquote(m.group(1)))
        if mm:
            diretos.append(limpa_url(mm.group(0)))
    for m in RE_MEDIA.finditer(t):
        diretos.append(limpa_url(m.group(0)))
    for m in RE_PLAYER.finditer(t):
        players.append(limpa_url(m.group(0)))
    for m in RE_ATTR.finditer(t):
        u = m.group(1)
        if u.startswith("http") and re.search(r"blogger\.com/video|youtube\.com/embed|/embed/|/player", u, re.I):
            players.append(limpa_url(u))

    def dedup(seq):
        seen, out = set(), []
        for x in seq:
            k = x.split("&nocache")[0].split("?nocache")[0]
            if k not in seen and not k.lower().endswith((".jpg", ".png", ".css", ".js")):
                seen.add(k)
                out.append(x)
        return out

    diretos = dedup(diretos)
    diretos = [d for d in diretos if not any(o != d and o in d for o in diretos)]
    players = [p for p in dedup(players) if not any(d in p for d in diretos)]
    return diretos, players


def cmd_videos(a):
    resultado = []
    for ent in a.entradas:
        try:
            texto = ler(ent)
        except Exception as ex:
            print(f"[erro] {ent}: {ex}")
            continue
        diretos, players = extrair_videos(texto)
        eps = episodios(texto)
        m = re.search(r"<title>([^<]+)</title>", texto, re.I)
        item = {"origem": ent, "titulo": html.unescape(m.group(1)).strip() if m else "",
                "videos": diretos, "players": players, "episodios": eps}
        print("=" * 60)
        print(f"{ent}\n{item['titulo']}")
        if diretos:
            print("\n▶ VÍDEOS DIRETOS:")
            for d in diretos:
                print("  ", d)
        if players:
            print("\n◦ PLAYERS/EMBEDS:")
            for p in players:
                print("  ", p[:150] + ("..." if len(p) > 150 else ""))
        if eps:
            print(f"\n# EPISÓDIOS ({len(eps)}):")
            for e in eps:
                print(f"   Ep {e['ep']:>2}  {e['url']}")
        if a.fetch and eps:
            print("\n... a abrir episódios ...")
            for e in eps:
                try:
                    d, p = extrair_videos(baixar(e["url"]))
                    e["videos"], e["players"] = d, p
                    print(f"  ✓ Ep {e['ep']}: {d[0] if d else (p[0][:80] if p else 'nada')}")
                except Exception as ex:
                    e["erro"] = str(ex)
                    print(f"  ✗ Ep {e['ep']}: {ex}")
                time.sleep(a.delay)
        resultado.append(item)
    if a.json:
        json.dump(resultado, open(a.json, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
        print(f"\nGuardado em {a.json}")


# ----------------------------------------------------------------- serie
XOR_KEY = 92


def decode_html(raw):
    """Wrapper Base64 + XOR 92. Se já estiver decodificado, devolve como está."""
    if "const SERIES_INDEX" in raw:
        return raw
    m = re.search(r'(?:var|let|const)\s+encoded\s*=\s*["\']([^"\']+)["\']', raw, re.S)
    if not m:
        raise ValueError("Não encontrei a variável 'encoded'. O HTML pode não estar no formato esperado.")
    try:
        binario = base64.b64decode(m.group(1))
    except Exception as exc:
        raise ValueError("Falha ao decodificar Base64.") from exc
    try:
        return bytes(b ^ XOR_KEY for b in binario).decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ValueError("Falha ao converter o conteúdo decodificado para UTF-8.") from exc


def extract_js_array(text, nome):
    m = re.search(rf"\b(?:const|let|var)\s+{re.escape(nome)}\s*=\s*", text)
    if not m:
        raise ValueError(f"Não encontrei {nome} no HTML decodificado.")
    i = m.end()
    while i < len(text) and text[i].isspace():
        i += 1
    if i >= len(text) or text[i] != "[":
        raise ValueError(f"{nome} não contém um array JSON.")
    ini, depth, em_str, esc = i, 0, False, False
    for j in range(ini, len(text)):
        ch = text[j]
        if em_str:
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                em_str = False
            continue
        if ch == '"':
            em_str = True
        elif ch == "[":
            depth += 1
        elif ch == "]":
            depth -= 1
            if depth == 0:
                return text[ini:j + 1]
    raise ValueError(f"Array {nome} incompleto.")


def cmd_serie(a):
    if not Path(a.html).exists():
        sys.exit(f"Arquivo não encontrado: {a.html}")
    dec = html.unescape(decode_html(Path(a.html).read_text(encoding="utf-8", errors="replace")))
    try:
        indice = json.loads(extract_js_array(dec, "SERIES_INDEX"))
    except json.JSONDecodeError as exc:
        sys.exit(f"SERIES_INDEX foi encontrado, mas não é JSON válido: {exc}")
    res = {}
    for anime in indice:
        dados = {"poster": anime.get("poster"), "temporadas": {}}
        for temp, eps in (anime.get("seasons") or {}).items():
            lista = dados["temporadas"][str(temp)] = []
            if not isinstance(eps, list):
                continue
            for it in eps:
                url = it.get("url") if isinstance(it, dict) else None
                if url and (".mp4" in url.lower() or ".m3u8" in url.lower()):
                    lista.append({"episodio": it.get("episode"), "nome": it.get("name"),
                                  "url": url, "logo": it.get("logo")})
            lista.sort(key=lambda x: (x["episodio"] is None,
                                      x["episodio"] if isinstance(x["episodio"], (int, float)) else 0))
        res[anime.get("name", "Sem nome")] = dados
    Path(a.o).write_text(json.dumps(res, ensure_ascii=False, indent=2), encoding="utf-8")
    print("Extração concluída!")
    print(f"Animes:       {len(res)}")
    print(f"Temporadas:   {sum(len(v['temporadas']) for v in res.values())}")
    print(f"Episódios:    {sum(len(e) for v in res.values() for e in v['temporadas'].values())}")
    print(f"Arquivo:      {Path(a.o).resolve()}")


# ----------------------------------------------------------------- crawl
VIDEO_EXT = (".mp4", ".m3u8", ".mpd", ".webm", ".mkv")


def abs_url(url, base):
    url = html.unescape((url or "").strip())
    if not url or url.startswith(("javascript:", "mailto:", "tel:", "data:")):
        return ""
    return urldefrag(urljoin(base, url))[0]


def is_http(u):
    return urlparse(u).scheme in ("http", "https")


def same_domain(u, dom):
    h = (urlparse(u).hostname or "").lower()
    return h == dom or h.endswith("." + dom)


def is_video(u):
    low = u.lower()
    return low.split("?", 1)[0].endswith(VIDEO_EXT) or any(
        x in low for x in ("googlevideo.com", "blogger.com/video", "drive.google.com/uc"))


def num(x):
    return int(x) if isinstance(x, float) and x.is_integer() else x


def txt(v):
    return re.sub(r"\s+", " ", html.unescape(str(v or ""))).strip()


class Pagina(HTMLParser):
    """Recolhe título, metas, links, fontes de vídeo, JSON-LD e parágrafos."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.title, self.meta, self.links, self.srcs, self.ld, self.paras = "", {}, [], [], [], []
        self._t = self._a = self._ld = self._p = None

    def handle_starttag(self, tag, attrs):
        a = {k: (v or "") for k, v in attrs}
        if tag == "title":
            self._t = []
        elif tag == "meta":
            k = a.get("property") or a.get("name") or a.get("itemprop")
            if k and a.get("content"):
                self.meta.setdefault(k.lower(), a["content"])
        elif tag == "a" and a.get("href"):
            self._a = [a, [], ""]
        elif tag == "img" and self._a is not None:
            if not self._a[2]:
                self._a[2] = a.get("data-src") or a.get("data-lazy-src") or a.get("src") or ""
            if a.get("alt"):
                self._a[1].append(" " + a["alt"])
        elif tag in ("iframe", "video", "source"):
            u = a.get("src") or a.get("data-src")
            if u:
                self.srcs.append(u)
            if tag == "iframe" and a.get("href"):
                self.srcs.append(a["href"])
        elif tag == "script" and a.get("type") == "application/ld+json":
            self._ld = []
        elif tag == "p":
            self._p = []

    def handle_data(self, d):
        for buf in (self._t, self._ld, self._p, self._a[1] if self._a else None):
            if buf is not None:
                buf.append(d)

    def handle_endtag(self, tag):
        if tag == "title" and self._t is not None:
            self.title, self._t = txt("".join(self._t)), None
        elif tag == "a" and self._a:
            a, buf, img = self._a
            self.links.append((a["href"], a.get("title") or txt("".join(buf)), img))
            self._a = None
        elif tag == "script" and self._ld is not None:
            self.ld.append("".join(self._ld))
            self._ld = None
        elif tag == "p" and self._p is not None:
            self.paras.append(txt("".join(self._p)))
            self._p = None


def descricao(p):
    for k in ("description", "og:description", "twitter:description"):
        if txt(p.meta.get(k)):
            return txt(p.meta[k])
    for raw in p.ld:
        try:
            data = json.loads(raw)
        except ValueError:
            continue
        for item in (data if isinstance(data, list) else [data]):
            if isinstance(item, dict) and txt(item.get("description")):
                return txt(item["description"])
    for t in p.paras:
        if len(t) >= 60:
            return t[:5000]
    return ""


def parse_page(texto, url, dom):
    p = Pagina()
    p.feed(texto)
    titulo = txt(p.meta.get("og:title")) or p.title
    poster = abs_url(p.meta["og:image"], url) if p.meta.get("og:image") else ""
    internos, eps, ext, vids = [], [], [], []
    vistos_ep, vistos_ext, vistos_v = set(), set(), set()
    generos = []
    for href, label, img in p.links:
        u = abs_url(href, url)
        if not is_http(u):
            continue
        if same_domain(u, dom):
            internos.append(u)
            if re.search(r"/(?:gener[oa]s?|genre|categor\w*)/", urlparse(u).path, re.I) and label:
                generos.append(label)
            low = label.lower()
            if "/video/" in urlparse(u).path.lower() or EP_NUM.search(label) or "epis" in low:
                if u not in vistos_ep:
                    n = EP_NUM.search(label)
                    eps.append({"nome": label or u, "episodio": float(n.group(1)) if n else None, "url": u,
                                "imagem": abs_url(img, url) if img else ""})
                    vistos_ep.add(u)
        elif any(d in (urlparse(u).hostname or "").lower() for d in ("blogger.com", "blogspot.com", "drive.google.com")):
            if u not in vistos_ext:
                ext.append({"nome": label, "url": u})
                vistos_ext.add(u)
    cand = list(p.srcs) + re.findall(r"[\"']((?:https?:)?//[^\"']+)[\"']", texto)
    for raw in cand:
        u = abs_url(raw, url)
        if u and is_http(u) and is_video(u) and u not in vistos_v:
            vids.append(u)
            vistos_v.add(u)
    eps.sort(key=lambda x: (x["episodio"] is None, x["episodio"] or 0, x["nome"].lower()))
    return {"titulo": titulo or url, "descricao": descricao(p), "url_pagina": url, "poster": poster,
            "generos": uniq(generos), "episodios": eps, "videos_diretos": vids,
            "links_google_blogger": ext}, uniq(internos)


def read_seeds(path, dom):
    raw = Path(path).read_text(encoding="utf-8", errors="replace")
    base = "https://" + dom + "/"
    seeds = [abs_url(m, base) for m in re.findall(r"""(?:href|src)\s*=\s*["']([^"']+)["']""", raw, re.I)]
    seeds += [abs_url(u.replace("&amp;", "&"), base) for u in re.findall(r"https?://[^\s\"'<>]+", raw)]
    return uniq(u for u in seeds if u and is_http(u) and same_domain(u, dom))


def cmd_crawl(a):
    queue, queued = deque(), set()
    for nome in a.html:
        p = Path(nome)
        if not p.is_file():
            print(f"[AVISO] HTML não encontrado: {p}")
            continue
        try:
            for u in read_seeds(p, a.domain):
                if u not in queued:
                    queue.append((u, 0))
                    queued.add(u)
            print(f"[SEMENTE] {p.name}: referências carregadas")
        except Exception as ex:
            print(f"[ERRO] {p.name}: {ex}")
    results, visited = [], set()
    while queue and len(visited) < max(0, a.max_pages):
        url, depth = queue.popleft()
        if url in visited or not same_domain(url, a.domain):
            continue
        visited.add(url)
        try:
            req = Request(url, headers={"User-Agent": a.user_agent, "Accept": "text/html,application/xhtml+xml"})
            with urlopen(req, timeout=a.timeout) as r:
                final = urldefrag(r.geturl())[0]
                ctype = (r.headers.get("content-type") or "").lower()
                corpo = r.read().decode("utf-8", "replace")
            if not same_domain(final, a.domain):
                print(f"[IGNORADO] Redirecionou para outro domínio: {url}")
                continue
            if "html" not in ctype and "<html" not in corpo[:1000].lower():
                print(f"[SEM HTML] {url}")
                continue
            data, internos = parse_page(corpo, final, a.domain)
            results.append(data)
            desc = "sim" if data["descricao"] else "não"
            print(f"[OK {len(visited)}/{a.max_pages}] {data['titulo'][:70]} | descrição: {desc} | "
                  f"{len(data['episodios'])} links de episódio | {len(data['videos_diretos'])} vídeos")
            if depth < a.max_depth:
                for link in internos:
                    if link not in queued and link not in visited:
                        queue.append((link, depth + 1))
                        queued.add(link)
            if a.delay > 0:
                time.sleep(a.delay)
        except Exception as ex:
            print(f"[ERRO HTTP] {url}: {ex}")
    out = Path(a.o)
    out.write_text(json.dumps({"dominio": a.domain, "paginas_visitadas": len(visited), "resultados": results},
                              ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\nConcluído: {len(results)} páginas analisadas. JSON guardado em: {out.resolve()}")
    print("Nota: só visita páginas públicas do domínio indicado; não contorna login, CAPTCHA nem outras proteções.")


# -------------------------------------------------------------------- db
# Estrutura real do site (confirmada nas páginas guardadas):
#   listas  : /animes-dublado001?...&pagina=N   (30 itens .itemA por página, "Página 1 de 13")
#   anime   : /anime/a/<slug>  (h1, .poster, .sinopse, .genre, .info, .itens_ep/.item_ep, /page/N/)
#   filme   : /filme/<slug>
#   episódio: /video/a/<id>  -> iframes: api.anivideo.net/videohls.php?d=<m3u8>   (HLS direto)
#                                        api.anivideo.net/file/<b64>  -> iframe blogger.com/video.g?token=...
_F = ("filter_letter=0&type_url={t}&filter_audio={au}&filter_order=name&filter_genre_add="
      "&filter_genre_del=&pagina={{p}}&search=0&limit=30")
LISTAS = [  # (tipo, audio, caminho)
    ("anime", "dublado", "/animes-dublado001?" + _F.format(t="animes", au="dublado")),
    ("anime", "legendado", "/animes-legendados-online001?" + _F.format(t="animes", au="legendado")),
    ("filme", "", "/filmes001?" + _F.format(t="filmes", au="0")),
]
RE_ITEMA = re.compile(r'<div class="itemA"><a href="([^"]+)"[^>]*>.*?<img src="([^"]*)".*?'
                      r'<span class="title_anime">(.*?)</span>', re.S)
RE_TOTAL = re.compile(r"P[áa]gina\s*<b>(\d+)</b>\s*de\s*<b>(\d+)</b>")
RE_EPI = re.compile(r'<div class="item_ep[^"]*">\s*<a href="([^"]+)"[^>]*>.*?<img src="([^"]*)".*?'
                    r'<div class="title_anime">(.*?)</div>\s*<div class="date">(.*?)</div>', re.S)
RE_PLAY = re.compile(r"https?://[^\s\"'<>\\]*(?:videohls\.php\?d=|/file/)[^\s\"'<>\\]*")


def sem_tags(x):
    return txt(re.sub(r"<[^>]+>", " ", x or ""))


def um(pat, t):
    m = re.search(pat, t, re.S)
    return m.group(1) if m else ""


def url_limpa(u):
    return re.sub(r"/feed/?$", "", urldefrag(html.unescape(u))[0]).rstrip("/")


def parse_lista(t):
    itens = [{"url": url_limpa(u), "poster": html.unescape(img), "nome": sem_tags(n)}
             for u, img, n in RE_ITEMA.findall(t)]
    m = RE_TOTAL.search(t)
    return itens, (int(m.group(2)) if m else 1)


def parse_detalhe(t):
    eps = []
    for u, img, nome, data in RE_EPI.findall(t):
        nome = sem_tags(nome)
        n = EP_NUM.search(nome) or re.search(r"(\d+(?:\.\d+)?)\s*$", nome)
        eps.append({"episodio": num(float(n.group(1))) if n else None, "nome": nome,
                    "url": url_limpa(u), "imagem": html.unescape(img), "data": sem_tags(data)})
    return {
        "nome": sem_tags(um(r"<h1[^>]*>(.*?)</h1>", t)),
        "poster": html.unescape(um(r'<div class="poster"><img src="([^"]+)"', t)),
        "sinopse": sem_tags(um(r'<div class="sinopse">(.*?)</div>', t))
                   or txt(um(r'<meta name="description" content="([^"]*)"', t)),
        "generos": uniq(sem_tags(g) for g in re.findall(r'<div class="genre"><a [^>]*>(.*?)</a>', t, re.S)),
        "info": {sem_tags(k).rstrip(":"): sem_tags(v)
                 for k, v in re.findall(r'<div class="info"><span>(.*?)</span>(.*?)</div>', t, re.S)},
        "eps": eps,
        "proxima": html.unescape(um(r'<li class="next"><a href="([^"]+)"', t)),
    }


def extrair_players(url, pg):
    """Página do episódio -> {'hls': m3u8 direto, 'blogger': link blogger}."""
    t = limpar(pg)
    res = {"hls": None, "blogger": None}
    tk = tokens(t)
    if tk:
        res["blogger"] = tk[0]
    cands = uniq(limpa_url(m.group(0)) for m in RE_PLAY.finditer(t))
    for c in cands:
        m = re.search(r"videohls\.php\?d=([^&\s]+)", c)
        if m:
            res["hls"] = res["hls"] or unquote(m.group(1))
        elif "/file/" in c and not res["blogger"]:
            body = limpar(baixar(c, ref=url))
            tk = tokens(body)
            if tk:
                res["blogger"] = tk[0]
            mm = re.search(r"videohls\.php\?d=([^&\s]+)", body)
            if mm:
                res["hls"] = res["hls"] or unquote(mm.group(1))
    return res


def paralelo(fn, itens, workers, delay):
    """Chama fn em cada item (em lotes, com pausa); devolve (item, resultado, erro) por ordem."""
    def w(x):
        try:
            r = (x, fn(x), None)
        except Exception as ex:
            r = (x, None, ex)
        time.sleep(delay)
        return r
    passo = max(workers * 3, 6)
    for i in range(0, len(itens), passo):
        lote = itens[i:i + passo]
        if workers <= 1:
            for x in lote:
                yield w(x)
        else:
            with ThreadPoolExecutor(workers) as ex:
                yield from ex.map(w, lote)


def cmd_db(a):
    out, base = Path(a.o), a.base.rstrip("/")
    animes = {}
    if out.exists():
        try:
            for an in json.loads(out.read_text(encoding="utf-8")).get("animes", []):
                animes[an["url"]] = an
            print(f"Base existente: {len(animes)} animes (continua de onde parou)")
        except Exception as ex:
            print(f"[AVISO] não consegui ler {out}: {ex}")

    def salvar():
        lista = sorted(animes.values(), key=lambda x: x["nome"].lower())
        for an in lista:
            an["episodios"].sort(key=lambda e: (e["episodio"] is None, e["episodio"] or 0))
        db = {"fonte": base, "atualizado": time.strftime("%Y-%m-%d %H:%M:%S"),
              "total_animes": len(lista),
              "total_episodios": sum(len(x["episodios"]) for x in lista),
              "total_links": sum(1 for x in lista for e in x["episodios"] if e.get("hls") or e.get("blogger")),
              "animes": lista}
        tmp = out.with_suffix(out.suffix + ".tmp")
        tmp.write_text(json.dumps(db, ensure_ascii=False, indent=2), encoding="utf-8")
        tmp.replace(out)

    def reg(item, tipo, audio):
        an = animes.setdefault(item["url"], {
            "nome": item["nome"], "tipo": tipo, "audio": audio, "url": item["url"],
            "poster": item["poster"], "descricao": "", "generos": [], "info": {},
            "detalhado": False, "episodios": []})
        an["nome"] = an["nome"] or item["nome"]
        an["poster"] = an["poster"] or item["poster"]

    # ---------------- fase 1: listas (todos os animes e filmes)
    if "1" in a.fases:
        fontes = []   # (tipo, audio, modelo_url, html_da_pagina1_ou_None)
        for f in a.fontes:
            pre = None
            if Path(f).is_file():
                cab = open(f, "rb").read(4000).decode("latin-1")
                loc = (re.search(r"Snapshot-Content-Location:\s*(\S+)", cab) or [None, ""])[1]
                pre = ler(f)
            else:
                loc = f
            if "pagina=" not in loc:
                print(f"[AVISO] {f}: não consegui descobrir o URL da lista (esperava ...pagina=N)")
                continue
            fontes.append(("filme" if "filmes" in loc else "anime",
                           "dublado" if "dublado" in loc else "legendado" if "legendado" in loc else "",
                           re.sub(r"pagina=\d+", "pagina={p}", loc), pre))
        if not fontes:
            fontes = [(t, au, base + c, None) for t, au, c in LISTAS]
        print(f"Fase 1: listas ({len(fontes)})")
        for tipo, audio, modelo, pre in fontes:
            total, vistos1, avisou = None, set(), False
            p = 1
            while total is None or p <= total:
                try:
                    t = pre if (p == 1 and pre) else baixar(modelo.format(p=p))
                except Exception as ex:
                    print(f"  [ERRO] {tipo} {audio} pág {p}: {ex}")
                    p += 1
                    continue
                itens, tot = parse_lista(t)
                total = total or tot
                if p == 1:
                    vistos1 = {i["url"] for i in itens}
                elif itens and not avisou and {i["url"] for i in itens} <= vistos1:
                    avisou = True
                    print("  [AVISO] a pág", p, "repete a pág 1: a paginação por URL pode não funcionar. "
                          "Guarda cada página da lista no navegador e passa os HTMLs.")
                for it in itens:
                    reg(it, tipo, audio)
                print(f"  {tipo} {audio or ''} pág {p}/{total}: {len(itens)} itens (total {len(animes)})")
                p += 1
                if not (p == 2 and pre):
                    time.sleep(a.delay)
            salvar()
        print(f"Fase 1 concluída: {len(animes)} títulos.\n")

    alvo = sorted((x for x in animes.values() if not a.match or re.search(a.match, x["nome"], re.I)),
                  key=lambda x: x["nome"].lower())

    # ---------------- fase 2: detalhes (sinopse, gêneros, episódios)
    if "2" in a.fases:
        pend = [x for x in alvo if not x.get("detalhado")]
        if a.max_animes:
            pend = pend[:a.max_animes]
        print(f"Fase 2: detalhes de {len(pend)} títulos")

        def detalhar(an):
            pagina, vistas, d0, eps = an["url"], set(), None, []
            for _ in range(300):
                d = parse_detalhe(baixar(pagina))
                d0 = d0 or d
                eps += d["eps"]
                vistas.add(pagina)
                nxt = abs_url(d["proxima"], pagina) if d["proxima"] else ""
                if not nxt or nxt in vistas:
                    break
                pagina = nxt
                time.sleep(a.delay)
            if not eps and an["tipo"] == "filme":   # filme = a própria página é o "episódio"
                eps = [{"episodio": 1, "nome": d0["nome"] or an["nome"], "url": an["url"],
                        "imagem": d0["poster"], "data": ""}]
            return d0, eps

        for k, (an, r, err) in enumerate(paralelo(detalhar, pend, a.workers, a.delay), 1):
            if err:
                print(f"  ✗ {an['nome']}: {err}")
                continue
            d0, eps = r
            an["poster"] = d0["poster"] or an["poster"]
            an["descricao"] = d0["sinopse"]
            an["generos"] = d0["generos"]
            an["info"] = d0["info"]
            ja = {e["url"]: e for e in an["episodios"]}
            for e in eps:
                if e["url"] in ja:
                    ja[e["url"]].update({k2: v for k2, v in e.items() if v})
                else:
                    ja[e["url"]] = dict(e, hls=None, blogger=None)
                    an["episodios"].append(ja[e["url"]])
            an["detalhado"] = True
            print(f"  ✓ [{k}/{len(pend)}] {an['nome'][:55]} ({len(an['episodios'])} eps)")
            if k % 10 == 0:
                salvar()
        salvar()
        print("Fase 2 concluída.\n")

    # ---------------- fase 3: links de cada episódio (blogger + hls)
    if "3" in a.fases:
        pend = [x for x in alvo if any(not (e.get("hls") or e.get("blogger")) for e in x["episodios"])]
        if a.max_animes:
            pend = pend[:a.max_animes]
        print(f"Fase 3: links de {sum(1 for x in pend for e in x['episodios'] if not (e.get('hls') or e.get('blogger')))} "
              f"episódios em {len(pend)} títulos")

        def links(e):
            r = extrair_players(e["url"], baixar(e["url"]))
            e["hls"], e["blogger"] = r["hls"], r["blogger"]
            return r

        for an in pend:
            falta = sorted((e for e in an["episodios"] if not (e.get("hls") or e.get("blogger"))),
                           key=lambda e: (e["episodio"] is None, e["episodio"] or 0))
            ok = 0
            print(f"\n» {an['nome']} ({len(falta)} eps)")
            for e, r, err in paralelo(links, falta, a.workers, a.delay):
                if err:
                    print(f"  ✗ ep {e['episodio']}: {err}")
                elif r["hls"] or r["blogger"]:
                    ok += 1
                else:
                    print(f"  ✗ ep {e['episodio']}: sem link")
            print(f"  {ok}/{len(falta)} com link")
            salvar()   # grava após cada anime: pode parar (Ctrl+C) e continuar depois
        print("\nFase 3 concluída.")
    salvar()
    print(f"\nBase de dados: {out.resolve()}")

# ------------------------------------------------------------------ main
def main():
    ap = argparse.ArgumentParser(description="Extrai links/episódios do Animes Digital (script único).",
                                 epilog=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    def novo(nome, ajuda, delay=1.0):
        s = sub.add_parser(nome, help=ajuda)
        if delay is not None:
            s.add_argument("--delay", type=float, default=delay, help="pausa entre pedidos (s)")
        return s

    s = novo("db", "TUDO: lista todos os animes/filmes e cria a base JSON (nome, imagem, sinopse, eps, links)")
    s.add_argument("fontes", nargs="*", help="(opcional) HTMLs/URLs das listas; padrão: dublados + legendados + filmes")
    s.add_argument("-o", default="animes_db.json", help="base de dados (padrão: animes_db.json)")
    s.add_argument("--base", default="https://" + DOMINIO, help="endereço do site")
    s.add_argument("--fases", default="123", help="1=listas  2=detalhes (sinopse, gêneros, eps)  3=links dos eps")
    s.add_argument("--match", help="regex: só processa títulos cujo nome combine (fases 2 e 3)")
    s.add_argument("--max-animes", type=int, default=0, help="limite de títulos por fase e execução (0 = todos)")
    s.add_argument("--workers", type=int, default=3, help="pedidos em paralelo (padrão 3)")
    s.set_defaults(f=cmd_db)

    s = novo("eps", "página com todos os episódios -> 'ep|link do blogger'")
    s.add_argument("pagina", help="HTML/MHTML guardado ou URL")
    s.add_argument("-o", default="blogger.txt", help="saída (padrão: blogger.txt)")
    s.set_defaults(f=cmd_eps)

    s = novo("tokens", "extrai links blogger.com/video.g?token=...")
    s.add_argument("entradas", nargs="+", help="HTML, MHTML, URL ou JSON")
    s.add_argument("-o", help="saída .txt ou .json")
    s.add_argument("--listar", action="store_true", help="só mostra o que leu do JSON")
    s.set_defaults(f=cmd_tokens)

    s = novo("videos", "links de vídeo (m3u8/mp4/...) e episódios")
    s.add_argument("entradas", nargs="+", help="HTML, MHTML ou URL")
    s.add_argument("--fetch", action="store_true", help="abre cada episódio online")
    s.add_argument("--json", metavar="FICHEIRO", help="guarda o resultado em JSON")
    s.set_defaults(f=cmd_videos)

    s = novo("serie", "SERIES_INDEX (Base64 + XOR 92) -> JSON", delay=None)
    s.add_argument("html", help="HTML original, ex.: resultado.html")
    s.add_argument("-o", default="links.json", help="saída (padrão: links.json)")
    s.set_defaults(f=cmd_serie)

    s = novo("crawl", "navega o domínio a partir de HTMLs-semente")
    s.add_argument("html", nargs="+", help="HTMLs locais usados como sementes")
    s.add_argument("-o", default="links_animesdigital.json", help="JSON de saída")
    s.add_argument("--domain", default=DOMINIO, help="domínio permitido")
    s.add_argument("--max-pages", type=int, default=300)
    s.add_argument("--max-depth", type=int, default=5)
    s.add_argument("--timeout", type=int, default=20)
    s.add_argument("--user-agent", default=UA)
    s.set_defaults(f=cmd_crawl)

    a = ap.parse_args()
    a.f(a)


if __name__ == "__main__":
    main()
