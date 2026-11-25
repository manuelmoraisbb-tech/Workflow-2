import json, re, time, shutil, os
import urllib.request, urllib.error

FICHEIRO = "animes_leve.json"
CACHE = "imagens_cache.json"   # guarda o progresso (podes parar e continuar)
NAME_KEYS = ["titulo", "título", "title", "nome", "name"]
IMG_KEYS = ["imagem", "image", "img", "capa", "cover", "poster", "thumb", "thumbnail"]

QUERY = "query($q:String){Media(search:$q,type:ANIME){coverImage{extraLarge large}}}"

def limpar(nome):
    nome = re.sub(r"[\(\[].*?[\)\]]", " ", nome)
    nome = re.sub(r"\b(dublado|legendado|dub|sub|todos os epis[oó]dios)\b", " ", nome, flags=re.I)
    return re.sub(r"\s+", " ", nome).strip(" -:")

def buscar(nome):
    body = json.dumps({"query": QUERY, "variables": {"q": nome}}).encode()
    req = urllib.request.Request(
        "https://graphql.anilist.co", data=body,
        headers={"Content-Type": "application/json", "Accept": "application/json"})
    for _ in range(4):
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                d = json.load(r)
            c = d["data"]["Media"]["coverImage"]
            return c.get("extraLarge") or c.get("large")
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            if e.code == 429:
                time.sleep(int(e.headers.get("Retry-After", 30)) + 1)
                continue
            time.sleep(3)
        except Exception:
            time.sleep(3)
    return None

def achar_itens(data):
    """devolve lista de (nome, dict) seja lista, dict de dicts ou {'animes': [...]}"""
    if isinstance(data, list):
        lista = [(None, x) for x in data if isinstance(x, dict)]
    elif isinstance(data, dict):
        if all(isinstance(v, dict) for v in data.values()):
            lista = list(data.items())
        else:
            lista = []
            for v in data.values():
                if isinstance(v, list) and v and isinstance(v[0], dict):
                    lista = [(None, x) for x in v]
                    break
    else:
        lista = []
    out = []
    for chave, item in lista:
        nome = next((item[k] for k in NAME_KEYS if isinstance(item.get(k), str)), chave)
        if nome:
            out.append((nome, item))
    return out

def main():
    with open(FICHEIRO, encoding="utf-8") as f:
        data = json.load(f)
    if not os.path.exists(FICHEIRO + ".bak"):
        shutil.copy(FICHEIRO, FICHEIRO + ".bak")

    cache = {}
    if os.path.exists(CACHE):
        with open(CACHE, encoding="utf-8") as f:
            cache = json.load(f)

    itens = achar_itens(data)
    print(f"{len(itens)} animes encontrados")
    falhas = []

    for i, (nome, item) in enumerate(itens, 1):
        if nome not in cache:
            url = buscar(limpar(nome))
            if url:
                cache[nome] = url
            time.sleep(0.8)  # limite da API: ~90 pedidos/min
        url = cache.get(nome)
        if url:
            chave_img = next((k for k in IMG_KEYS if k in item), "imagem")
            item[chave_img] = url
            print(f"[{i}/{len(itens)}] OK  {nome}")
        else:
            falhas.append(nome)
            print(f"[{i}/{len(itens)}] --  {nome}")
        if i % 20 == 0:
            with open(CACHE, "w", encoding="utf-8") as f:
                json.dump(cache, f, ensure_ascii=False, indent=1)

    with open(CACHE, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False, indent=1)
    with open(FICHEIRO, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    with open("imagens.json", "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False, indent=2)

    print(f"\nFeito. {len(itens) - len(falhas)} com imagem, {len(falhas)} sem.")
    if falhas:
        print("Sem imagem:", *falhas, sep="\n  ")

main()
