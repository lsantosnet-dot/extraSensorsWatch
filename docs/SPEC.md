# Especificação: app de sensores para Galaxy Watch 9

Versão 2 (revisada para validação antes da implementação).

## 1. Objetivo e escopo

App Wear OS nativo em Kotlin para o Samsung Galaxy Watch 9 (Wear OS 7, One UI 9 Watch), de uso pessoal, instalado por ADB (sem Play Store). Mostra dados de sensores que o relógio não exibe direto.

- Interface em português do Brasil, tema escuro (OLED), sem anúncios, sem rede, sem conta. Nenhum dado sai do relógio. O manifesto não declara `INTERNET`.
- **Somente dados reais.** O app não contém valores de exemplo, simulações, implementações fake nem modo demo. Quando um dado ainda não existe (sensor ausente, permissão negada, histórico insuficiente), a tela mostra um estado explícito (seção 6), nunca um número inventado.
- **Entrega única e completa**: todas as telas, o serviço em segundo plano, as notificações, as configurações, a complicação, os testes e o README, num só passo.

Antes de codar, ler a documentação atual de Wear OS 7 (Compose for Wear OS / Material 3 for Wear, serviços em primeiro plano, permissões, Ongoing Activity, complicações) e do Samsung Health Sensor SDK. Confirmar compileSdk, targetSdk, minSdk e as permissões exigidas em vez de assumir. Registrar no README cada decisão tomada a partir dessa leitura.

## 2. Stack e arquitetura

- Kotlin, Gradle Kotlin DSL com version catalog, Jetpack Compose for Wear OS (Material 3 for Wear), Coroutines/Flow, Hilt (ou injeção manual simples, se o Hilt complicar o build).
- Camadas:
  - `sensor/`: um repositório por fonte, atrás de interface (`PressureSource`, `LightSource`, `OffBodySource`, `SoundSource`, `SkinTemperatureSource`, `SweatLossSource`). Uma única implementação real de cada. As interfaces existem para isolar Android e permitir testes unitários, não para trocar por fakes no app.
  - `domain/`: algoritmos puros em Kotlin, sem dependência de Android (seção 5). Toda a lógica testável fica aqui.
  - `data/`: Room (históricos) e DataStore (configurações).
  - `service/`: `SensorService` (serviço em primeiro plano) e alarmes.
  - `ui/`: um ViewModel por tela, cada um expondo `StateFlow<XUiState>`.
- Persistência:
  - **DataStore**: todas as configurações da seção 4.10 e o estado dos alertas (último alerta de chuva, alerta de sol já disparado).
  - **Room**:
    - `pressure_sample`: timestamp, hPa. Guarda 48 h e apaga o resto.
    - `sun_day`: data, total do dia em segundos.
    - `sun_application`: timestamp de cada "Apliquei protetor".
    - `climb_session`: início, fim, subida, descida, altitude mínima e máxima.
    - `climb_point`: perfil da sessão ativa, 1 ponto a cada 10 s.
    - `skin_temp_reading`: timestamp, °C. Guarda 30 dias.
    - `sweat_result`: timestamp, litros, duração da corrida.

## 3. Navegação e estilo

- `SwipeDismissableNavHost` com dois destinos: o **pager** (rota principal) e as **telas de edição** de cada configuração.
- O pager é um `HorizontalPager` com 10 páginas, sem dar a volta. Na página 0, deslizar para a direita fecha o app (comportamento padrão do Wear).
- Coroa/bezel giratório troca de página no pager e altera valores nas telas de edição (`rotaryScrollable`).
- Indicador de página no rodapé (`HorizontalPageIndicator`).
- Toque numa linha do Resumo leva à página correspondente.

Estilo:

- Fundo preto puro (#000000).
- Número principal com altura de cerca de 21% do diâmetro da tela (o equivalente aos 88 px num círculo de 420 px do protótipo), em `sp`, com `fontFeatureSettings = "tnum"`.
- Rótulo do topo em caixa alta, `letterSpacing` de cerca de 0,16 em, na cor de destaque da tela.
- Texto secundário #9AA4B2; texto de apoio #C3CAD4.
- Alvos de toque de pelo menos 48 dp. Todo conteúdo dentro do círculo, com `ScreenScaffold` e padding para telas redondas.
- Fonte do sistema.
- Números no formato pt-BR: vírgula decimal ("33,8"), ponto de milhar ("10.000") e sinal de menos tipográfico ("−4,2").
- Durações: "38 min", "1 h 12", "2 h".

| # | Página | Cor de destaque |
|---|---|---|
| 0 | Resumo | neutra (#E6EAF0) |
| 1 | Barômetro | #7AB8FF |
| 2 | Chuva | #4DD0E1 (o rótulo de estado varia, ver 4.3) |
| 3 | Subida | #F472B6 |
| 4 | Luz | #FFC857 |
| 5 | Sol | #FF8F3F |
| 6 | Som | #FF8A7A |
| 7 | Pele | #6EE7B7 |
| 8 | Suor | #B69CFF |
| 9 | Ajustes | neutra |

O protótipo é referência visual. Os controles fora do círculo (setas, chips, painel lateral) e os botões "Ver outro cenário" e "Simular 2 h de sol" são só do protótipo e não existem no app.

## 4. Telas

### 4.0 Resumo

Seis linhas tocáveis (pílulas com ponto colorido, rótulo à esquerda e valor à direita):

| Linha | Valor | Leva a |
|---|---|---|
| Altitude | "852 m" | 1 |
| Chuva | rótulo curto do estado: "Queda rápida", "Caindo", "Estável" ou "Subindo" | 2 |
| Subida | subida da sessão ativa ou da última sessão, "312 m" | 3 |
| Sol | tempo desde a última aplicação de protetor, "38 min" | 5 |
| Som | último valor medido, "42 dB" | 6 |
| Pele | última leitura, "33,8 °C" | 7 |

Sem dado, o valor é "—". As linhas usam `ScalingLazyColumn` para caberem no círculo.

### 4.1 Barômetro

- Pressão atual em hPa (número grande, 1 casa decimal, por exemplo "915,2").
- Pílula "Altitude 852 m".
- Mini gráfico das últimas 3 h (amostras do Room).
- Texto "últimas 3 h", seguido do rótulo da tendência (4.3).
- Leitura ao vivo enquanto a tela está aberta (`SENSOR_DELAY_NORMAL`), suavizada (5.1).

### 4.2 Altitude e calibração

`altitude = SensorManager.getAltitude(p0, p)`. O valor de `p0` vem das configurações e pode ser definido de duas formas:

1. **pressão ao nível do mar** (padrão 1013,25 hPa, ou o valor de uma previsão do tempo);
2. **altitude conhecida**: o usuário informa a altitude em metros e o app calcula `p0 = p / (1 − h/44330)^5,255`, usando a pressão suavizada atual.

### 4.3 Chuva (tendência de pressão)

- Número grande: ΔP com sinal e 1 casa decimal, seguido de "hPa / 3 h".
- Pílula com o rótulo do estado, na cor do estado.
- Mini gráfico de 3 h e mensagem.

| Estado | Condição (padrão, configurável) | Cor | Mensagem |
|---|---|---|---|
| Queda rápida | ΔP ≤ −4,0 | #4DD0E1 | Chuva provável nas próximas horas |
| Caindo | −4,0 < ΔP ≤ −1,5 | #80DEEA | O tempo pode piorar |
| Estável | −1,5 < ΔP < +1,5 | #A5B4C3 | Sem mudança de tempo à vista |
| Subindo | ΔP ≥ +1,5 | #86EFAC | O tempo tende a melhorar |

Sem 3 h de histórico, a tela mostra o estado "Coletando dados" (seção 6).

**Alerta**: notificação com vibração ao entrar em "Queda rápida", com:

- **histerese**: depois de disparar, só rearma quando ΔP > −3,0;
- **intervalo mínimo** de 6 h entre alertas;
- interruptor nas configurações.

Ajustes leva um texto fixo: "Regra prática baseada na pressão do ar. Não é previsão do tempo."

### 4.4 Subida acumulada

- Número grande: subida da sessão em metros.
- Linhas: "Descida 298 m", "altitude atual 852 m" e a duração ("1 h 24").
- Perfil de altitude da sessão (área preenchida).
- Botões:
  - sem sessão: **Iniciar**;
  - sessão ativa: **Parar**;
  - sessão parada: **Nova sessão**, com confirmação antes de zerar.
- Enquanto a sessão está ativa, o `SensorService` lê a pressão a 1 Hz em segundo plano (5.3) e a Ongoing Activity mostra "Subida 312 m".
- Ao parar, a sessão fica gravada no Room e continua sendo mostrada até uma nova.

### 4.5 Luz ambiente

- Número grande: lux, sem decimais, com ponto de milhar.
- Descrição por faixa:

| Faixa (lx) | Descrição |
|---|---|
| < 10 | Escuro |
| 10–50 | Penumbra |
| 50–200 | Interior pouco iluminado |
| 200–1.000 | Interior bem iluminado |
| 1.000–10.000 | Externo, sombra ou nublado |
| 10.000–50.000 | Luz forte |
| ≥ 50.000 | Sol direto |

- Barra em escala logarítmica de 1 a 100.000 lx, com marcador na posição `log10(lux)/5`, legendas "escuro" e "sol direto", e marca no limite de luz forte configurado.
- Leitura ao vivo enquanto a tela está aberta.

### 4.6 Sol (exposição solar), prioridade alta

- Número grande: tempo sob luz forte **desde a última aplicação de protetor**, ou desde 00:00 se não houve aplicação hoje. Legenda: "desde o protetor".
- Barra de progresso até a meta (padrão 2 h), com as legendas "0" e "alerta em 2 h".
- Linha secundária: "Hoje: 1 h 12 no total".
- Ao atingir a meta, aparece a pílula "Hora de reaplicar protetor" e é enviada uma notificação com vibração e a ação **Apliquei protetor**. O alerta dispara uma vez por aplicação, sem repetição.
- Botão **Apliquei protetor** (na tela e na notificação):
  - zera o contador desde o protetor;
  - grava o horário em `sun_application`;
  - mostra "Protetor aplicado às 10:42".
- Meia-noite: o total do dia é fechado no Room e os dois contadores recomeçam do zero.
- Estado "Medindo em segundo plano" ou "Pausado: relógio fora do pulso", conforme o serviço.

### 4.7 Decibelímetro

Só captura com a tela aberta e o app em primeiro plano. Para ao sair da tela.

- Número grande: dB atual, inteiro, atualizado a 4 Hz.
- Rótulo por faixa:

| Faixa (dB) | Rótulo |
|---|---|
| < 40 | Silencioso |
| 40–55 | Moderado |
| 55–70 | Ruidoso |
| 70–85 | Muito ruidoso |
| ≥ 85 | Perigoso em exposição longa |

- Barra linear de 30 a 100 dB, com marcador.
- Linha "média 44 dB · pico 58 dB", sobre a sessão desde a abertura da tela. Zera ao sair.
- Aviso fixo: "valor aproximado".
- Pede `RECORD_AUDIO` ao abrir a tela pela primeira vez.

### 4.8 Temperatura da pele

- Número grande: última leitura em °C, com 1 casa decimal.
- Legenda: "Pele do pulso".
- Pílula "+0,2 °C vs. sua média" (5.6).
- Horário da leitura ("medido às 14:05").
- Aviso fixo: "não é temperatura corporal".
- Fonte: Samsung Health Sensor SDK.
  - Se o relógio suporta o tracker contínuo, o `SensorService` grava 1 leitura a cada 30 min.
  - Senão, a tela tem o botão **Medir agora** (tracker sob demanda).
  - A escolha é feita em tempo de execução, consultando os trackers suportados.

### 4.9 Perda de suor

- Fluxo:
  1. **Iniciar corrida**;
  2. tela "corrida em andamento", com cronômetro;
  3. **Finalizar**;
  4. "Calculando…";
  5. resultado em L, com 2 casas decimais, e a legenda "perda de suor estimada".
- Tela inicial: "Meça depois de uma corrida" e "Última medição: 0,42 L · 12/11", vinda do Room. Sem histórico, a segunda linha some.
- Seguir o code lab oficial da Samsung para o tracker de sweat loss, inclusive a sessão de exercício de corrida que ele exigir (provavelmente via Health Services `ExerciseClient`) e os dados do usuário que ele pedir.
- A corrida em andamento roda no `SensorService` e aparece como Ongoing Activity.

### 4.10 Ajustes

Lista rolável (`ScalingLazyColumn`). Cada item abre uma tela de edição com stepper e coroa.

| Item | Padrão | Passo e faixa |
|---|---|---|
| Pressão ao nível do mar (p0) | 1013,25 hPa | 0,25; 950–1050 |
| Calibrar por altitude conhecida | — | 1 m; −500 a 9000 |
| Limiar "Queda rápida" | −4,0 hPa | 0,5 |
| Limiar "Caindo" / "Subindo" | ±1,5 hPa | 0,5 |
| Alerta de chuva | ligado | — |
| Limite de luz forte | 10.000 lx | 1.000; 2.000–50.000 |
| Meta de exposição | 2 h | 15 min; 30 min–4 h |
| Alerta de protetor | ligado | — |
| Offset do decibelímetro | 90 dB | 0,5; 60–120 |
| Medição em segundo plano | ligada | — |

Também tem a entrada "Sobre as medições", com os avisos fixos:

- o sensor mede luz visível, não UV; dia nublado e manga cobrindo o relógio alteram o resultado;
- a tendência de chuva é uma regra prática;
- o decibelímetro não é um SPL certificado; calibre comparando com um medidor no celular;
- a temperatura da pele não é temperatura corporal.

## 5. Algoritmos (camada `domain/`, Kotlin puro)

### 5.1 Pressão

- **Amostra de fundo** (a cada 5 min): registrar o sensor por cerca de 10 s, usar a **mediana** das leituras e gravar no Room.
- **Exibição ao vivo**: filtro exponencial (α = 0,1 a cerca de 5 Hz).
- Altitude e ΔP sempre a partir de valores suavizados.

### 5.2 Tendência

- `ΔP = P(agora) − P(3 h atrás)`.
- `P(3 h atrás)`: a amostra mais próxima de `agora − 3 h`, dentro de ±10 min.
- Sem amostra válida nessa janela, o resultado é `Indisponível` (com o tempo que falta para ter 3 h).
- Classificação e alerta conforme 4.3.
- Funções puras:
  - `classify(delta, thresholds)`;
  - `shouldAlert(state, armed, lastAlertAt, now)`.

### 5.3 Subida acumulada

- Altitude suavizada a 1 Hz.
- O algoritmo guarda um ponto de referência "confirmado":
  - se `alt − ref ≥ +2 m`: soma em subida e move a referência;
  - se `alt − ref ≤ −2 m`: soma em descida e move a referência;
  - a histerese de 2 m fica configurável no código.
- Função pura: `ClimbAccumulator`.

### 5.4 Exposição solar

Ponto crítico: `TYPE_LIGHT` é um sensor *on-change*. Sob sol constante ele pode ficar minutos sem enviar evento, e esse silêncio não pode ser tratado como falta de dado.

- O serviço guarda o **último lux recebido**.
- Um **tick a cada 30 s** avalia esse valor. Em cada tick, `Δt = agora − tickAnterior` (intervalo real, nunca fixo) e o tempo só é somado se todas as condições valem:
  - `Δt ≤ 90 s`: um Δt maior indica serviço parado ou CPU dormindo sem tick, e o intervalo é descartado;
  - relógio no pulso;
  - último lux > limite.
- **Fora do pulso**: usar `Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT` se existir. Se não existir, só a regra dos 90 s.
- **Virada da meia-noite** dentro de um intervalo: dividir o Δt entre os dois dias.
- Função pura: `SunExposureAccumulator.onTick(now, lux, onWrist)`, que devolve o novo estado e os eventos (meta atingida).
- Registrar o sensor de luz com `maxReportLatencyUs` (batching) para poupar bateria.

### 5.5 Decibelímetro

- `AudioRecord`: mono, PCM 16 bits, 16 kHz, blocos de 100 ms.
- `dBFS = 20·log10(RMS/32767)`; `dB = dBFS + offset`.
- Valor mostrado: média energética do último 1 s.
- Média da sessão: Leq (média de energia, não de dB).
- Pico: o maior bloco de 100 ms.
- Silêncio digital (RMS = 0) vira o piso de 0 dB, nunca `−∞`.

### 5.6 Variação da pele

- Média pessoal: média das leituras dos **7 dias anteriores**, sem contar o dia de hoje.
- Exige pelo menos **3 dias com leitura**. Antes disso, a pílula mostra "Calculando sua média · 1 de 3 dias".

## 6. Estados sem dado

Nenhuma tela mostra número inventado. Estados possíveis em cada tela:

| Situação | Exibição |
|---|---|
| Sensor ausente no relógio | "Sensor indisponível neste relógio" |
| Permissão negada | Explicação curta e botão **Permitir**, que reabre o pedido ou as configurações do sistema |
| Aguardando a primeira leitura | Indicador de carregamento, no máximo 5 s |
| Histórico insuficiente (Chuva) | "Coletando dados · faltam 1 h 20" |
| Medição em segundo plano desligada | Aviso com atalho para Ajustes, nas telas Chuva e Sol |
| SDK Samsung indisponível, modo desenvolvedor do Health Platform desligado ou erro de conexão | Mensagem com o motivo e instrução curta, nas telas Pele e Suor |

## 7. Serviço em segundo plano

- **`SensorService`**: um único serviço em primeiro plano, que faz:
  - amostra de pressão a cada 5 min (5.1);
  - luz e exposição solar (5.4);
  - off-body;
  - sessão de subida, quando ativa;
  - leitura periódica da temperatura da pele, se houver tracker contínuo;
  - corrida de suor, quando ativa.
- **Ciclo de vida**:
  - inicia ao abrir o app, se "Medição em segundo plano" estiver ligada e a permissão de notificação concedida;
  - reinicia após o boot (`RECEIVE_BOOT_COMPLETED`) e após atualização do app;
  - para quando o usuário desliga a opção.
- **Tipo de foreground service**: escolher o tipo correto no Wear OS 7 e justificar no README. Candidato: `health`, que exige `FOREGROUND_SERVICE_HEALTH` mais uma das permissões aceitas pelo sistema, como `HIGH_SAMPLING_RATE_SENSORS` ou as de sensores corporais. Confirmar na documentação atual.
- **Notificação discreta** em canal de baixa importância ("Medindo sensores"), ligada a uma **Ongoing Activity** (ícone no mostrador e no launcher de apps recentes).
- **Doze e sono da CPU**: um `delay` de coroutine não acorda a CPU. Garantir que os ticks de 30 s (luz) e de 5 min (pressão) aconteçam de verdade. Avaliar, nesta ordem:
  1. sensores com batching e FIFO, mais variantes *wake-up* quando houver;
  2. `AlarmManager` exato com wakelock curto.

  Documentar no README o que foi escolhido e o impacto esperado na bateria.
- **Canais de notificação**:
  - "Medindo sensores": baixa importância;
  - "Alerta de chuva": alta, com vibração;
  - "Protetor solar": alta, com vibração e a ação "Apliquei protetor".

## 8. Permissões e fluxo de pedido

Pedir cada permissão no contexto em que ela é usada, nunca todas de uma vez na abertura:

| Permissão | Quando pedir |
|---|---|
| `POST_NOTIFICATIONS` | No primeiro uso, antes de iniciar o serviço |
| `RECORD_AUDIO` | Ao abrir a tela Som |
| Permissões de sensores de saúde e do SDK Samsung (nomes exatos conforme a documentação do Wear OS 7 e do SDK) | Ao abrir Pele ou Suor |
| `ACTIVITY_RECOGNITION` | Se o fluxo de corrida exigir |

Permissões de manifesto confirmadas na documentação: `FOREGROUND_SERVICE`, o tipo específico, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` e `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM` se o alarme exato for usado.

## 9. Melhorias incluídas

1. Ação **Apliquei protetor** direto na notificação.
2. **Complicação** "Sol", com tipos `RANGED_VALUE` e `SHORT_TEXT`, mostrando o tempo desde o protetor contra a meta. Atualizada pelo serviço a cada minuto.
3. **Ongoing Activity** para o serviço, a sessão de subida e a corrida.
4. Calibração de p0 por altitude conhecida já na primeira versão (antes era "opção futura").
5. Navegação pela coroa ou bezel giratório.

## 10. Samsung Health Sensor SDK

- O `.aar` é baixado manualmente em developer.samsung.com/health/sensor (não está no Maven) e colocado em `app/libs/`. Ele **não é versionado**: entra no `.gitignore` por causa da licença.
- O Gradle falha com mensagem clara se o arquivo estiver ausente.
- Requisitos:
  - relógio físico (o SDK não funciona em emulador);
  - modo desenvolvedor do Health Platform ligado. Como o app é de uso pessoal, não é preciso ser parceiro Samsung.
- Ler os javadocs e o code lab oficial para os nomes exatos de classes, `HealthTrackerType` e permissões. Não presumir nomes.

## 11. Testes

Testes unitários (JUnit) em `src/test`. As séries de entrada são geradas no próprio código de teste e nada disso vai para o APK.

- **Tendência**:
  - limites exatos (−4,0; −1,5; +1,5);
  - histórico com menos de 3 h;
  - amostra fora da janela de ±10 min;
  - histerese e intervalo de 6 h do alerta.
- **Subida**:
  - subida lenta;
  - ruído puro de ±1,5 m (deve dar 0);
  - subida e descida;
  - degrau único de 2 m.
- **Sol**:
  - sol contínuo sem eventos do sensor;
  - lacuna de 10 min (descartada);
  - relógio fora do pulso;
  - meta atingida uma única vez;
  - reinício após "Apliquei protetor";
  - virada da meia-noite.
- **dB**:
  - RMS conhecido para dB;
  - Leq contra a média aritmética;
  - RMS = 0.
- **Calibração**: `p0` por altitude conhecida, ida e volta com `getAltitude`.
- **Pele**: média de 7 dias, mínimo de 3 dias e exclusão do dia de hoje.

## 12. Entrega e critérios de aceite

- Projeto Gradle completo, compilando com `./gradlew assembleDebug` (com o `.aar` presente), passando em `./gradlew test` e sem avisos de lint de nível erro.
- O README cobre:
  - pré-requisitos;
  - download do `.aar`;
  - ativação do modo desenvolvedor do Health Platform;
  - instalação por ADB via Wi-Fi;
  - decisões de compileSdk/targetSdk, tipo de FGS, estratégia anti-Doze e permissões;
  - como calibrar altitude e dB;
  - limitações conhecidas.
- Critérios no relógio, verificados pelo usuário:
  1. As 10 páginas abrem e mostram dados reais ou um estado da seção 6.
  2. Com o app fechado, a contagem do Sol e as amostras de pressão continuam (conferir após 1 h).
  3. Após reiniciar o relógio, o serviço volta sozinho.
  4. O alerta de protetor dispara ao atingir a meta, e a ação da notificação zera o contador.
  5. A complicação "Sol" aparece no editor de mostrador.
