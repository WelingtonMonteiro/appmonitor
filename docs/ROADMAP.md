# Roadmap — App Monitor

## Visão geral

Um botão **"Adicionar app"** → você digita **nome + porta** (e, opcionalmente, uma URL de health, um
comando de start/stop, limites de alerta) → o painel passa a mostrar aquele app ao vivo, **mesmo que
ele tenha subido fora da IDE**.

A decisão central de arquitetura: rastrear **alvos** (porta / nome de processo / PID) re-resolvidos a
cada refresh, em vez de depender do processo que a IDE lançou. Assim o monitor enxerga **qualquer**
processo (IDE, terminal, docker, serviço do sistema) e mostra **down** quando o alvo some e **up**
quando volta. Detalhes em [ARQUITETURA.md](ARQUITETURA.md).

Nomes candidatos (nome ainda provisório): App Monitor · Runtime Monitor · Port Monitor · Live Apps ·
Process Pulse · DevMonitor.

## Fases

Roadmap por fases. Cada fase é entregável sozinha. A regra de ouro: **Fase 0 tem que valer a pena
mesmo que nada mais seja feito** (adicionar um app por porta e ver mem/cpu ao vivo já é útil).

Legenda: 🔁 reaproveita do Multiple Run Monitor · 🆕 novo · 🔧 técnico.

---

## Fase 0 — MVP: "adicione um app e veja ele ao vivo"

- 🆕 **Tool window "App Monitor"** com uma tabela (mesmo estilo docker-stats do MR Monitor).
- 🆕 **Adicionar app** (diálogo): **nome + porta** (mínimo); opcionais já previstos no modelo:
  URL de health, comando start/stop, working dir, arquivo `.env`, limites de alerta, tag/cor.
- 🆕 **Resolução por porta**: a cada refresh, achar o PID em LISTEN naquela porta → árvore de processos.
- 🔁 **Colunas ao vivo**: Nome · Porta(s) · PID · Uptime · Status (up/down) · Mem · Mem % · CPU %.
- 🔁 **Kill por porta / árvore** (o clássico do `EADDRINUSE`).
- 🆕 **Persistência da lista de apps** por projeto (sobrevive a reiniciar a IDE).
- 🔁 **Status up/down** (achou/não achou o alvo no refresh).
- 🔧 **Windows desde o dia 1** (netstat/PowerShell) — dívida que o MR Monitor deixou para trás.

## Fase 1 — Observabilidade rica

- 🔁 **Sparkline de tendência de memória** por app.
- 🔁 **Gráfico de sessão completa** (eixos X/Y, pico) + **análise de memory-leak** (slope KB/min, R²,
  fração monótona, veredito) + **export** (.txt/.csv).
- 🔁 **Breakdown por processo** da árvore (PID/comando/memória/% da árvore).
- 🆕 **Health check HTTP** por app (2xx/3xx = healthy), além do check de porta.
- 🆕 **Alertas + notificações**: app caiu, memória/CPU acima do limite, leak detectado, porta liberou/ocupou.
- 🔁 **Seletor de colunas** (mostrar/ocultar) — **persistido** (o MR não persistia).

## Fase 2 — Organização e descoberta

- 🆕 **Auto-descoberta**: varrer portas em LISTEN e **sugerir apps para adicionar** (com nome do
  comando / container docker). Um clique adiciona.
- 🆕 **Grupos / tags** (backend, frontend, infra) com cor; filtros e agregados por grupo.
- 🆕 **Widget na status bar**: "N up · M down · mem total · ⚠ K", clica e abre o painel.
- 🆕 **Env viewer** para apps com env conhecido (caminho `.env` informado).
- 🆕 **Múltiplos alvos por app** (ex.: app que escuta em 2 portas; ou monitorar por nome + porta).

## Fase 3 — Vira um mini gerenciador (opcional, mas poderoso)

- 🆕 **Start / Stop / Restart via comando do usuário** por app (working dir + comando + env) — habilita
  as ações que faltavam para apps externos, sem depender da IDE.
- 🆕 **Docker-aware**: ler stats de containers via `docker stats`/CLI (nome do container como alvo).
- 🆕 **Página de Settings**: intervalos de refresh, limites padrão, cores, comportamento de alerta.

## Fase 4 — Alcance (stretch)

- 🆕 **Monitor remoto via SSH** (portas/stats de outra máquina).
- 🆕 **Histórico persistente** entre sessões (não só a sessão atual) + comparação.
- 🆕 **Mini-dashboard por app** (aba dedicada com gráficos e eventos).
- 🆕 **Regras de ação** ("se app X cair, rode comando Y" / "se mem > 90% por 5 min, notifique/mata").

---

## Matriz rápida (o que vem do MR Monitor × o que é novo)

| Funcionalidade | Origem | Fase |
|---|---|---|
| Tabela mem/cpu/portas/uptime | 🔁 | 0 |
| Adicionar app por porta/nome | 🆕 | 0 |
| Resolução de PID por porta | 🔁 (sampler) + 🆕 (alvo) | 0 |
| Persistir lista de apps | 🆕 | 0 |
| Kill por porta/árvore | 🔁 | 0 |
| Windows (netstat/PowerShell) | 🔧🆕 | 0 |
| Sparkline + gráfico + leak analysis + export | 🔁 | 1 |
| Health HTTP | 🆕 | 1 |
| Alertas/notificações | 🔁 base + 🆕 (down/porta) | 1 |
| Seletor de colunas persistido | 🔁 + 🆕 (persistência) | 1 |
| Auto-descoberta de portas | 🆕 | 2 |
| Grupos/tags | 🆕 | 2 |
| Status bar widget | 🆕 (inspirado no MR) | 2 |
| Env viewer (quando conhecido) | 🔁 | 2 |
| Start/Stop/Restart por comando | 🆕 | 3 |
| Docker stats | 🆕 | 3 |
| Settings page | 🆕 | 3 |
| SSH / histórico persistente / regras | 🆕 | 4 |
