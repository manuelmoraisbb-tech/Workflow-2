import json, re, time, os, shutil, difflib
import urllib.request, urllib.error

FICHEIRO = "animes_leve.json"
CACHE = "nomes_cache.json"
MANUAIS = "nomes_manuais.json"   # opcional: {"nome original": "nome que queres"}
RELATORIO = "nomes_revisar.txt"
LIMIAR = 0.55   # abaixo disto não troca (fica para revisão)

QUERY = """query($q:String){Page(perPage:5){media(search:$q,type:ANIME){
title{romaji english} synonyms}}}"""

def norm(t):
    return re.sub(r"[^a-z0-9]+", "", (t or "").lower())

def separar(nome):
    """tira (Dublado)/(Legendado) e devolve (nome_base, etiqueta)"""
    m = re.search(r"\b(dublado|legendado)\b", nome, flags=re.I)
    tag = f" ({m.group(1).capitalize()})" if m else ""
    base = re.sub(r"[\(\[].*?[\)\]]", " ", nome)
    base = re.sub(r"\b(dublado|legendado|todos os epis[oó]dios)\b", " ", base, flags=re.I)
    return re.sub(r"\s+", " ", base).strip(" -:"), tag

def buscar(q):
    body = json.dumps({"query": QUERY, "variables": {"q": q}}).encode()
    req = urllib.request.Request(
        "https://graphql.anilist.co", data=body,
        headers={"Content-Type": "application/json", "Accept": "application/json"})
    for _ in range(4):
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                return json.load(r)["data"]["Page"]["media"]
        except urllib.error.HTTPError as e:
            if e.code == 429:
                time.sleep(int(e.headers.get("Retry-After", 30)) + 1)
            else:
                time.sleep(3)
        except Exception:
            time.sleep(3)
    return []

def escolher(q, medias):
    melhor, score = None, 0
    for m in medias:
        t = m["title"]
        cands = [t.get("romaji"), t.get("english")] + (m.get("synonyms") or [])
        s = max((difflib.SequenceMatcher(None, norm(q), norm(c)).ratio()
                 for c in cands if c), default=0)
        if s > score:
            melhor, score = m, s
    if not melhor:
        return None, 0
    t = melhor["title"]
    return (t.get("english") or t.get("romaji")), score

def carregar(caminho, padrao):
    if os.path.exists(caminho):
        with open(caminho, encoding="utf-8") as f:
            return json.load(f)
    return padrao

def main():
    with open(FICHEIRO, encoding="utf-8") as f:
        animes = json.load(f)
    if not os.path.exists(FICHEIRO + ".bak2"):
        shutil.copy(FICHEIRO, FICHEIRO + ".bak2")

    cache = carregar(CACHE, {})
    manuais = carregar(MANUAIS, {})
    revisar = []

    for i, a in enumerate(animes, 1):
        orig = a.get("nome_original") or a["nome"]
        a["nome_original"] = orig

        if orig in manuais:
            a["nome"] = manuais[orig]
            continue

        base, tag = separar(orig)
        if orig not in cache:
            novo, score = escolher(base, buscar(base))
            cache[orig] = {"nome": novo, "score": round(score, 2)}
            time.sleep(0.8)
        c = cache[orig]

        if c["nome"] and c["score"] >= LIMIAR:
            a["nome"] = c["nome"] + tag
            print(f"[{i}/{len(animes)}] {orig}  ->  {a['nome']}")
        else:
            a["nome"] = orig
            revisar.append(f"{orig}  (melhor: {c['nome']}, {c['score']})")
            print(f"[{i}/{len(animes)}] ?? {orig}")

        if i % 20 == 0:
            with open(CACHE, "w", encoding="utf-8") as f:
                json.dump(cache, f, ensure_ascii=False, indent=1)

    with open(CACHE, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False, indent=1)
    with open(FICHEIRO, "w", encoding="utf-8") as f:
        json.dump(animes, f, ensure_ascii=False, separators=(",", ":"))
    with open(RELATORIO, "w", encoding="utf-8") as f:
        f.write("\n".join(revisar))

    print(f"\nFeito. {len(revisar)} para revisar em {RELATORIO}")

main()
