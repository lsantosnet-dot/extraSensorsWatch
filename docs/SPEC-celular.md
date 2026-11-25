# Especificação: app do celular e alertas de UV

Etapa 2. Decisões aprovadas na conversa:

- celular Android;
- o app do celular pode acessar a internet só para buscar o índice UV;
- pele que queima fácil e quase não bronzeia (tipo II).

## 1. Escopo

1. Novo app Android em `app-celular/`, no mesmo repositório (módulo Gradle `:app-celular`).
2. Sincronização automática relógio → celular por Bluetooth (Wearable Data Layer), sem servidor.
3. Só dois dados vão para o celular por enquanto: **exposição ao sol** e **temperatura da pele**.
4. O celular busca a previsão horária do **índice UV** (Open-Meteo) e envia ao relógio, que passa a calcular a dose de UV e a alertar com base em recomendações da OMS e da dermatologia.

## 2. Identidade e assinatura

O Data Layer só conecta apps com o **mesmo applicationId e a mesma assinatura**. Os dois apps usam:

- `applicationId = net.extrasensors.watch`;
- a chave do projeto, `keystore/project-debug.jks`.

## 3. Sincronização

### Relógio → celular

- **Dados enviados:**
  - blocos de 5 min de exposição solar (tempo sob luz forte, dose UV);
  - totais diários de sol;
  - aplicações de protetor;
  - leituras de temperatura da pele.
- **Envio:** um DataItem `/sync/pending`, com um Asset compactado (gzip), contendo tudo o que mudou desde o último recibo do celular.
- **Recibo:** o celular grava os dados e responde com o DataItem `/ack`, com o maior `updatedAt` ou horário recebido de cada tabela.
- **Entrega garantida:** o Data Layer guarda o item e entrega quando a conexão volta, sem perda se o celular ficar dias desconectado.
- **Frequência:** o relógio envia a cada 15 min se houver mudança, à meia-noite e quando o celular pede (mensagem `/request-sync`, botão "Sincronizar agora").

### Celular → relógio

- **Previsão:** índice UV horário das últimas 24 h e das próximas 48 h, no DataItem `/uv`.
- **Busca no celular:** a cada 1 h (WorkManager) e ao abrir o app. Pede só a localização aproximada, salva com 2 casas decimais (cerca de 1 km) e enviada apenas ao Open-Meteo.
- **Pedido do relógio:** se a previsão no relógio tiver mais de 3 h, ele pede uma atualização ao celular (mensagem `/request-uv`, no máximo 1 por hora).

## 4. Alertas de sol (relógio)

O relógio estima o índice UV do momento por interpolação da previsão horária. A dose é calculada assim:

- 1 unidade de índice UV equivale a 25 mW/m², ou seja, 0,9 SED por hora;
- a dose só é somada quando o relógio detecta sol (luz forte e relógio no pulso).

| Alerta | Regra | Base |
|---|---|---|
| Reaplicar protetor | 2 h de exposição contadas só com UV ≥ 3 (ou UV desconhecido) | AAD/FDA: reaplicar a cada 2 h; OMS: proteção a partir do UV 3 |
| Aplique protetor | Sem protetor registrado hoje e dose do dia ≥ 50% da MED da pele | MED tipo II ≈ 2,5 SED (faixa 2,1–3,5) |
| UV muito alto | Exposto com UV ≥ 8; no máximo 1 aviso a cada 2 h | OMS: evitar o sol do meio do dia com UV 8+ |

- **Tipo de pele:** configurável nos Ajustes do relógio, de I a VI. Padrão: tipo II.

| Tipo | I | II | III | IV | V | VI |
|---|---|---|---|---|---|---|
| MED (SED) | 2,0 | 2,5 | 3,5 | 4,5 | 6,0 | 10,0 |

- **Sem dados de UV:** se o relógio não tiver previsão, o comportamento é o da etapa 1 (só tempo sob luz forte) e a tela avisa "Sem índice UV".
- **Tela Sol:** passa a mostrar "UV agora" e "Dose hoje".

## 5. App do celular

- **Visual:** Compose com Material 3, tema que segue o sistema (claro/escuro), em pt-BR.
- **Abas:** **Sol** e **Pele**.
- **Filtro de período:** Hoje, 7 dias, 30 dias, 90 dias, 1 ano e Personalizado (seletor de intervalo de datas).
- **Aba Sol:**
  - cartões: tempo ao sol, média por dia, dose UV (SED), aplicações de protetor;
  - gráfico: barras de minutos ao sol (por hora em "Hoje", por dia nos demais) e linha do índice UV (horário em "Hoje", máximo do dia nos demais), com linhas de referência em UV 3 e 8.
- **Aba Pele:**
  - cartões: média, mínima e máxima do período;
  - gráfico: leituras em "Hoje" e, nos demais, média diária com faixa de mínimo e máximo, mais uma linha tracejada da média do período.
- **Rodapé:**
  - relógio conectado ou não, última sincronização, última atualização do UV e local usado;
  - botões "Sincronizar agora", "Atualizar UV" e "Exportar CSV".
- **Sem dados:** estado vazio explicando o motivo; nunca valores inventados.

## 6. Fora do escopo desta etapa

- Health Connect.
- Outros sensores no celular.
- Notificações no celular: os alertas continuam no relógio.
