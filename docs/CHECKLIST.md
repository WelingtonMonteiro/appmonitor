# Checklist de funcionalidades — App Monitor

Rastreamento vivo do que já existe (a copiar do **Multiple Run Monitor**) e do que é novo, por fase.
Marque `[x]` conforme entregar. Legenda: 🔁 reaproveita do MR · 🆕 novo · 🔧 técnico.

> Fonte das fases: [ROADMAP.md](ROADMAP.md). Design: [ARQUITETURA.md](ARQUITETURA.md).

---

## Decisões de projeto (fixadas)

- [x] **Linguagem principal: Kotlin** (recomendada pela JetBrains para plugins novos).
- [x] **Classes reaproveitadas ficam em Java temporariamente** (interop Kotlin↔Java), migradas
      **uma por vez** depois via *Code → Convert Java File to Kotlin File* do IntelliJ.
- [x] **Escopo da primeira leva: Fase 0 (MVP) completo.**
- [x] id do plugin: `io.github.welingtonmonteiro.appmonitor` (vendor criado no Marketplace).
- [ ] Nome final (provisório: "App Monitor" — ver candidatos no README).

---

## Fase 0 — MVP: "adicione um app e veja ele ao vivo" — ✅ v0.1.0

- [x] 🆕 **Build Kotlin+Java** (IntelliJ Platform Gradle Plugin 2.x, Gradle 9, JBR 21→bytecode 17, sinceBuild 233+).
- [x] 🆕 **plugin.xml** (id, tool window, notificationGroup) + ícones do plugin.
- [x] 🆕 **Tool window "App Monitor"** com tabela estilo docker-stats.
- [x] 🆕 **Modelo `MonitoredApp` + `Target`** (Port / ProcessName / Pid).
- [x] 🆕 **Diálogo "Adicionar app"**: nome + porta (mínimo); campos opcionais previstos no modelo
      (health URL, limites de alerta, tag — start/stop/workdir/.env reservados p/ fases seguintes).
- [x] 🆕 **Resolução por porta** a cada refresh: PID em LISTEN → árvore de processos.
- [x] 🔁 **Colunas ao vivo**: Nome · Porta(s) · PID · Uptime · Status (up/down) · Mem · Mem % · CPU %.
- [x] 🔁 **Kill por porta / árvore** (o clássico do `EADDRINUSE`).
- [x] 🆕 **Persistência da lista de apps** por projeto (`PersistentStateComponent`).
- [x] 🔁 **Status up/down** (achou/não achou o alvo no refresh).
- [x] 🔧 **Windows desde o dia 1** (netstat/PowerShell) — dívida que o MR deixou para trás.
- [x] 🔧 **Testes de lógica pura** (parsing netstat/lsof, resolução de alvo, round-trip de persistência). 36 testes.

## Fase 1 — Observabilidade rica — ✅ completa (v0.2.0–v0.3.0)

- [x] 🔁 **Sparkline de tendência de memória** por app. (v0.2.0)
- [x] 🔁 **Gráfico de sessão completa** (eixos X/Y, pico) + **análise de memory-leak**
      (slope KB/min, R², fração monótona, veredito) + **export** (.txt/.csv). (v0.2.0)
- [x] 🔁 **Breakdown por processo** da árvore (PID/comando/memória/% da árvore). (v0.3.0, aba Processes)
- [x] 🆕 **Health check HTTP** por app (2xx/3xx = healthy), além do check de porta. (v0.2.0)
- [x] 🆕 **Alertas + notificações**: app caiu, mem/CPU acima do limite, leak detectado. (v0.3.0)
- [x] 🔁 **Seletor de colunas** (mostrar/ocultar) — **persistido**. (v0.3.0)

## Fase 2 — Organização e descoberta — 🚧 parcial (v0.4.0–v0.5.0)

- [x] 🆕 **Auto-descoberta**: varrer portas em LISTEN e **sugerir apps** (nome do comando). (v0.4.0)
- [x] 🆕 **Grupos / tags** (backend, frontend, infra) com cor + **filtro** por nome/tag/porta. (v0.5.0)
      _(agregados por grupo / agrupamento colapsável ficam p/ depois)_
- [x] 🆕 **Widget na status bar**: "▲up ▼down" + mem total no tooltip; clica e abre o painel. (v0.4.0)
- [ ] 🆕 **Env viewer** para apps com env conhecido (`.env` informado ou ponte com run config).
- [ ] 🆕 **Múltiplos alvos por app** (2 portas; ou nome + porta).

## Fase 3 — Mini gerenciador (opcional) — 🚧 parcial (v0.6.0)

- [x] 🆕 **Start / Stop / Restart via comando do usuário** por app (working dir + comando + `.env`). (v0.6.0)
- [ ] 🆕 **Docker-aware**: `docker stats`/CLI (nome do container como alvo).
- [ ] 🆕 **Página de Settings**: intervalos, limites padrão, cores, comportamento de alerta.
- [ ] 🆕 **Ponte com o Multiple Run**: importar apps que o MR lançou.

## Fase 4 — Alcance (stretch)

- [ ] 🆕 **Monitor remoto via SSH** (portas/stats de outra máquina).
- [ ] 🆕 **Histórico persistente** entre sessões + comparação.
- [ ] 🆕 **Mini-dashboard por app** (aba dedicada com gráficos e eventos).
- [ ] 🆕 **Regras de ação** ("se app X cair, rode Y" / "se mem > 90% por 5 min, notifique/mata").

---

## Classes reaproveitadas do Multiple Run (status de cópia + migração p/ Kotlin)

| Classe (MR) | Fase | Copiada | Windows | → Kotlin | Observação |
|---|---|:--:|:--:|:--:|---|
| `ProcessStatsSampler` | 0 | [x] | [x] | [ ] | ps/lsof, árvore, mem/cpu/portas/uptime/kill. Camada Windows (netstat/PowerShell) adicionada. Fica em Java por ora. |
| `MemoryHistory` (+ `Analysis`) | 1 | [x] | n/a | [x] | histórico + análise de leak (slope/R²/monótono). **Escrito direto em Kotlin.** |
| `MemoryChartDialog` | 1 | [x] | n/a | [x] | gráfico de sessão + análise + **breakdown por processo** (aba Processes) + export. **Reescrito em Kotlin**, sem deps do MR. |
| Renderers de tabela (sparkline/ports/status) | 0–1 | [ ] | n/a | [ ] | reaproveitáveis do `MultirunMonitorPanel`. |

**Não vem do MR** (dependem de `RunContentManager`/`ProcessHandler`/orquestração do grupo):
`MultirunRunner*`, `MultirunRunConfiguration`, `MultirunProcessRegistry`, `RunConfigurationHelper`,
`ComposeImporter`, `EnvVarsDialog` (parte), editor de run config.

---

## Estratégia de compartilhamento de código (futuro)

- [ ] **Agora:** copiar as classes independentes (evita acoplar dois produtos jovens).
- [ ] **Depois (ambos estáveis):** extrair módulo **`process-monitor-core`** consumido pelos dois plugins.
