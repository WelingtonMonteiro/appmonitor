# App Monitor (nome provisório)

> Plugin JetBrains (IntelliJ / WebStorm / …) que transforma a IDE num **painel de runtime**
> para os apps que você acompanha — **independente de como foram iniciados** (run config da IDE,
> terminal, docker, serviço do sistema). Você adiciona um app por **nome + porta** e ele mostra
> memória, CPU, portas, uptime, saúde e análise de memory-leak ao vivo.

**Status: só planejamento.** Este repositório guarda a ideia, o roadmap e a arquitetura para
construirmos depois. Nada implementado ainda.

Origem: nasceu do **Multiple Run Monitor** (do plugin
[Multiple Run](https://github.com/WelingtonMonteiro/multiple_run)), que ficou bom demais para viver
só como aba de um plugin de orquestração. A ideia é extrair a experiência de monitoramento num
produto próprio, focado em **quem só quer observar apps rodando**.

---

## A ideia em uma frase

Um botão **"Adicionar app"** → você digita **nome + porta** (e, opcionalmente, uma URL de health,
um comando de start/stop, limites de alerta) → o painel passa a mostrar aquele app ao vivo, mesmo
que ele tenha subido fora da IDE.

## Por que um plugin separado (e não só a aba do Multiple Run)

| | Multiple Run | App Monitor |
|---|---|---|
| Foco | **Lançar** grupos de run configs | **Observar** apps rodando |
| O que enxerga | Só o que a IDE lançou (tem `ProcessHandler`) | **Qualquer** processo — por porta/nome/PID |
| Ações | Start/Stop/Restart/Debug do grupo | Monitorar, matar por porta, alertar (start/stop opcional via comando) |
| Público | Quem usa a IDE para subir tudo | Quem roda por terminal/docker/systemd e quer um dashboard na IDE |

**Conclusão:** faz sentido como produto separado **porque o valor novo está em desacoplar do
processo lançado pela IDE** — não em reempacotar o painel atual. O motor de amostragem/análise é
altamente reaproveitável; a mudança de design é rastrear **alvos** (porta/nome), não `ProcessHandler`.

## O que muda sem `ProcessHandler` (a decisão central de arquitetura)

Hoje o monitor depende do `RunContentDescriptor`/`ProcessHandler` da IDE. No standalone o app é um
**alvo** resolvido a cada refresh:

- **por porta TCP** — descobre o PID em LISTEN (lsof / `netstat -ano` / PowerShell) → árvore de processos;
- **por nome/comando** (regex no comando do processo);
- **por PID**;
- **por run config da IDE** (ponte opcional, para também monitorar o que a IDE lançou).

Se o alvo não é encontrado num refresh → status **down** (e volta a **up** quando reaparece).
Consequências:

- ❌ **Sem "restart"** para apps externos (você não os lançou) — a menos que o usuário informe um
  **comando de start/stop** por app (aí vira um mini gerenciador de processos, opcional).
- ❌ **Sem pular pro console** de um app externo.
- ⚠️ **Env viewer** só quando o env é conhecido (app lançado pela IDE, ou caminho `.env` informado).

## O que já reaproveita do Multiple Run Monitor

Memória/CPU/portas/uptime ao vivo · sparkline de tendência de memória · gráfico de sessão completa +
**análise de leak** (slope, R², monótono) + export · status healthy/down via check de porta/http ·
seletor de colunas · **kill por porta / árvore** · alertas de memória/CPU + notificações.

## MVP (Fase 0)

Tool window + **Adicionar app por porta** + memória/CPU/portas/uptime/status ao vivo + kill por porta
+ **lista de apps persistida por projeto**. Ver [docs/ROADMAP.md](docs/ROADMAP.md).

## Nomes candidatos

App Monitor · Runtime Monitor · Port Monitor · Live Apps · Process Pulse · DevMonitor.
(Verificar disponibilidade no JetBrains Marketplace e o id `io.github.welingtonmonteiro.*`.)

## Documentos

- [docs/ROADMAP.md](docs/ROADMAP.md) — fases e lista completa de funcionalidades.
- [docs/ARQUITETURA.md](docs/ARQUITETURA.md) — modelo de alvo, resolução por porta, reuso de código,
  persistência, stack e estratégia de compartilhamento com o Multiple Run.
