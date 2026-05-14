# FlashPrinter 📄🖨️

Aplicativo Android desenvolvido em Kotlin para impressão automática de PDFs em impressoras térmicas Bluetooth utilizando protocolo TSPL.

O projeto foi criado com foco em automação operacional e comunicação direta com impressoras térmicas via Bluetooth Classic (SPP), realizando todo o fluxo de:

- recebimento de PDF
- renderização
- conversão monocromática
- geração TSPL
- envio para impressão

---

# ✨ Funcionalidades

- ✔️ Recebimento de PDFs via compartilhamento Android
- ✔️ Impressão automática após abertura do arquivo
- ✔️ Conexão Bluetooth Classic (SPP)
- ✔️ Descoberta de dispositivos Bluetooth
- ✔️ Pareamento automático
- ✔️ Reconexão com último dispositivo utilizado
- ✔️ Renderização de PDF usando `PdfRenderer`
- ✔️ Conversão bitmap → TSPL
- ✔️ Ajuste proporcional da etiqueta
- ✔️ Conversão monocromática otimizada para impressão térmica
- ✔️ Impressão em lote de múltiplas páginas
- ✔️ Compatibilidade com Android 12+

---

# 🛠️ Tecnologias Utilizadas

- Kotlin
- Android SDK
- Bluetooth RFCOMM / SPP
- TSPL / TSPL2
- PdfRenderer API
- Android Intents
- SharedPreferences

---

# 📱 Compatibilidade

- Android 8+
- Impressoras térmicas Bluetooth TSPL
- Impressoras de etiquetas 203 DPI

---

# 🚀 Objetivo do Projeto

O objetivo do aplicativo é automatizar o processo de impressão térmica de PDFs diretamente pelo Android, eliminando etapas manuais e reduzindo tempo operacional.

O app foi projetado para:
- rapidez
- estabilidade de conexão
- baixo atrito operacional
- impressão direta sem intermediários

---

# 🔥 Fluxo da Aplicação

```text
PDF recebido
    ↓
Renderização das páginas
    ↓
Conversão para bitmap
    ↓
Conversão para TSPL
    ↓
Conexão Bluetooth
    ↓
Envio para impressora
```

---

# 📂 Estrutura Principal

```text
MainActivity.kt
│
├── Bluetooth
│   ├── Permissões
│   ├── Descoberta
│   ├── Pareamento
│   └── Conexão
│
├── PDF
│   ├── Recebimento via Intent
│   ├── Renderização
│   └── Redimensionamento
│
├── Impressão
│   ├── Conversão TSPL
│   ├── Bitmap monocromático
│   └── Envio Bluetooth
│
└── Persistência
    └── SharedPreferences
```

---

# ⚙️ Permissões Necessárias

## Android 12+

```xml
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"/>
```

## Android 11 ou inferior

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
```

---

# 🖨️ Comunicação com Impressora

A comunicação utiliza Bluetooth RFCOMM através do UUID padrão SPP:

```kotlin
00001101-0000-1000-8000-00805F9B34FB
```

Os comandos enviados seguem o padrão TSPL:

```text
SIZE 80 mm,80 mm
GAP 4 mm,0 mm
CLS
BITMAP x,y,width,height,0,
PRINT 1
```

---

# 🧠 Processamento de Imagem

O aplicativo converte páginas PDF em imagens monocromáticas utilizando cálculo de luminância:

```kotlin
val luminancia = ((r * 30) + (g * 59) + (b * 11)) / 100
```

Isso melhora:
- definição térmica
- contraste
- legibilidade da impressão

---

# 📌 Recursos Técnicos

## Renderização PDF

```kotlin
PdfRenderer
```

---

## Escala de renderização

```kotlin
ESCALA_RENDER_PDF = 2
```

---

## DPI utilizado

```kotlin
DPI_IMPRESSORA = 203
```

---

# 💡 Desafios Técnicos Resolvidos

- Comunicação Bluetooth estável
- Reconexão automática
- Compatibilidade Android 12+
- Conversão eficiente PDF → TSPL
- Rotação e ajuste de página
- Processamento bitmap em memória
- Impressão térmica monocromática
- Impressão de múltiplas páginas em lote

---

# 📈 Possíveis Melhorias Futuras

- [ ] Arquitetura MVVM
- [ ] Preview da etiqueta
- [ ] Compressão de imagem
- [ ] Histórico de impressão
- [ ] Fila de impressão
- [ ] Suporte ESC/POS
- [ ] Seleção manual de páginas
- [ ] Perfis múltiplos de impressora

---

# 🧪 Possíveis Aplicações

- Logística
- Expedição
- Etiquetas de envio
- Controle de estoque
- Impressão operacional
- Ambientes industriais
- Automação de processos

---

# 📚 Sobre o Projeto

Este projeto foi desenvolvido como estudo e solução prática para automação de impressão térmica em Android, explorando integração direta com hardware Bluetooth e protocolos de impressão.

Nenhuma informação corporativa, integração proprietária ou dado interno está presente no código disponibilizado.

---

# 👨‍💻 Autor

Desenvolvido por Murilo.

---

# 📄 Licença

Este projeto pode ser utilizado para fins educacionais e demonstração técnica de portfólio.

---

# 🔧 Observações Técnicas

O aplicativo:
- utiliza Bluetooth Classic
- trabalha com sockets RFCOMM
- envia o pacote completo antes da impressão
- evita múltiplos envios fragmentados
- realiza processamento completo localmente no dispositivo Android

Tudo visando maior estabilidade na impressão térmica Bluetooth.
