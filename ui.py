#!/usr/bin/env python3
import base64, gzip, html, json, re, time, uuid
from pathlib import Path
from urllib.parse import urljoin, urldefrag
from urllib.request import Request, urlopen
from urllib.error import HTTPError

# User-Agent normal, igual ao Chrome. Não uses Python-urllib
UA = "Mozilla/5.0 (Linux; Android 13) Chrome/120.0.0.0 Mobile Safari/537.36"

def pedir(url, ref=None):
    h = {"User-Agent": UA, "Referer": ref or "https://animesdigital.org/",
         "Accept": "text/html,*/*", "Accept-Language": "pt-BR,pt;q=0.9",
         "Accept-Encoding": "gzip"}
    for i in range(3):
        try:
            try: r = urlopen(Request(url, headers=h), timeout=25)
            except HTTPError as e: r = e
            raw = r.read()
            if "gzip" in (r.headers.get("Content-Encoding") or "").lower():
                try: raw = gzip.decompress(raw)
                except Exception: pass
            return raw, r.geturl()
        except Exception:
            if i == 2: raise
            time.sleep(2)

def baixar_ep(url):
    raw, final = pedir(url, url)
    txt = raw.decode("utf-8", "replace")
    if "just a moment" in txt.lower() or "attention required" in txt.lower():
        raise Exception("Cloudflare bloqueou - tenta outro User-Agent")
    assets = set()
    for m in re.finditer(r'''(?:src|href|poster)\s*=\s*["']([^"']+)["']''', txt):
        u = html.unescape(m.group(1)).strip()
        if u.startswith(("data:", "javascript:", "#")): continue
        u = urldefrag(urljoin(final, u))[0]
        if u.startswith("http") and not u.endswith((".m3u8", ".mp4")):
            assets.add(u)
    b = f"----=_NextPart_{uuid.uuid4().hex[:12]}"
    out = [f"From: <Saved>", f"Subject: {final}", "MIME-Version: 1.0",
           f'Content-Type: multipart/related; boundary="{b}"',
           f"Snapshot-Content-Location: {final}", ""]
    def add(loc, ctype, dados):
        out.append(f"--{b}"); out.append(f"Content-Type: {ctype}")
        out.append("Content-Transfer-Encoding: base64")
        out.append(f"Content-Location: {loc}"); out.append("")
        out.append(base64.encodebytes(dados).decode().strip()); out.append("")
    add(final, "text/html", raw)
    for u in list(assets)[:40]:
        try:
            d, _ = pedir(u, final)
            if len(d) < 5*1024*1024: add(u, "text/css" if u.endswith(".css") else "application/octet-stream", d)
        except Exception: pass
    out.append(f"--{b}--")
    return "\n".join(out).encode()

# --- main ---
import argparse
ap = argparse.ArgumentParser()
ap.add_argument("--entrada", default="sem_links.json")
ap.add_argument("--saida", default="mhtml")
ap.add_argument("--apenas", type=int, default=0)
ap.add_argument("--delay", type=float, default=1.0)
a = ap.parse_args()
dados = json.loads(Path(a.entrada).read_text(encoding="utf-8"))
animes = dados.get("animes", []) if isinstance(dados, dict) else dados
base = Path(a.saida); base.mkdir(exist_ok=True)
total = 0
for an in animes:
    nome = re.sub(r"[^\w-]", "_", an.get("nome", "anime"))[:50]
    pasta = base / nome; pasta.mkdir(exist_ok=True)
    for ep in an.get("episodios_sem_link", []) or an.get("episodios", []):
        if a.apenas and total >= a.apenas: break
        url = ep.get("url") or ""
        if not url: continue
        caminho = pasta / f"ep_{str(ep.get('episodio')).zfill(3)}.mhtml"
        if caminho.exists(): continue
        print(f"» {nome} ep {ep.get('episodio')}")
        try:
            caminho.write_bytes(baixar_ep(url))
            print(f"  OK {caminho.stat().st_size//1024} KB")
        except Exception as e: print(f"  ERRO: {e}")
        total += 1; time.sleep(a.delay)
print("FIM")
