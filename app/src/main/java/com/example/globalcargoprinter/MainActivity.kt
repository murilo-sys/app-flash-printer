package com.murilo.flashprinter

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_BLUETOOTH_PERMISSIONS = 1
        const val DPI_IMPRESSORA = 203
        const val ESCALA_RENDER_PDF = 2

        const val PREFS_NAME = "printer_prefs"
        const val PREF_DEVICE_ADDRESS = "device_address"

        const val LIMIAR_PRETO = 170
        const val PINTAR_VIZINHO_DIREITA = true

        const val MARGEM_SEGURANCA_PX = 28

        val UUID_SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var pdfUriMain: Uri? = null
    private var socketBluetooth: BluetoothSocket? = null
    private var imprimindoAgora = false
    private var dispositivoSelecionado: BluetoothDevice? = null
    private var impressaoPendente = false
    private var veioDeIntentPdf = false

    private var receiverRegistrado = false
    private val dispositivosNovosMap = linkedMapOf<String, BluetoothDevice>()

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                onBluetoothDisponivel()
            } else {
                atualizarStatusBluetoothUI("NEGADO")
                atualizarStatus("Bluetooth não foi ativado")
            }
        }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        registrarReceiverBluetooth()
        exibirDadosPadrao()
        tratarIntentPdf(intent)
        garantirPermissaoBluetooth()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tratarIntentPdf(intent)
        garantirPermissaoBluetooth()
    }

    override fun onDestroy() {
        super.onDestroy()
        pararDescobertaBluetooth()
        desregistrarReceiverBluetooth()
        fecharSocketAtual()
    }

    fun atualizarTv(status: String, bluetooth: String) {
        val tvBluetooth = findViewById<TextView>(R.id.bluetooth)
        val tvStatus = findViewById<TextView>(R.id.status)

        tvStatus.text = getString(R.string.status, status)
        tvBluetooth.text = getString(R.string.status_bluetooth, bluetooth)
    }

    fun atualizarStatus(status: String) {
        val tvStatus = findViewById<TextView>(R.id.status)
        tvStatus.text = getString(R.string.status, status)
    }

    fun atualizarStatusBluetoothUI(bluetooth: String) {
        val tvBluetooth = findViewById<TextView>(R.id.bluetooth)
        tvBluetooth.text = getString(R.string.status_bluetooth, bluetooth)
    }

    fun exibirDadosPadrao() {
        val na = getString(R.string.n_a)
        atualizarTv(na, na)
    }

    fun tratarIntentPdf(intent: Intent?) {
        if (intent == null) return

        when {
            intent.action == Intent.ACTION_VIEW && intent.type == "application/pdf" -> {
                val pdfUri = intent.data
                if (pdfUri != null) {
                    pdfUriMain = pdfUri
                    impressaoPendente = true
                    veioDeIntentPdf = true
                    atualizarStatus("PDF recebido, preparando impressão")
                }
            }

            intent.action == Intent.ACTION_SEND && intent.type == "application/pdf" -> {
                val pdfUri =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }

                if (pdfUri != null) {
                    pdfUriMain = pdfUri
                    impressaoPendente = true
                    veioDeIntentPdf = true
                    atualizarStatus("PDF recebido, preparando impressão")
                }
            }

            else -> {
                veioDeIntentPdf = false
                if (!impressaoPendente) {
                    exibirDadosPadrao()
                }
            }
        }
    }

    fun garantirPermissaoBluetooth() {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            atualizarStatusBluetoothUI("NÃO ENCONTRADO")
            atualizarStatus("Bluetooth não encontrado")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val semConnect =
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED

            val semScan =
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED

            if (semConnect || semScan) {
                requestPermissions(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                    ),
                    REQUEST_BLUETOOTH_PERMISSIONS
                )
                return
            }
        } else {
            val semLocation =
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED

            if (semLocation) {
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    REQUEST_BLUETOOTH_PERMISSIONS
                )
                return
            }
        }

        verificarEstadoBluetooth(bluetoothAdapter)
    }

    fun verificarEstadoBluetooth(bluetoothAdapter: BluetoothAdapter) {
        if (bluetoothAdapter.isEnabled) {
            onBluetoothDisponivel()
        } else {
            solicitarAtivacaoBluetooth()
        }
    }

    fun solicitarAtivacaoBluetooth() {
        val bluetoothManager = getSystemService(BluetoothManager::class.java) ?: return
        val bluetoothAdapter = bluetoothManager.adapter ?: return

        if (!bluetoothAdapter.isEnabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    fun onBluetoothDisponivel() {
        atualizarStatusBluetoothUI("PERMITIDO")
        listarDispositivosBluetooth()
        listarDispositivosBluetoothNovos()

        if (impressaoPendente) {
            garantirConexaoParaImpressao()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            val permissoesConcedidas = grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (permissoesConcedidas) {
                garantirPermissaoBluetooth()
            } else {
                atualizarStatusBluetoothUI("NEGADO")
                atualizarStatus("Permissões Bluetooth negadas")
            }
        }
    }

    fun listarDispositivosBluetooth() {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter = bluetoothManager?.adapter ?: return
        val lyDevices = findViewById<LinearLayout>(R.id.lyDevices)

        if (!bluetoothAdapter.isEnabled) {
            atualizarStatus("Bluetooth desligado ao listar dispositivos")
            return
        }

        if (!temPermissaoConnect()) {
            atualizarStatus("Sem permissão para listar dispositivos Bluetooth")
            return
        }

        try {
            val dispositivosPareados = bluetoothAdapter.bondedDevices
            lyDevices.removeAllViews()

            if (dispositivosPareados.isEmpty()) {
                atualizarStatus("Nenhum dispositivo pareado encontrado")
            }

            for (device in dispositivosPareados) {
                val nomeDevice = try {
                    device.name ?: "Desconhecido"
                } catch (e: Exception) {
                    "Desconhecido"
                }

                val addressDevice = try {
                    device.address
                } catch (e: Exception) {
                    "Endereço indisponível"
                }

                val salvo = obterEnderecoDispositivoSalvo()

                val itemLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.setMargins(0, 5, 0, 5) }
                    setPadding(0, 10, 0, 10)
                    setOnClickListener {
                        dispositivoSelecionado = device
                        salvarEnderecoDispositivo(device.address)
                        conectarDispositivo(device, imprimirAoConectar = false)
                    }
                }

                val tvNome = TextView(this).apply {
                    text = if (addressDevice == salvo) "$nomeDevice (Ultimo conectado)" else nomeDevice
                    setTextColor(Color.BLACK)
                    gravity = Gravity.CENTER
                }

                val tvAddress = TextView(this).apply {
                    text = addressDevice
                    setTextColor(Color.GRAY)
                    textSize = 12f
                    gravity = Gravity.CENTER
                }

                itemLayout.addView(tvNome)
                itemLayout.addView(tvAddress)
                lyDevices.addView(itemLayout)
            }
        } catch (e: Exception) {
            val erro = e.message ?: "Erro desconhecido ao listar dispositivos"
            atualizarStatus("Erro ao listar dispositivos: $erro")
            Log.e("BT_LISTAGEM", "Erro ao listar dispositivos Bluetooth", e)
        }
    }

    fun listarDispositivosBluetoothNovos() {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter = bluetoothManager?.adapter ?: return
        val lyDevices = findViewById<LinearLayout>(R.id.lyNewDevices)

        if (!bluetoothAdapter.isEnabled) {
            atualizarStatus("Bluetooth desligado ao listar dispositivos")
            return
        }

        if (!temPermissaoScan()) {
            atualizarStatus("Sem permissão para buscar novos dispositivos Bluetooth")
            return
        }

        try {
            dispositivosNovosMap.clear()
            lyDevices.removeAllViews()

            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }

            val iniciouBusca = bluetoothAdapter.startDiscovery()

            if (iniciouBusca) {
                atualizarStatus("Buscando novos dispositivos...")
            } else {
                atualizarStatus("Não foi possível iniciar a busca de dispositivos")
            }
        } catch (e: Exception) {
            val erro = e.message ?: "Erro desconhecido ao listar dispositivos novos"
            atualizarStatus("Erro ao listar dispositivos novos: $erro")
            Log.e("BT_LISTAGEM", "Erro ao listar dispositivos novos Bluetooth", e)
        }
    }

    fun adicionarNovoDispositivoNaTela(device: BluetoothDevice) {
        val lyNewDevices = findViewById<LinearLayout>(R.id.lyNewDevices)

        if (dispositivosNovosMap.containsKey(device.address)) return
        dispositivosNovosMap[device.address] = device

        val nomeDevice = try {
            device.name ?: return
        } catch (e: Exception) {
            "Desconhecido"
        }

        val addressDevice = try {
            device.address
        } catch (e: Exception) {
            "Endereço indisponível"
        }

        val itemLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 5, 0, 5) }
            setPadding(0, 10, 0, 10)
            setOnClickListener {
                onNovoDispositivoSelecionado(device)
            }
        }

        val tvNome = TextView(this).apply {
            text = nomeDevice
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }

        val tvAddress = TextView(this).apply {
            text = addressDevice
            setTextColor(Color.GRAY)
            textSize = 12f
            gravity = Gravity.CENTER
        }

        itemLayout.addView(tvNome)
        itemLayout.addView(tvAddress)
        lyNewDevices.addView(itemLayout)
    }

    fun onNovoDispositivoSelecionado(device: BluetoothDevice) {
        dispositivoSelecionado = device
        salvarEnderecoDispositivo(device.address)

        if (!temPermissaoConnect()) {
            atualizarStatus("Sem permissão para parear/conectar dispositivo")
            return
        }

        try {
            pararDescobertaBluetooth()

            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> {
                    conectarDispositivo(device, imprimirAoConectar = false)
                }

                BluetoothDevice.BOND_NONE -> {
                    val iniciouPareamento = device.createBond()
                    if (iniciouPareamento) {
                        atualizarStatus("Pareando com ${device.name ?: "Desconhecido"}")
                    } else {
                        atualizarStatus("Não foi possível iniciar o pareamento")
                    }
                }

                BluetoothDevice.BOND_BONDING -> {
                    atualizarStatus("Pareamento em andamento com ${device.name ?: "Desconhecido"}")
                }
            }
        } catch (e: Exception) {
            val erro = e.message ?: "Erro desconhecido"
            atualizarStatus("Erro ao selecionar novo dispositivo: $erro")
            Log.e("BT_NOVO_DEVICE", "Erro ao selecionar novo dispositivo", e)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    runOnUiThread {
                        atualizarStatus("Buscando novos dispositivos...")
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    runOnUiThread {
                        if (dispositivosNovosMap.isEmpty()) {
                            atualizarStatus("Busca finalizada. Nenhum novo dispositivo encontrado")
                        } else {
                            atualizarStatus("Busca finalizada")
                        }
                    }
                }

                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }

                    if (device == null) return

                    if (temPermissaoConnect() && device.bondState == BluetoothDevice.BOND_BONDED) {
                        return
                    }

                    runOnUiThread {
                        adicionarNovoDispositivoNaTela(device)
                    }

                    Log.d("BT", "Dispositivo encontrado: ${device.name} - ${device.address}")
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }

                    val novoEstado = intent.getIntExtra(
                        BluetoothDevice.EXTRA_BOND_STATE,
                        BluetoothDevice.ERROR
                    )

                    val estadoAnterior = intent.getIntExtra(
                        BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                        BluetoothDevice.ERROR
                    )

                    if (device == null) return

                    when (novoEstado) {
                        BluetoothDevice.BOND_BONDED -> {
                            runOnUiThread {
                                atualizarStatus("Pareado com ${device.name ?: "Desconhecido"}")
                                listarDispositivosBluetooth()
                                conectarDispositivo(device, imprimirAoConectar = false)
                            }
                        }

                        BluetoothDevice.BOND_BONDING -> {
                            runOnUiThread {
                                atualizarStatus("Pareamento em andamento com ${device.name ?: "Desconhecido"}")
                            }
                        }

                        BluetoothDevice.BOND_NONE -> {
                            if (estadoAnterior == BluetoothDevice.BOND_BONDING) {
                                runOnUiThread {
                                    atualizarStatus("Pareamento não concluído com ${device.name ?: "Desconhecido"}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun registrarReceiverBluetooth() {
        if (receiverRegistrado) return

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }

        registerReceiver(receiver, filter)
        receiverRegistrado = true
    }

    fun desregistrarReceiverBluetooth() {
        if (!receiverRegistrado) return

        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        } finally {
            receiverRegistrado = false
        }
    }

    fun pararDescobertaBluetooth() {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter = bluetoothManager?.adapter ?: return

        if (!temPermissaoScan()) return

        try {
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
        } catch (e: Exception) {
            Log.e("BT_DISCOVERY", "Erro ao cancelar descoberta", e)
        }
    }

    fun temPermissaoConnect(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun temPermissaoScan(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun salvarEnderecoDispositivo(address: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(PREF_DEVICE_ADDRESS, address)
            .apply()
    }

    fun obterEnderecoDispositivoSalvo(): String? {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_DEVICE_ADDRESS, null)
    }

    fun buscarDispositivoSalvo(): BluetoothDevice? {
        return try {
            val address = obterEnderecoDispositivoSalvo() ?: return null

            val bluetoothManager = getSystemService(BluetoothManager::class.java)
            val bluetoothAdapter = bluetoothManager?.adapter ?: return null

            if (!bluetoothAdapter.isEnabled) {
                atualizarStatus("Bluetooth desligado ao buscar dispositivo salvo")
                return null
            }

            if (!temPermissaoConnect()) {
                atualizarStatus("Sem permissão para buscar dispositivo salvo")
                return null
            }

            bluetoothAdapter.bondedDevices.firstOrNull { it.address == address }
        } catch (e: Exception) {
            val erro = e.message ?: "Erro desconhecido ao buscar dispositivo salvo"
            atualizarStatus("Erro ao buscar dispositivo salvo: $erro")
            Log.e("BT_BUSCA_SALVO", "Erro ao buscar dispositivo salvo", e)
            null
        }
    }

    fun estaConectado(): Boolean {
        return try {
            socketBluetooth?.isConnected == true
        } catch (e: Exception) {
            false
        }
    }

    fun garantirConexaoParaImpressao() {
        val deviceSalvo = buscarDispositivoSalvo()

        if (deviceSalvo == null) {
            atualizarStatus("Nenhum dispositivo salvo para impressão")
            impressaoPendente = false
            return
        }

        if (estaConectado()) {
            val addressAtual = socketBluetooth?.remoteDevice?.address
            if (addressAtual == deviceSalvo.address) {
                imprimir()
                return
            } else {
                fecharSocketAtual()
            }
        }

        conectarDispositivo(deviceSalvo, imprimirAoConectar = true)
    }

    fun fecharSocketAtual() {
        try {
            socketBluetooth?.close()
        } catch (e: IOException) {
            val erro = e.message ?: "Erro desconhecido ao fechar socket"
            atualizarStatus("Erro ao fechar conexão Bluetooth: $erro")
            Log.e("BT_CONEXAO", "Erro ao fechar socket", e)
        } finally {
            socketBluetooth = null
        }
    }

    fun conectarDispositivo(device: BluetoothDevice, imprimirAoConectar: Boolean) {
        if (!temPermissaoConnect()) {
            atualizarStatus("Sem permissão para conectar no Bluetooth")
            return
        }

        if (!temPermissaoScan()) {
            atualizarStatus("Sem permissão para cancelar descoberta Bluetooth")
            return
        }

        if (estaConectado()) {
            val addressAtual = socketBluetooth?.remoteDevice?.address
            if (addressAtual == device.address) {
                atualizarStatus("Conectado em ${device.name ?: "Desconhecido"}")
                if (imprimirAoConectar && impressaoPendente) {
                    imprimir()
                }
                return
            } else {
                fecharSocketAtual()
            }
        }

        Thread {
            try {
                val bluetoothManager = getSystemService(BluetoothManager::class.java)
                bluetoothManager?.adapter?.cancelDiscovery()

                runOnUiThread {
                    atualizarStatus("Conectando em ${device.name ?: "Desconhecido"}")
                }

                val socket = device.createRfcommSocketToServiceRecord(UUID_SPP)
                socket.connect()
                socketBluetooth = socket
                dispositivoSelecionado = device

                runOnUiThread {
                    atualizarStatus("Conectado em ${device.name ?: "Desconhecido"}")
                    listarDispositivosBluetooth()
                }

                if (imprimirAoConectar && impressaoPendente) {
                    runOnUiThread {
                        imprimir()
                    }
                }
            } catch (e: Exception) {
                fecharSocketAtual()

                val erro = e.message ?: "Erro desconhecido"

                runOnUiThread {
                    atualizarStatus("Erro ao conectar em ${device.name ?: "Desconhecido"}: $erro")
                }

                Log.e("BT_CONEXAO", "Conexão não sucedida no dispositivo ${device.name}", e)
            }
        }.start()
    }

    fun imprimir() {
        if (imprimindoAgora) {
            atualizarStatus("Impressão já está em andamento")
            return
        }

        val uri = pdfUriMain
        if (uri == null) {
            atualizarStatus("PDF não encontrado")
            impressaoPendente = false
            return
        }

        if (!estaConectado()) {
            atualizarStatus("Dispositivo não conectado")
            garantirConexaoParaImpressao()
            return
        }

        imprimindoAgora = true

        Thread {
            try {
                runOnUiThread {
                    atualizarStatus("Montando impressão completa")
                }

                val pacoteCompleto = montarPacoteCompletoPdf(uri)
                if (pacoteCompleto == null) {
                    runOnUiThread {
                        atualizarStatus("Erro ao montar impressão")
                    }
                    return@Thread
                }

                if (!estaConectado()) {
                    throw IllegalStateException("Bluetooth desconectado antes do envio")
                }

                runOnUiThread {
                    atualizarStatus("Enviando tudo de uma vez")
                }

                enviarPacoteCompleto(pacoteCompleto)

                runOnUiThread {
                    atualizarStatus("Impressão enviada")
                    impressaoPendente = false

                    if (veioDeIntentPdf) {
                        finish()
                    }
                }
            } catch (e: Exception) {
                val erro = e.message ?: "Erro desconhecido"

                Log.e("BT_IMPRESSAO", "Erro ao imprimir", e)

                runOnUiThread {
                    atualizarStatus("Erro ao imprimir: $erro")
                }
            } finally {
                imprimindoAgora = false
            }
        }.start()
    }

    fun montarPacoteCompletoPdf(pdfUri: Uri): ByteArray? {
        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null

        return try {
            descriptor = contentResolver.openFileDescriptor(pdfUri, "r") ?: run {
                runOnUiThread {
                    atualizarStatus("Erro ao abrir PDF para leitura")
                }
                return null
            }

            renderer = PdfRenderer(descriptor)

            val totalPaginas = renderer.pageCount
            if (totalPaginas <= 0) {
                runOnUiThread {
                    atualizarStatus("PDF sem páginas para imprimir")
                }
                return null
            }

            val pacote = ByteArrayOutputStream(1024 * 256)

            for (indicePagina in 0 until totalPaginas) {
                if (!estaConectado()) {
                    throw IllegalStateException("Bluetooth desconectado durante a montagem")
                }

                runOnUiThread {
                    atualizarStatus("Renderizando página ${indicePagina + 1} de $totalPaginas")
                }

                val bitmapOriginal = renderizarPaginaPdf(renderer, indicePagina)
                    ?: throw IllegalStateException("Erro ao renderizar página ${indicePagina + 1}")

                val tamanhoEtiqueta =
                    descobrirTamanhoEtiqueta(bitmapOriginal.width, bitmapOriginal.height)

                val larguraUtil = (tamanhoEtiqueta.larguraPx - (MARGEM_SEGURANCA_PX * 2)).coerceAtLeast(1)
                val alturaUtil = (tamanhoEtiqueta.alturaPx - (MARGEM_SEGURANCA_PX * 2)).coerceAtLeast(1)

                val bitmapAjustado = redimensionarProporcional(
                    bitmapOriginal,
                    larguraUtil,
                    alturaUtil
                )

                if (bitmapAjustado != bitmapOriginal) {
                    bitmapOriginal.recycle()
                }

                val bytesPagina = montarComandoPagina(bitmapAjustado, tamanhoEtiqueta)
                pacote.write(bytesPagina)

                bitmapAjustado.recycle()
            }

            pacote.toByteArray()
        } catch (e: Exception) {
            val erro = e.message ?: "Erro desconhecido"

            Log.e("PDF_RENDER", "Erro ao montar pacote completo do PDF", e)

            runOnUiThread {
                atualizarStatus("Erro ao processar PDF: $erro")
            }

            null
        } finally {
            try {
                renderer?.close()
            } catch (e: Exception) {
                val erro = e.message ?: "Erro ao fechar renderer"
                Log.e("PDF_RENDER", "Erro ao fechar renderer", e)
                runOnUiThread {
                    atualizarStatus("Erro ao finalizar renderer: $erro")
                }
            }

            try {
                descriptor?.close()
            } catch (e: Exception) {
                val erro = e.message ?: "Erro ao fechar descriptor"
                Log.e("PDF_RENDER", "Erro ao fechar descriptor", e)
                runOnUiThread {
                    atualizarStatus("Erro ao fechar arquivo PDF: $erro")
                }
            }
        }
    }

    data class TamanhoEtiqueta(
        val larguraMm: Int,
        val alturaMm: Int,
        val larguraPx: Int,
        val alturaPx: Int
    )

    fun mmParaPx(mm: Int): Int {
        return ((mm / 25.4f) * DPI_IMPRESSORA).toInt()
    }

    fun descobrirTamanhoEtiqueta(larguraPdf: Int, alturaPdf: Int): TamanhoEtiqueta {
        val proporcao = larguraPdf.toFloat() / alturaPdf.toFloat()

        return if (proporcao in 0.95f..1.05f) {
            TamanhoEtiqueta(
                larguraMm = 80,
                alturaMm = 80,
                larguraPx = mmParaPx(80),
                alturaPx = mmParaPx(80)
            )
        } else {
            TamanhoEtiqueta(
                larguraMm = 100,
                alturaMm = 75,
                larguraPx = mmParaPx(80),
                alturaPx = mmParaPx(80)
            )
        }
    }

    fun redimensionarProporcional(bitmap: Bitmap, maxLargura: Int, maxAltura: Int): Bitmap {
        val larguraOriginal = bitmap.width
        val alturaOriginal = bitmap.height

        val escala = minOf(
            maxLargura.toFloat() / larguraOriginal,
            maxAltura.toFloat() / alturaOriginal
        )

        val novaLargura = (larguraOriginal * escala).toInt().coerceAtLeast(1)
        val novaAltura = (alturaOriginal * escala).toInt().coerceAtLeast(1)

        if (novaLargura == larguraOriginal && novaAltura == alturaOriginal) {
            return bitmap
        }

        return Bitmap.createScaledBitmap(bitmap, novaLargura, novaAltura, true)
    }

    fun montarComandoPagina(bitmap: Bitmap, tamanhoEtiqueta: TamanhoEtiqueta): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val widthBytes = (width + 7) / 8

        val imageBytes = bitmapParaBytesTSPL(bitmap)

        val x = ((tamanhoEtiqueta.larguraPx - width) / 2).coerceAtLeast(0)
        val y = ((tamanhoEtiqueta.alturaPx - height) / 2).coerceAtLeast(0)

        val comandoInicio = """
SIZE ${tamanhoEtiqueta.larguraMm} mm,${tamanhoEtiqueta.alturaMm} mm
GAP 4 mm,0 mm
CLS
BITMAP $x,$y,$widthBytes,$height,0,
""".trimIndent()

        val pagina = ByteArrayOutputStream(comandoInicio.length + imageBytes.size + 16)
        pagina.write(comandoInicio.toByteArray(Charsets.US_ASCII))
        pagina.write(imageBytes)
        pagina.write("\nPRINT 1\n".toByteArray(Charsets.US_ASCII))

        return pagina.toByteArray()
    }

    fun bitmapParaBytesTSPL(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val widthBytes = (width + 7) / 8
        val imageBytes = ByteArray(widthBytes * height)

        val pixels = IntArray(width)

        for (y in 0 until height) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)

            for (x in 0 until width) {
                val pixel = pixels[x]

                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                val luminancia = ((r * 30) + (g * 59) + (b * 11)) / 100
                val pixelEhBranco = luminancia >= LIMIAR_PRETO

                if (pixelEhBranco) {
                    marcarPixelBranco(imageBytes, widthBytes, x, y)

                    if (PINTAR_VIZINHO_DIREITA && x + 1 < width) {
                        marcarPixelBranco(imageBytes, widthBytes, x + 1, y)
                    }
                }
            }
        }

        return imageBytes
    }

    fun marcarPixelBranco(imageBytes: ByteArray, widthBytes: Int, x: Int, y: Int) {
        val byteIndex = y * widthBytes + (x / 8)
        imageBytes[byteIndex] =
            (imageBytes[byteIndex].toInt() or (0x80 shr (x % 8))).toByte()
    }

    fun enviarPacoteCompleto(bytes: ByteArray) {
        val output = socketBluetooth?.outputStream
            ?: throw IllegalStateException("Socket Bluetooth nulo")

        output.write(bytes)
        output.flush()
    }

    fun renderizarPaginaPdf(renderer: PdfRenderer, indicePagina: Int): Bitmap? {
        var page: PdfRenderer.Page? = null

        return try {
            page = renderer.openPage(indicePagina)

            val larguraOriginal = page.width * ESCALA_RENDER_PDF
            val alturaOriginal = page.height * ESCALA_RENDER_PDF

            Log.d("BT", "LARGURA $larguraOriginal ALTURA $alturaOriginal")

            val bitmap = Bitmap.createBitmap(
                alturaOriginal,
                larguraOriginal,
                Bitmap.Config.ARGB_8888
            )

            bitmap.eraseColor(Color.WHITE)

            val matrix = Matrix().apply {
                postScale(
                    ESCALA_RENDER_PDF.toFloat(),
                    ESCALA_RENDER_PDF.toFloat()
                )

                postRotate(270f)

                postTranslate(0f, larguraOriginal.toFloat() - 8f)
            }

            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            bitmap
        } catch (e: Exception) {
            val erro = e.message ?: "Erro desconhecido"

            Log.e("PDF_RENDER", "Erro ao renderizar página $indicePagina", e)

            runOnUiThread {
                atualizarStatus("Erro ao renderizar página ${indicePagina + 1}: $erro")
            }

            null
        } finally {
            try {
                page?.close()
            } catch (e: Exception) {
                val erro = e.message ?: "Erro ao fechar página"
                Log.e("PDF_RENDER", "Erro ao fechar página $indicePagina", e)
                runOnUiThread {
                    atualizarStatus("Erro ao fechar página ${indicePagina + 1}: $erro")
                }
            }
        }
    }
}