package com.example.newconstructionappwithlocationtracking

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.newconstructionappwithlocationtracking.adapters.DeliveryFileAdapter
import com.google.firebase.auth.FirebaseAuth
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream

class DeliveryActivity : AppCompatActivity() {

    private lateinit var deliveryTextInput: EditText
    private lateinit var addFileButton: Button
    private lateinit var deliverButton: Button
    private lateinit var filesRecyclerView: RecyclerView
    private lateinit var loadingLayout: View
    private lateinit var mainLayout: View
    private lateinit var backButton: ImageView

    private var orderId: String? = null
    private val client = OkHttpClient()
    private val uploadedFiles = mutableListOf<DeliveryFile>()
    private lateinit var fileAdapter: DeliveryFileAdapter

    data class DeliveryFile(
        val url: String,
        val fileName: String,
        var isUploading: Boolean = false
    )

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            uploadDeliveryFile(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_delivery)

        orderId = intent.getStringExtra("orderId")
        if (orderId == null) {
            Toast.makeText(this, "Order ID not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initializeViews()
        setupRecyclerView()
        setupClickListeners()
    }

    private fun initializeViews() {
        deliveryTextInput = findViewById(R.id.deliveryTextInput)
        addFileButton = findViewById(R.id.addFileButton)
        deliverButton = findViewById(R.id.deliverButton)
        filesRecyclerView = findViewById(R.id.filesRecyclerView)
        loadingLayout = findViewById(R.id.loadingLayout)
        mainLayout = findViewById(R.id.mainLayout)
        backButton = findViewById(R.id.backButton)
    }

    private fun setupRecyclerView() {
        fileAdapter = DeliveryFileAdapter(uploadedFiles) { file ->
            // Remove file from list
            uploadedFiles.remove(file)
            fileAdapter.notifyDataSetChanged()
            updateDeliverButtonState()
        }
        filesRecyclerView.layoutManager = LinearLayoutManager(this)
        filesRecyclerView.adapter = fileAdapter
    }

    private fun setupClickListeners() {
        backButton.setOnClickListener {
            finish()
        }

        addFileButton.setOnClickListener {
            filePickerLauncher.launch("*/*") // Accept all file types
        }

        deliverButton.setOnClickListener {
            deliverOrder()
        }

        // Update deliver button state when text changes
        deliveryTextInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateDeliverButtonState()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun uploadDeliveryFile(uri: Uri) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        Log.d("DELIVERY_UPLOAD", "Starting file upload for URI: $uri")

        // Add placeholder file to show loading
        val fileName = getFileName(uri) ?: "uploaded_file"
        Log.d("DELIVERY_UPLOAD", "File name: $fileName")
        val loadingFile = DeliveryFile("", fileName, true)
        uploadedFiles.add(loadingFile)
        fileAdapter.notifyItemInserted(uploadedFiles.size - 1)

        user.getIdToken(true).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val idToken = task.result?.token ?: return@addOnCompleteListener
                val backendUrl = getBackendUrl()
                val url = "$backendUrl/api/upload-delivery-file"
                Log.d("DELIVERY_UPLOAD", "Upload URL: $url")
                Log.d("DELIVERY_UPLOAD", "Order ID: $orderId")

                val inputStream: InputStream? = contentResolver.openInputStream(uri)
                val fileBytes = inputStream?.readBytes()
                inputStream?.close()

                if (fileBytes == null) {
                    Log.e("DELIVERY_UPLOAD", "Failed to read file bytes")
                    uploadedFiles.remove(loadingFile)
                    fileAdapter.notifyDataSetChanged()
                    Toast.makeText(this, "Failed to read file", Toast.LENGTH_SHORT).show()
                    return@addOnCompleteListener
                }

                Log.d("DELIVERY_UPLOAD", "File size: ${fileBytes.size} bytes")

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", fileName,
                        RequestBody.create("application/octet-stream".toMediaTypeOrNull(), fileBytes))
                    .addFormDataPart("orderId", orderId ?: "")
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer $idToken")
                    .build()

                Log.d("DELIVERY_UPLOAD", "Making upload request...")

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e("DELIVERY_UPLOAD", "Network failure: ${e.message}", e)
                        runOnUiThread {
                            uploadedFiles.remove(loadingFile)
                            fileAdapter.notifyDataSetChanged()
                            Toast.makeText(this@DeliveryActivity, "File upload failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string() ?: ""
                        Log.d("DELIVERY_UPLOAD", "Response code: ${response.code}")
                        Log.d("DELIVERY_UPLOAD", "Response body: $body")

                        runOnUiThread {
                            try {
                                if (response.isSuccessful) {
                                    val json = JSONObject(body)
                                    if (json.optBoolean("success")) {
                                        val data = json.optJSONObject("data")
                                        val fileUrl = data?.optString("fileUrl") ?: ""
                                        val uploadedFileName = data?.optString("fileName") ?: fileName

                                        Log.d("DELIVERY_UPLOAD", "Upload successful. File URL: $fileUrl")

                                        // Replace loading file with actual file
                                        val index = uploadedFiles.indexOf(loadingFile)
                                        if (index != -1) {
                                            uploadedFiles[index] = DeliveryFile(fileUrl, uploadedFileName, false)
                                            fileAdapter.notifyItemChanged(index)
                                        }
                                        updateDeliverButtonState()
                                        Toast.makeText(this@DeliveryActivity, "File uploaded successfully", Toast.LENGTH_SHORT).show()
                                    } else {
                                        val errorMsg = json.optString("message", "Upload failed")
                                        Log.e("DELIVERY_UPLOAD", "Backend error: $errorMsg")
                                        throw Exception(errorMsg)
                                    }
                                } else {
                                    Log.e("DELIVERY_UPLOAD", "HTTP error ${response.code}: $body")
                                    throw Exception("HTTP ${response.code}")
                                }
                            } catch (e: Exception) {
                                Log.e("DELIVERY_UPLOAD", "Upload processing error: ${e.message}", e)
                                uploadedFiles.remove(loadingFile)
                                fileAdapter.notifyDataSetChanged()
                                Toast.makeText(this@DeliveryActivity, "Upload failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                })
            } else {
                Log.e("DELIVERY_UPLOAD", "Failed to get Firebase token: ${task.exception?.message}")
            }
        }
    }

    private fun deliverOrder() {
        val deliveryText = deliveryTextInput.text.toString().trim()
        if (deliveryText.isEmpty()) {
            Toast.makeText(this, "Please enter delivery description", Toast.LENGTH_SHORT).show()
            return
        }

        showLoading()

        val user = FirebaseAuth.getInstance().currentUser ?: return
        user.getIdToken(true).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val idToken = task.result?.token ?: return@addOnCompleteListener
                val backendUrl = getBackendUrl()
                val url = "$backendUrl/api/orders/$orderId/deliver"

                // Prepare file URLs array
                val fileUrls = JSONArray()
                uploadedFiles.filter { !it.isUploading }.forEach { file ->
                    fileUrls.put(file.url)
                }

                val requestBody = JSONObject().apply {
                    put("deliveryText", deliveryText)
                    put("deliveryFileUrls", fileUrls)
                }.toString().toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer $idToken")
                    .addHeader("Content-Type", "application/json")
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        runOnUiThread {
                            hideLoading()
                            Toast.makeText(this@DeliveryActivity, "Delivery failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string() ?: ""
                        runOnUiThread {
                            hideLoading()
                            try {
                                if (response.isSuccessful) {
                                    val json = JSONObject(body)
                                    if (json.optBoolean("success")) {
                                        Toast.makeText(this@DeliveryActivity, "Order delivered successfully!", Toast.LENGTH_SHORT).show()
                                        // Return to order details with result
                                        setResult(RESULT_OK)
                                        finish()
                                    } else {
                                        throw Exception(json.optString("message", "Delivery failed"))
                                    }
                                } else {
                                    throw Exception("HTTP ${response.code}")
                                }
                            } catch (e: Exception) {
                                Toast.makeText(this@DeliveryActivity, "Delivery failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                })
            }
        }
    }

    private fun updateDeliverButtonState() {
        val hasText = deliveryTextInput.text.toString().trim().isNotEmpty()
        val hasUploadingFiles = uploadedFiles.any { it.isUploading }

        deliverButton.isEnabled = hasText && !hasUploadingFiles
        deliverButton.alpha = if (deliverButton.isEnabled) 1.0f else 0.5f
    }

    private fun showLoading() {
        loadingLayout.visibility = View.VISIBLE
        mainLayout.visibility = View.GONE
    }

    private fun hideLoading() {
        loadingLayout.visibility = View.GONE
        mainLayout.visibility = View.VISIBLE
    }

    private fun getFileName(uri: Uri): String? {
        val cursor = contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst() && nameIndex != -1) {
                it.getString(nameIndex)
            } else null
        }
    }

    private fun getBackendUrl(): String {
        return try {
            assets.open(".env").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.trim().startsWith("BACKEND_URL=")) {
                        return line.substringAfter("=").trim()
                    }
                }
            }
            "https://real-pakistan-backend.onrender.com"
        } catch (ignored: Exception) {
            "https://real-pakistan-backend.onrender.com"
        }
    }
}
