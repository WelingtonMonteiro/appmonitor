# Arquitetura — App Monitor

Notas de design para quando começarmos. O ponto central: **desacoplar do processo lançado pela IDE**.

---

## 1. Modelo de "alvo" (o coração da mudança)

No Multiple Run Monitor, cada linha é um `ProcessHandler`/`RunContentDescriptor` da IDE. Aqui, cada
linha é um **`MonitoredApp`** (definição persistida) que é **resolvido a um PID vivo a cada refresh**:

```
MonitoredApp {
  id            // uuid estável
  name          // "eparts-api"
  match: Target // como encontrar o processo
  healthUrl?    // http://localhost:3003/health (opcional)
  startCmd?     // comando + workingDir + envFile (opcional -> habilita Start/Stop/Restart)
  stopCmd?
  memAlert?     // % ou MB
  cpuAlert?     // %
  tag?, color?  // organização
}

Target =
  | Port(port)                 // resolve PID em LISTEN naquela porta
  | ProcessName(regex)         // casa no comando do processo
  | Pid(pid)                   // PID fixo
  | RunConfig(name)            // ponte: processo lançado pela IDE (quando disponível)
```

**Ciclo de refresh (a cada ~2s, fora da EDT):**
1. Para cada `MonitoredApp`, **resolver o alvo → rootPid** (ou "não encontrado").
2. Coletar árvore de processos do rootPid; amostrar mem/cpu/portas numa chamada `ps`/`lsof` batelada.
3. Health: se `healthUrl` → HTTP; senão check de porta; senão up=encontrado.
4. Montar as linhas na EDT (preservando seleção — lição já aprendida no MR: guardar por chave estável,
   aqui o `MonitoredApp.id`, não o handler).

Estado **up/down** é derivado de "resolveu ou não". Um app pode existir na lista e estar **down**
(ainda não subiu / caiu) — diferente do MR, onde a linha só existe se o processo existe.

## 2. Resolução de PID por porta (multiplataforma desde o início)

- **Linux/macOS**: `lsof -nP -iTCP:<porta> -sTCP:LISTEN` (ou `ss`/`/proc`).
- **Windows**: `netstat -ano | findstr :<porta>` → PID; ou PowerShell `Get-NetTCPConnection`.
- Árvore de processos: reaproveitar `ProcessStatsSampler.processTreePids(rootPid)` do Multiple Run.

> Windows foi dívida do MR Monitor (portas/kill dependiam de lsof). Aqui já entra como requisito.

## 3. Reuso de código do Multiple Run

Classes candidatas a **copiar primeiro, extrair depois**:

- `ProcessStatsSampler` — ps/lsof, árvore de processos, mem/cpu/portas, uptime, kill. **Independente
  da IDE.** Precisa da camada Windows.
- `MemoryHistory` (+ `Analysis`) — histórico e análise de leak (slope/R²/monótono). **Independente.**
- `MemoryChartDialog` — gráfico + análise + breakdown + export (tirar dependências específicas do MR).
- Renderers da tabela (sparkline, ports, status) — reaproveitáveis.

O que **não** vem: tudo que depende de `RunContentManager`/`ProcessHandler`/env de launch e da
orquestração do grupo.

### Estratégia de compartilhamento
- **Começo (velocidade):** **copiar** as classes independentes para o novo plugin. Evita acoplar dois
  produtos jovens.
- **Depois (quando ambos estabilizarem):** extrair um módulo **`process-monitor-core`** (Gradle
  multiprojeto ou lib publicada) consumido pelos dois plugins. Evita divergência do sampler.

## 4. Persistência

- `PersistentStateComponent` (nível **projeto** para a lista de apps; opção **global** para apps que
  você monitora em qualquer projeto — ex.: um postgres local).
- Persistir também preferências do painel (colunas ocultas, intervalos) — o MR não fazia; aqui é padrão.
- Formato versionado (migração futura).

## 5. UI / integração com a IDE

- **Tool window** "App Monitor" (bottom stripe), com toolbar: Add app · Remove · Refresh · Kill by
  port · Column chooser · (Fase 3) Start/Stop/Restart · Settings.
- **Diálogo Add app**: form com nome + porta + campos opcionais (validação de porta, dedup por id).
- **Status bar widget** (Fase 2) e **notificações** (`NotificationGroup`) como no MR.
- **Auto-descoberta** (Fase 2): varre portas em LISTEN → lista "processos não monitorados" com botão
  "Adicionar".

## 6. Ações e o limite do "sem ProcessHandler"

| Ação | App externo | Como habilitar |
|---|---|---|
| Monitorar (mem/cpu/porta/uptime/health) | ✅ | resolução por alvo |
| Kill por porta / árvore | ✅ | já dá (mata por PID) |
| **Restart / Start / Stop** | ❌ por padrão | ✅ se o usuário informar `startCmd`/`stopCmd` |
| Pular pro console | ❌ | só para alvo `RunConfig` (ponte com a IDE) |
| Env viewer | ⚠️ | só com `.env` informado ou alvo `RunConfig` |

Ou seja: as ações "de gerência" ficam atrás de **configuração opcional por app** — quem só quer
observar não configura nada; quem quer controlar informa os comandos.

## 7. Stack (alinhada ao Multiple Run)

- **IntelliJ Platform Gradle Plugin 2.x** em Gradle 9 (mesma base do MR 1.43.0+).
- Java **ou** Kotlin (avaliar Kotlin para começar limpo; o MR é Java).
- `sinceBuild` 233+, sem upper bound; **Plugin Verifier** com a matriz do Marketplace (copiar o setup do MR).
- Testes JUnit desde o início na **lógica pura** (resolução de alvo, parsing de netstat/lsof, análise
  de leak) — o mesmo padrão "extrair estático e testar" do MR.
- id sugerido: `io.github.welingtonmonteiro.appmonitor` (confirmar).

## 8. Relação com o Multiple Run

Não competem — se complementam. O App Monitor observa alvos vivos (porta / processo / PID / container)
qualquer que seja quem os lançou, então já cobre o que o Multiple Run sobe. Uma ponte para o Multiple
Run **publicar** no monitor os apps que lançou foi considerada e está **fora de escopo por ora**.

---

## Riscos / questões em aberto (decidir ao começar)

- **Porta reutilizada / troca de PID** entre refreshes: tratar como "mesmo app" enquanto a porta bate.
- **Permissões**: ler processos de outros usuários (root/systemd) pode exigir cuidado por plataforma.
- **Custo do lsof/netstat** a cada 2s com muitos apps: batelar chamadas (o MR já batela — manter).
- **Kotlin vs Java**: decidir antes da primeira linha (afeta reuso direto das classes Java do MR).
- **Escopo do Fase 3 (start/stop)**: cuidado para não virar um terminal — manter simples e opt-in.
