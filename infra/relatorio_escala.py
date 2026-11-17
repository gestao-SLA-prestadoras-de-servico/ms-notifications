"""Gera tabelas, CSV e gráficos a partir de uma execução completa de escala."""
import argparse
import csv
import json
import os
import statistics
from pathlib import Path

os.environ.setdefault("MPLCONFIGDIR", str(Path(__file__).resolve().parents[1] / "target/matplotlib-cache"))

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


def gerar(raiz, repeticoes):
    dados = json.loads((raiz / "resultados.json").read_text(encoding="utf-8"))
    rodadas = dados["resultados"]
    esperadas = {(r, n) for r in range(1, repeticoes + 1) for n in (1, 4)}
    if len(rodadas) != len(esperadas) or {(r["repeticao"], r["instancias"]) for r in rodadas} != esperadas:
        raise ValueError("Execução incompleta ou número de repetições diferente do esperado")
    if {r["instancias"] for r in rodadas} != {1, 4}:
        raise ValueError("São necessárias medições de uma e quatro instâncias")
    for r in rodadas:
        if r["falhas"] or r["duplicacoesSmtp"] or r["emailsCapturados"] != dados["eventosUnicos"]:
            raise ValueError("Integridade da execução não comprovada")
    with (raiz / "resultados.csv").open("w", encoding="utf-8", newline="") as arquivo:
        campos = [k for k, v in rodadas[0].items() if not isinstance(v, list)]
        writer = csv.DictWriter(arquivo, fieldnames=campos, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rodadas)

    plt.rcParams.update({"font.size": 10, "axes.spines.top": False, "axes.spines.right": False})
    cores = {1: "#2563eb", 4: "#0f766e"}
    fig, axes = plt.subplots(1, 2, figsize=(10, 4.5), layout="constrained")
    grupos = {n: [r for r in rodadas if r["instancias"] == n] for n in (1, 4)}
    for ax, campo, unidade in [(axes[0], "drenagemMs", "Tempo de drenagem (s)"),
                               (axes[1], "vazaoEmailsPorSegundo", "Vazão útil (e-mails/s)")]:
        for pos, n in enumerate((1, 4)):
            valores = [r[campo] / (1000 if campo == "drenagemMs" else 1) for r in grupos[n]]
            media = statistics.mean(valores)
            ax.bar(pos, media, color=cores[n], width=.55, alpha=.75)
            ax.scatter([pos] * len(valores), valores, color="black", s=25, zorder=3)
            ax.annotate(f"{media:.2f}", (pos, max(valores)), xytext=(0, 8), textcoords="offset points", ha="center")
        ax.set_xticks([0, 1], ["1 instância", "4 instâncias"])
        ax.set_ylabel(unidade)
        ax.set_ylim(bottom=0, top=ax.get_ylim()[1] * 1.15)
        ax.grid(axis="y", alpha=.18)
    fig.suptitle(f"MS-NOT-16 · {dados['eventosUnicos']} eventos únicos / {dados['entregas']} entregas\n"
                 f"Latência SMTP downstream: {dados['latenciaDownstreamMs']} ms · pontos = medições individuais")
    fig.savefig(raiz / "comparacao.png", dpi=160)
    fig.savefig(raiz / "comparacao.svg")
    plt.close(fig)

    fig, ax = plt.subplots(figsize=(9, 4.5), layout="constrained")
    for r in rodadas:
        pasta = raiz / f"r{r['repeticao']}-{r['instancias']}instancias"
        amostras = json.loads((pasta / "amostras.json").read_text(encoding="utf-8"))
        ax.plot([0] + [a["tempoMs"] / 1000 for a in amostras],
                [dados["entregas"]] + [a["pendentes"] for a in amostras],
                label=f"{r['instancias']} instância(s), repetição {r['repeticao']}", color=cores[r["instancias"]],
                linestyle="-" if r["repeticao"] % 2 else "--")
    ax.set(xlabel="Tempo desde a liberação dos consumidores (s)", ylabel="Entregas ainda não confirmadas",
           title="Drenagem observada por ACKs da aplicação", ylim=(0, dados["entregas"] + 1))
    ax.legend(); ax.grid(alpha=.18)
    fig.savefig(raiz / "drenagem.png", dpi=160)
    fig.savefig(raiz / "drenagem.svg")
    plt.close(fig)

    media1 = statistics.mean(r["vazaoEmailsPorSegundo"] for r in grupos[1])
    media4 = statistics.mean(r["vazaoEmailsPorSegundo"] for r in grupos[4])
    linhas = ["# Resultado do experimento MS-NOT-16", "",
              f"Carga SHA-256: `{dados['sha256Carga']}`.", "",
              "| Repetição | Instâncias | Drenagem (s) | E-mails/s | Falhas | Duplicações SMTP | Interna observada (ms) |",
              "|---|---|---|---|---|---|---|"]
    for r in rodadas:
        linhas.append(f"| {r['repeticao']} | {r['instancias']} | {r['drenagemMs']/1000:.3f} | "
                      f"{r['vazaoEmailsPorSegundo']:.3f} | {r['falhas']:.0f} | {r['duplicacoesSmtp']} | "
                      f"{r['latenciaInternaObservadaMs']} |")
    linhas += ["", f"Razão entre vazões médias observadas (4/1): **{media4/media1:.2f}x**.", "",
               "Esse valor descreve esta carga e este ambiente. Não é uma garantia de ganho em produção.", "",
               "![Comparação](comparacao.png)", "", "![Drenagem](drenagem.png)", "",
               "A latência é aplicada às respostas SMTP, não ao envio inteiro. O tempo exclui boot, aquecimento e publicação; "
               "inclui a liberação dos containers e a observação do último ACK/commit. A amostragem acrescenta resolução limitada ao tempo observado."]
    (raiz / "relatorio.md").write_text("\n".join(linhas) + "\n", encoding="utf-8")
    print(raiz / "relatorio.md")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("execucao", type=Path, help="Diretório com resultados.json")
    parser.add_argument("--repeticoes", type=int, default=2, choices=range(1, 6), help="Quantidade esperada por cenário (padrão: 2)")
    args = parser.parse_args()
    gerar(args.execucao, args.repeticoes)
