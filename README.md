# Sensores: app para Galaxy Watch 9

App Wear OS nativo, de uso pessoal, que mostra dados de sensores que o relógio não exibe direto: barômetro e altitude, tendência de chuva, subida acumulada, luz ambiente, exposição solar, decibelímetro, temperatura da pele e perda de suor.

- Português do Brasil, tema escuro, sem anúncios, sem rede (o app não declara `INTERNET`).
- Somente dados reais: sem valores de exemplo, simulação ou modo demo. Sem dado, a tela diz o motivo.
- Especificação completa: [`docs/SPEC.md`](docs/SPEC.md).

## Instalar no relógio (pelo celular, com Termux)

1. Baixe o `.apk` no celular. Ele fica em Downloads.
2. No Termux, na primeira vez, rode `termux-setup-storage` para o Termux enxergar a pasta Downloads.
3. Conecte o ADB ao relógio por Wi-Fi, como você já faz, e instale:
   ```
   adb install -r ~/storage/downloads/sensores.apk
   ```
   O `-r` atualiza o app mantendo os dados. Todas as compilações são assinadas com a mesma chave do projeto (`keystore/project-debug.jks`), então atualizar nunca exige desinstalar.

### Antes de usar Pele e Suor: modo desenvolvedor do Health Platform

O Samsung Health Sensor SDK só funciona para apps não parceiros com o modo desenvolvedor do Health Platform ligado. Conforme a documentação da Samsung:

1. No relógio, abra **Configurações › Aplicativos › Health Platform**.
2. Toque várias vezes no título "Health Platform" até aparecer "[Dev mode]".

Sem isso, as telas Pele e Suor mostram "Ative o modo desenvolvedor do Health Platform".

## Compilar

Pré-requisitos: JDK 17 ou mais novo e Android SDK com a plataforma `android-37.1` e as build-tools 37.

1. Baixe o Samsung Health Sensor SDK em developer.samsung.com/health/sensor (é preciso login Samsung) e copie `samsung-health-sensor-api-1.4.1.aar` para `app/libs/`. O arquivo **não é versionado** por causa da licença; sem ele o Gradle para com uma mensagem explicando o que fazer.
2. Crie `local.properties` com `sdk.dir=/caminho/do/android-sdk`.
3. Rode:
   ```
   ./gradlew assembleRelease   # APK em app/build/outputs/apk/release/
   ./gradlew test              # testes unitários e renderização das telas
   ./gradlew lintDebug
   ```

## Telas

O pager horizontal tem 10 páginas. A coroa ou o bezel giratório troca de página. Tocar numa linha do Resumo abre a tela correspondente.

| # | Tela | Fonte |
|---|---|---|
| 0 | Resumo | todas |
| 1 | Barômetro e altitude | `Sensor.TYPE_PRESSURE` |
| 2 | Tendência de pressão (chuva) | histórico de pressão no Room |
| 3 | Subida acumulada | barômetro a 1 Hz durante a sessão |
| 4 | Luz ambiente | `Sensor.TYPE_LIGHT` |
| 5 | Sol (exposição solar) | luz + serviço em segundo plano |
| 6 | Decibelímetro | `AudioRecord`, só com a tela aberta |
| 7 | Temperatura da pele | Samsung Health Sensor SDK |
| 8 | Perda de suor | Samsung Health Sensor SDK + contador de passos |
| 9 | Ajustes | atalho para a lista de ajustes e para "Sobre as medições" |

Também há uma **complicação "Sol"** para o mostrador, com o tempo desde o protetor contra a meta.

## Decisões técnicas

### SDK

| Item | Valor | Motivo |
|---|---|---|
| compileSdk | 37.1 | Exigido pelo Compose 1.13 (BOM 2026.11.01) |
| targetSdk | 37 | Última plataforma estável disponível |
| minSdk | 34 | O app é só para o Galaxy Watch 9; o mínimo permite usar o tipo de serviço `health` sem checagens de versão |

Também foram usados AGP 9.5.2 (com Kotlin embutido), Kotlin 2.4.21 e Gradle 9.9.2.

### Serviço em primeiro plano: tipo `health`

- **O que roda nele:** o `SensorService` faz toda a medição fora da tela: amostra de pressão a cada 5 min, exposição solar, detecção de pulso, sessão de subida, temperatura da pele periódica e corrida de perda de suor.
- **Tipo escolhido:** `health`, o tipo previsto para leitura de sensores corporais e de atividade.
- **Pré-requisitos:** o tipo exige `FOREGROUND_SERVICE_HEALTH` mais uma permissão de uma lista do sistema. O app usa `HIGH_SAMPLING_RATE_SENSORS`, que é concedida na instalação, assim o serviço inicia sem depender de permissões de saúde.
- **Boot:** o tipo `health` pode iniciar a partir do `BOOT_COMPLETED`, então o serviço volta sozinho depois de reiniciar o relógio.
- **Notificação:** o serviço mostra uma notificação discreta, no canal de baixa importância "Medindo sensores", ligada a uma Ongoing Activity.

### Estratégia contra o Doze (CPU dormindo)

Um `delay` de coroutine não acorda a CPU. O app usa três mecanismos:

1. **Alarme exato a cada 5 min:** `setExactAndAllowWhileIdle` com `USE_EXACT_ALARM`, que é concedida na instalação para apps instalados fora da loja. Cada alarme segura um wakelock de até 60 s para ler 10 s de pressão (mediana), avaliar a tendência e o alerta de chuva, rodar um tick do Sol e, se preciso, medir a pele.
2. **Sol:** o sensor de luz é registrado com batching de 20 s (variante *wake-up*, se existir). Enquanto há luz forte com o relógio no pulso, o serviço segura um wakelock parcial, renovado a cada tick com timeout de 10 min, para os ticks de 30 s acontecerem. Fora disso, a CPU dorme e nada é contado, pois não há luz forte para contar.
   - **Custo:** gasto extra de bateria só durante o tempo real sob sol forte.
   - **Limitação:** depois de um período escuro com a CPU dormindo, a contagem pode começar com até alguns minutos de atraso quando a luz forte chega (até o próximo evento do sensor ou alarme).
3. **Subida e corrida:** wakelock durante a sessão, renovado a cada 10 s, com timeout de segurança de 30 min.

### Contagem do Sol

O `TYPE_LIGHT` é um sensor *on-change*: sob sol constante ele fica minutos sem enviar evento. Por isso:

- o app guarda o último lux recebido e um tick de 30 s soma o intervalo real desde o tick anterior;
- intervalos acima de 90 s são descartados, porque indicam serviço parado ou CPU dormindo;
- com o relógio fora do pulso (`TYPE_LOW_LATENCY_OFFBODY_DETECT`), o tempo não conta;
- na virada da meia-noite, o intervalo é dividido entre os dois dias.

### Permissões

| Permissão | Quando é pedida |
|---|---|
| `POST_NOTIFICATIONS` | Na primeira abertura; o serviço só inicia com ela |
| `RECORD_AUDIO` | Ao abrir a tela Som |
| `health.READ_HEART_RATE` (Android 16+) ou `BODY_SENSORS` (até 15), mais `READ_ADDITIONAL_HEALTH_DATA` da Samsung, se existir no sistema | Ao abrir Pele ou Suor |
| `ACTIVITY_RECOGNITION` | Ao abrir Suor (contador de passos) |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_HEALTH`, `HIGH_SAMPLING_RATE_SENSORS`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `USE_EXACT_ALARM`, `VIBRATE` | Concedidas na instalação |

O app só pede as permissões que existem no sistema do relógio. Se o SDK da Samsung recusar uma leitura por falta de permissão, a tela mostra o erro.

## Calibração

- **Altitude:**
  - em Ajustes › Calibrar por altitude conhecida, informe a altitude de onde você está (placa de trilha, mapa topográfico). O app lê a pressão atual e calcula o p0;
  - outra opção: em Ajustes › Pressão ao nível do mar, informe o valor de uma previsão do tempo (o "QNH" de um aeroporto próximo).
- **Decibelímetro:** com um app medidor no celular ao lado do relógio, ajuste Ajustes › Offset do decibelímetro até os dois valores baterem. O padrão de 90 dB é só um ponto de partida.

## Diferenças em relação à especificação

1. **Perda de suor sem Health Services.** A especificação previa usar o `ExerciseClient` do Health Services, se o code lab exigisse. A API do SDK 1.4.1 pede só a cadência (`DataType.STEP_PER_MINUTE` via `setExerciseData`), então o app calcula os passos por minuto com o `TYPE_STEP_COUNTER` (janela de 60 s, enviada a cada 10 s). O code lab não pôde ser consultado: o site da Samsung estava inacessível no ambiente de desenvolvimento, e os nomes de API vieram das assinaturas do próprio `.aar`.
2. **Unidade e códigos do suor.** O valor de `SweatLossSet.SWEAT_LOSS` é tratado como mililitros e convertido para litros. O sexo no perfil usa 1 = masculino e 0 = feminino. `STATUS` igual a 0 é tratado como sucesso, nos dois trackers. **Confira na primeira medição real**: se o resultado vier mil vezes menor ou maior, a unidade é outra (o ajuste fica em `SamsungHealth.kt`).
3. **Pele também em segundo plano.** Quando o relógio não oferece leitura contínua, o serviço faz uma medição sob demanda a cada 30 min com o relógio no pulso, para formar a média pessoal sem depender de toques. O botão "Medir agora" continua na tela.
4. **Ajustes fora do pager.** A página 9 abre a lista de ajustes numa tela própria, porque no pager a coroa troca de página e numa lista longa ela precisa rolar a lista.

## Limitações conhecidas

- O sensor de luz mede luz visível, não UV. Dia nublado e manga cobrindo o relógio alteram a contagem do Sol.
- A tendência de chuva é uma regra prática, não uma previsão.
- O decibelímetro não é um SPL certificado.
- A temperatura da pele não é a temperatura corporal.
- O app não foi executado num relógio físico durante o desenvolvimento, porque o ambiente não tinha emulador nem relógio. A lógica tem testes unitários e as telas foram renderizadas em testes; o comportamento dos sensores e do SDK da Samsung só se confirma no relógio.

## Estrutura

```
app/src/main/java/net/extrasensors/watch/
  domain/        algoritmos puros (testados): tendência, subida, sol, som, pele, formatação
  data/          Room (históricos) e DataStore (configurações e estado)
  sensor/        sensores Android, microfone e Samsung Health Sensor SDK
  service/       SensorService, notificações, alarme, boot, ação "Apliquei protetor"
  complication/  complicação "Sol"
  ui/            MainActivity, pager, telas, ajustes e componentes
app/src/test/    testes unitários e renderização das telas
```
