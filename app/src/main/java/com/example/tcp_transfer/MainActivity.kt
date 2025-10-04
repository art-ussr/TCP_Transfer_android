package com.example.tcp_transfer
import kotlinx.coroutines.isActive

import android.Manifest
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayList
import kotlin.math.max

data class Contact(val name: String, val ip: String, val port: Int)

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivityTCP"

    private var bytesSinceLastUpdate = 0L
    private val speedLock = Any()
    private var speedTimerJob: kotlinx.coroutines.Job? = null
    private val speedIntervalMs = 200L // интервал замера, 200 ms


    private val STORAGE_PERMISSION_CODE = 100
    private val FILE_PICKER_REQUEST_CODE = 101
    private val DEFAULT_PORT = 5084

    private var selectedFileUri: Uri? = null
    private lateinit var sharedPreferences: android.content.SharedPreferences
    private val contacts: MutableList<Contact> = mutableListOf()

    // ------- поля для графика скорости -------
    private lateinit var chartSpeed: LineChart
    private lateinit var speedData: LineData
    private var lastSpeedUpdateMs = 0L
    private var xIndex = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences("AddressBook", MODE_PRIVATE)
        contacts.addAll(loadContacts())

        // UI элементы
        val btnSelectFile = findViewById<Button>(R.id.btnSelectFile)
        val btnSendFile = findViewById<Button>(R.id.btnSendFile)
        val btnReceiveFile = findViewById<Button>(R.id.btnReceiveFile)
        val btnSaveContact = findViewById<Button>(R.id.btnSaveContact)
        val btnCopyIp = findViewById<Button>(R.id.btnCopyIp)
        val tvDeviceIp = findViewById<TextView>(R.id.tvDeviceIp)
        val etRecipientName = findViewById<EditText>(R.id.etRecipientName)
        val etRecipientIp = findViewById<EditText>(R.id.etRecipientIp)
        val etPort = findViewById<EditText>(R.id.etPort)
        val spinnerContacts = findViewById<Spinner>(R.id.spinnerContacts)

        // Инициализация графика (поле класса)
        chartSpeed = findViewById(R.id.chartSpeed)
        initChart()

        // Показать IP устройства
        val deviceIp = getDeviceIpAddress(this)
        tvDeviceIp.text = "IP устройства: ${deviceIp ?: "Не подключено к Wi-Fi"}"

        btnCopyIp.setOnClickListener {
            deviceIp?.let {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = android.content.ClipData.newPlainText("Device IP", it)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "IP скопирован: $it", Toast.LENGTH_SHORT).show()
            } ?: Toast.makeText(this, "IP недоступен", Toast.LENGTH_SHORT).show()
        }

        // Spinner
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, contacts.map { it.name }.toMutableList())
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerContacts.adapter = adapter

        spinnerContacts.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (contacts.isNotEmpty()) {
                    val selectedContact = contacts[position]
                    etRecipientName.setText(selectedContact.name)
                    etRecipientIp.setText(selectedContact.ip)
                    etPort.setText(selectedContact.port.toString())
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        checkPermission()

        btnSelectFile.setOnClickListener { selectFile() }

        btnSendFile.setOnClickListener {
            val ip = etRecipientIp.text.toString().trim()
            val port = etPort.text.toString().toIntOrNull() ?: DEFAULT_PORT
            if (ip.isEmpty()) {
                Toast.makeText(this, "Введите IP получателя", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            selectedFileUri?.let { uri ->
                // сброс графика перед новой отправкой (опционально)
                runOnUiThread { resetChart() }
                CoroutineScope(Dispatchers.IO).launch { sendFileFromUri(uri, ip, port) }
            } ?: Toast.makeText(this, "Сначала выберите файл", Toast.LENGTH_SHORT).show()
        }

        btnReceiveFile.setOnClickListener {
            val port = etPort.text.toString().toIntOrNull() ?: DEFAULT_PORT
            // сброс графика перед приёмом (опционально)
            runOnUiThread { resetChart() }
            CoroutineScope(Dispatchers.IO).launch { receiveFile(port) }
        }

        btnSaveContact.setOnClickListener {
            val name = etRecipientName.text.toString().trim()
            val ip = etRecipientIp.text.toString().trim()
            val port = etPort.text.toString().toIntOrNull() ?: DEFAULT_PORT
            if (name.isEmpty() || ip.isEmpty()) {
                Toast.makeText(this, "Заполните имя и IP", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val contact = Contact(name, ip, port)
            if (!contacts.any { it.name == contact.name && it.ip == contact.ip && it.port == contact.port }) {
                contacts.add(contact)
                saveContacts(contacts)
                adapter.clear()
                adapter.addAll(contacts.map { it.name })
                adapter.notifyDataSetChanged()
                Toast.makeText(this, "Контакт сохранен", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Контакт уже существует", Toast.LENGTH_SHORT).show()
            }
        }
    }


    private fun startSpeedTimer() {
        if (speedTimerJob?.isActive == true) return
        // Запускаем в Main — так addSpeedPoint можно вызывать напрямую
        speedTimerJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                val bytes: Long = synchronized(speedLock) {
                    val b = bytesSinceLastUpdate
                    bytesSinceLastUpdate = 0L
                    b
                }

                // Переводим в КБ/с за интервал speedIntervalMs
                val seconds = speedIntervalMs.toDouble() / 1000.0
                val speedKbps = (bytes / 1024.0) / seconds
                // Добавляем точку (addSpeedPoint должен быть безопасен для вызова из UI-потока)
                try {
                    addSmoothedSpeedPoint(speedKbps.toFloat())
                } catch (_: Exception) { /* на всякий случай */ }

                // Ждём следующий тик
                kotlinx.coroutines.delay(speedIntervalMs)
            }
        }
    }

    private fun stopSpeedTimer() {
        // Отменяем job; перед завершением можно показать нулевую скорость
        try {
            speedTimerJob?.cancel()
            speedTimerJob = null
        } catch (_: Exception) {}
        synchronized(speedLock) { bytesSinceLastUpdate = 0L }
        // Обновим график нулём — чтобы очевидно показать окончание передачи
        runOnUiThread { addSpeedPoint(0f) }
    }






    // ----- Инициализация и утилиты графика -----
    private fun initChart() {
        val entries = ArrayList<Entry>()
        val dataSet = LineDataSet(entries, "Скорость (КБ/с)").apply {
            lineWidth = 2f
            setDrawCircles(false)
            setDrawValues(false)
            color = android.graphics.Color.WHITE          // линия -> белая
            setCircleColor(android.graphics.Color.WHITE)
            valueTextColor = android.graphics.Color.WHITE
        }
        speedData = LineData(dataSet)

        chartSpeed.data = speedData
        chartSpeed.description.isEnabled = false
        chartSpeed.setTouchEnabled(true)
        chartSpeed.setScaleEnabled(true)
        chartSpeed.setPinchZoom(true)

        // фон графика — совпадает с фоном экрана
        chartSpeed.setBackgroundColor(android.graphics.Color.parseColor("#99b4d1"))

        // оси и легенда белые
        chartSpeed.axisLeft.textColor = android.graphics.Color.WHITE
        chartSpeed.axisRight.isEnabled = false
        chartSpeed.xAxis.textColor = android.graphics.Color.WHITE
        chartSpeed.legend.textColor = android.graphics.Color.WHITE

        // убрать сетку или сделать её полупрозрачной
        chartSpeed.axisLeft.setDrawGridLines(false)
        chartSpeed.xAxis.setDrawGridLines(false)

        chartSpeed.invalidate()
        lastSpeedUpdateMs = 0L
        xIndex = 0f
    }


    private fun resetChart() {
        speedData.clearValues()
        speedData.notifyDataChanged()
        chartSpeed.notifyDataSetChanged()
        chartSpeed.invalidate()
        lastSpeedUpdateMs = 0L
        xIndex = 0f
    }

    // Добавляет точку скорости в график (только из UI потока)
    private fun addSpeedPoint(speedKbps: Float) {
        // этот метод вызывается через runOnUiThread
        val set = speedData.getDataSetByIndex(0)
        // безопасно: если set == null — создаём (на случай)
        if (set == null) {
            val newSet = LineDataSet(ArrayList(), "Скорость (КБ/с)")
            newSet.setDrawCircles(false)
            speedData.addDataSet(newSet)
        }
        val entry = Entry(xIndex, speedKbps)
        speedData.addEntry(entry, 0)
        speedData.notifyDataChanged()
        chartSpeed.notifyDataSetChanged()
        chartSpeed.setVisibleXRangeMaximum(50f)
        chartSpeed.moveViewToX(speedData.entryCount.toFloat())
        chartSpeed.invalidate()
        xIndex += 1f
    }

    // ----- Выбор файла -----
    private fun selectFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, FILE_PICKER_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_PICKER_REQUEST_CODE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                selectedFileUri = uri
                val fileName = getFileName(uri) ?: "selected_file"
                Toast.makeText(this, "Выбран файл: $fileName", Toast.LENGTH_SHORT).show()
            }
        }
    }



    private val recentSpeeds = ArrayDeque<Float>()
    private val maxRecent = 5 // усредняем последние 5 тиков

    private fun addSmoothedSpeedPoint(speedKbps: Float) {
        if (recentSpeeds.size >= maxRecent) {
            recentSpeeds.removeFirst()
        }
        recentSpeeds.addLast(speedKbps)

        val avg = recentSpeeds.average().toFloat()
        addSpeedPoint(avg)
    }


    // ----- Отправка файла (Uri) -----
    private fun sendFileFromUri(uri: Uri, receiverIp: String, port: Int) {
        var socket: Socket? = null
        try {
            socket = Socket(receiverIp, port)
            socket.keepAlive = true
            val output = socket.getOutputStream()

            contentResolver.openInputStream(uri)?.use { input ->
                val fileName = getFileName(uri) ?: "file"

                // Попытка получить размер через OpenableColumns
                var fileLength = -1L
                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (sizeIndex != -1) fileLength = cursor.getLong(sizeIndex)
                    }
                }
                if (fileLength < 0) {
                    try { fileLength = input.available().toLong() } catch (_: Exception) {}
                }

                val fileNameBytes = fileName.toByteArray(Charsets.UTF_8)
                output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(fileNameBytes.size).array())
                output.write(fileNameBytes)
                output.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(if (fileLength >= 0) fileLength else 0L).array())

                // Подготовка таймера замеров
                synchronized(speedLock) { bytesSinceLastUpdate = 0L }
                startSpeedTimer()

                // Отправка данных — только аккумулируем байты, таймер будет обновлять график
                val buffer = ByteArray(8192)
                var bytesRead = input.read(buffer)
                var totalSent = 0L

                while (bytesRead != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalSent += bytesRead
                    // аккумулируем для таймера
                    synchronized(speedLock) { bytesSinceLastUpdate += bytesRead.toLong() }
                    bytesRead = input.read(buffer)
                }

                output.flush()
                // Окончание передачи: даём таймеру шанс отрисовать последний интервал (опционально)
                // Небольшая пауза НЕ обязательна, но можно дать 1 тик (200 ms) если хочется увидеть спад плавно:
                // Thread.sleep(speedIntervalMs) // НЕ реком — блокирует поток вызывающего; лучше не использовать
            }

            runOnUiThread { Toast.makeText(this, "Файл отправлен", Toast.LENGTH_SHORT).show() }
        } catch (e: UnknownHostException) {
            Log.e(TAG, "UnknownHost: ${e.message}")
            runOnUiThread { Toast.makeText(this, "Ошибка: неизвестный хост", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отправки: ${e.message}", e)
            runOnUiThread { Toast.makeText(this, "Ошибка отправки: ${e.message}", Toast.LENGTH_LONG).show() }
        } finally {
            // Всегда останавливаем таймер (в finally, чтобы не оставить его запущенным при ошибке)
            stopSpeedTimer()
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    // ----- Прием файла (сервер) -----
    private fun receiveFile(port: Int) {
        var serverSocket: ServerSocket? = null
        try {
            serverSocket = ServerSocket(port)
            Log.i(TAG, "Server listening on port $port")

            val socket = serverSocket.accept()
            socket.keepAlive = true
            val input = socket.getInputStream()

            val intBuf = readExactly(input, 4)
            val fileNameLength = ByteBuffer.wrap(intBuf).order(ByteOrder.LITTLE_ENDIAN).int
            if (fileNameLength <= 0 || fileNameLength > 1024 * 1024)
                throw IOException("Неверная длина имени файла: $fileNameLength")

            val nameBytes = readExactly(input, fileNameLength)
            val fileName = String(nameBytes, Charsets.UTF_8)

            val longBuf = readExactly(input, 8)
            val fileLength = ByteBuffer.wrap(longBuf).order(ByteOrder.LITTLE_ENDIAN).long
            Log.i(TAG, "Receiving file: $fileName sizeHint=$fileLength")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }

                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        // приём и обновление скорости внутри writeStreamToOutputWithSpeed
                        writeStreamToOutputWithSpeed(input, out, fileLength)
                    }

                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)

                    val shownPath = "${Environment.getExternalStorageDirectory().absolutePath}/Download/$fileName"
                    runOnUiThread {
                        Toast.makeText(this, "Файл сохранён в Загрузки: $shownPath", Toast.LENGTH_LONG).show()
                    }
                } else {
                    val fallbackFile = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
                    FileOutputStream(fallbackFile).use { out ->
                        writeStreamToOutputWithSpeed(input, out, fileLength)
                    }
                    runOnUiThread {
                        Toast.makeText(this, "Файл сохранён (app): ${fallbackFile.absolutePath}", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                try {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()
                    val outFile = File(downloadsDir, fileName)
                    FileOutputStream(outFile).use { out ->
                        writeStreamToOutputWithSpeed(input, out, fileLength)
                    }
                    runOnUiThread {
                        Toast.makeText(this, "Файл сохранён в Загрузки: ${outFile.absolutePath}", Toast.LENGTH_LONG).show()
                    }
                } catch (ex: Exception) {
                    val fallbackFile = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
                    FileOutputStream(fallbackFile).use { out ->
                        writeStreamToOutputWithSpeed(input, out, fileLength)
                    }
                    runOnUiThread {
                        Toast.makeText(this, "Файл сохранён (app): ${fallbackFile.absolutePath}", Toast.LENGTH_LONG).show()
                    }
                }
            }

            input.close()
            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка приёма: ${e.message}", e)
            runOnUiThread { Toast.makeText(this, "Ошибка получения: ${e.message}", Toast.LENGTH_LONG).show() }
        } finally {
            try { serverSocket?.close() } catch (_: Exception) {}
        }
    }

    // новая версия writeStreamToOutput с измерением скорости
    private fun writeStreamToOutputWithSpeed(input: InputStream, output: OutputStream, fileLength: Long) {
        // Подготовка таймера
        synchronized(speedLock) { bytesSinceLastUpdate = 0L }
        startSpeedTimer()

        try {
            val buffer = ByteArray(8192)
            var totalRead = 0L

            if (fileLength > 0) {
                while (totalRead < fileLength) {
                    val toRead = ((fileLength - totalRead).coerceAtMost(buffer.size.toLong())).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) throw EOFException("Соединение прервано во время приёма файла")
                    output.write(buffer, 0, read)
                    totalRead += read
                    // аккумулируем байты для таймера
                    synchronized(speedLock) { bytesSinceLastUpdate += read.toLong() }
                }
            } else {
                var read = input.read(buffer)
                while (read != -1) {
                    output.write(buffer, 0, read)
                    totalRead += read
                    synchronized(speedLock) { bytesSinceLastUpdate += read.toLong() }
                    read = input.read(buffer)
                }
            }
            output.flush()
        } finally {
            // Остановим таймер в любом случае
            stopSpeedTimer()
        }
    }

    // Читает ровно count байт или бросает EOF
    @Throws(IOException::class)
    private fun readExactly(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            if (read == -1) throw EOFException("Не удалось прочитать $count байт, получено $offset")
            offset += read
        }
        return buffer
    }

    private fun saveContacts(contacts: MutableList<Contact>) {
        val editor = sharedPreferences.edit()
        val gson = Gson()
        val json = gson.toJson(contacts)
        editor.putString("contacts", json)
        editor.apply()
    }

    private fun loadContacts(): MutableList<Contact> {
        val gson = Gson()
        val json = sharedPreferences.getString("contacts", null)
        val type = object : TypeToken<MutableList<Contact>>() {}.type
        return gson.fromJson(json, type) ?: mutableListOf()
    }

    private fun getFileName(uri: Uri): String? {
        var fileName: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) fileName = cursor.getString(nameIndex)
            }
        }
        return fileName
    }

    private fun checkPermission() {
        val permissionsNeeded = mutableListOf<String>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), STORAGE_PERMISSION_CODE)
        }
    }

    private fun getDeviceIpAddress(context: Context): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            if (!wifiManager.isWifiEnabled) return null
            val wifiInfo = wifiManager.connectionInfo
            val ipAddress = wifiInfo.ipAddress
            return InetAddress.getByAddress(
                byteArrayOf(
                    (ipAddress and 0xff).toByte(),
                    (ipAddress shr 8 and 0xff).toByte(),
                    (ipAddress shr 16 and 0xff).toByte(),
                    (ipAddress shr 24 and 0xff).toByte()
                )
            ).hostAddress
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось получить IP: ${e.message}")
            return null
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Разрешения получены", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Требуются разрешения для работы с файлами", Toast.LENGTH_LONG).show()
            }
        }
    }
}
