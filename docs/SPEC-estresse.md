# Especificação: HRV, EDA e calibração por GPS

Etapa 3, aprovada na conversa. Relógio 1.6 e celular 1.3.

## 1. Variabilidade da frequência cardíaca (HRV)

- **Fonte:** `HEART_RATE_CONTINUOUS` do Samsung Health Sensor SDK, campo `IBI_LIST` (intervalos entre batimentos, ms) com `IBI_STATUS_LIST` (0 = válido).
- **Medição:** 1 min parado, iniciada na tela **Recuperação** do relógio. O sensor tem até 15 s para estabilizar; a gravação dura 60 s a partir do primeiro intervalo válido.
- **Cálculo:** RMSSD depois de remover artefatos:
  - intervalos fora de 300–2000 ms;
  - intervalos que diferem mais de 20% do anterior aceito.

  Diferenças só entre intervalos aceitos consecutivos. Mínimo de 30 batimentos.
- **Faixa pessoal:** média ± 1 desvio de ln(RMSSD) das medições dos 7 dias anteriores, com no mínimo 3 medições. Classificação: abaixo, na faixa ou acima.
- **Lembrete diário:** notificação no horário escolhido (padrão 7h), ajustável em Ajustes › Recuperação.

## 2. Atividade eletrodérmica (EDA)

- **Fonte:** `EDA_CONTINUOUS`, campo `SKIN_CONDUCTANCE` (µS) com `STATUS` 0. Disponível do Galaxy Watch8 em diante; o app verifica na hora.
- **Permissão:** no Android 16, `READ_ADDITIONAL_HEALTH_DATA` é obrigatória nesta tela.
- **Sessões, não o dia todo:**
  - manual, na tela **Ativação**;
  - automática durante a corrida da tela **Suor**.
- **Gravação:** janelas de 10 s com média, mínimo e máximo, mais os **picos de ativação**: subida de pelo menos 0,05 µS a partir do vale mais recente, contada de novo só depois de recuar 0,05 µS a partir do topo.
- **Guarda no relógio:** 90 dias de EDA e 1 ano de HRV. O celular guarda tudo.

## 3. Calibração da altitude pelo GPS

- **Busca:** GPS do relógio, sem internet, até 2 min, aceitando precisão vertical de até 10 m.
- **Conversão:** a altura do GPS (elipsoide WGS84) vira altitude acima do nível do mar com o `AltitudeConverter` do Android (modelo offline, API 34+).
- **Cálculo:** p0 = pressão atual / (1 − h/44330)^5,255.
- **Quando:**
  - pelo botão em Ajustes › Calibrar pelo GPS;
  - automaticamente ao iniciar uma sessão de Subida (pode ser desligado).
- **Na Subida:** quando o p0 muda no meio da sessão, o acumulador zera a referência, para o salto de altitude não contar como subida.

## 4. Sincronização e celular

- **Formato:** a sincronização vai para a versão 2 (HRV, sessões e janelas de EDA). O celular continua lendo envios da versão 1.
- **Recibo:** o celular confirma o maior horário ou `updatedAt` de cada tabela nova.
- **Nova aba "Estresse":**
  - cartões com última HRV e situação, média do período com FC, número de medições e número de sessões de EDA;
  - gráfico de HRV com a faixa pessoal de cada medição (pontos vazados abaixo da faixa);
  - gráfico da sessão de EDA escolhida, com lista de sessões.
- **Exportar CSV:** inclui `hrv.csv`, `eda_sessoes.csv` e `eda_10s.csv`.
- **Bancos:** relógio na versão 3, celular na versão 2, ambos com migração testada.

## 5. Resumo semanal e guia (celular 1.4)

- **Cartão "Resumo da semana":** cobre os últimos 7 dias, hoje incluído, sem depender do filtro de período. Mostra:
  - HRV: número de medições, média, variação contra a semana anterior e dias abaixo da faixa;
  - EDA: número de sessões e picos por minuto (média ponderada pela duração), comparados com a mediana das sessões dos 30 dias anteriores (exige 3 ou mais sessões).
- **Leituras automáticas** (ver `stats/Weekly.kt`):
  - recuperação baixa: 2 ou mais dias seguidos, ou 3 ou mais dias na semana, abaixo da faixa;
  - tendência: média de RMSSD 5% acima ou abaixo da semana anterior;
  - ativação alta: picos por minuto 50% acima da mediana;
  - estresse acumulado: recuperação baixa e ativação alta juntas;
  - dados insuficientes geram um pedido de mais medições em vez de conclusão.
- **Guia "Como usar e analisar HRV e EDA":** fica recolhido. Explica a regra de comparar com você mesmo, o protocolo de medição, a leitura de cada sinal, a combinação dos dois, como o resumo decide e os limites (não é diagnóstico).
