@file:OptIn(ExperimentalForeignApi::class)

package ru.ifmo.client

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import libcurl.*
import platform.posix.size_t
import kotlin.math.min

// Hint: See examples by the link - https://curl.se/libcurl/c/example.html

private val curlGlobalInit: Int = curl_global_init(CURL_GLOBAL_ALL.convert()).convert()

private fun writeToByteArray(
    buffer: CPointer<ByteVar>,
    size: size_t,
    count: size_t,
    userdata: ByteArray,
): ByteArray {
    val realSize = (size * count).toInt()

    val newArray = userdata.copyOf(userdata.size + realSize)

    for (i in 0..<realSize) {
        newArray[i + userdata.size] = buffer[i]
    }

    return newArray
}

class HeaderData(var body: ByteArray)

private fun onCurlHeadersReceived(
    buffer: CPointer<ByteVar>,
    size: size_t,
    count: size_t,
    userdata: COpaquePointer,
): Int {
    val headerData = userdata.fromCPointer<HeaderData>()
    headerData.body = writeToByteArray(buffer, size, count, headerData.body)
    return (size * count).toInt()
}

class RequestData(var body: ByteArray)

private fun onRequestTransfer(
    buffer: CPointer<ByteVar>,
    size: size_t,
    count: size_t,
    userdata: COpaquePointer,
): Int {
    val requestData = userdata.fromCPointer<RequestData>()
    val requestedSize = (size * count).toInt()
    val toCopy = min(requestedSize, requestData.body.size)

    for (i in 0..<toCopy) {
        buffer[i] = requestData.body[i]
    }
    return toCopy
}

class ResponseData(var body: ByteArray)

fun onResponseTransfer(
    buffer: CPointer<ByteVar>,
    size: size_t,
    count: size_t,
    userdata: COpaquePointer,
): Int {
    val responseData = userdata.fromCPointer<ResponseData>()
    responseData.body = writeToByteArray(buffer, size, count, responseData.body)
    return (size * count).toInt()
}

private inline fun <T : Any> T.asStablePointer(): COpaquePointer = StableRef.create(this).asCPointer()

private inline fun <reified T : Any> COpaquePointer.fromCPointer(): T = asStableRef<T>().get()

class CurlHttpClient : HttpClient {
    private val ioDispatcher = Dispatchers.IO

    private val coroutineContext = ioDispatcher + CoroutineName("NativeHttpClient")

    override suspend fun request(
        method: HttpMethod,
        request: HttpRequest,
    ): HttpResponse {
        if (curlGlobalInit != 0) {
            throw CurlException("curl_global_init() returned non-zero verify: $curlGlobalInit")
        }
        val curlHandle =
            curl_easy_init()
                ?: throw CurlException("curl_easy_init() failed.")

        lateinit var curl: Curl

        try {
            return withContext(coroutineContext) {
                curl = Curl(curlHandle)
                curl.setupRequest(method, request)
                val response = curl.perform()
                return@withContext response
            }
        } finally {
            curl.dispose()
            curl_easy_cleanup(curlHandle)
        }
    }

    override fun close() {
        coroutineContext.cancel()
    }
}

typealias CurlEasyPointer = COpaquePointer

data class Curl(val curl: CurlEasyPointer) {
    private val responseHeaders = HeaderData(byteArrayOf()).asStablePointer()
    private val responseBody = ResponseData(byteArrayOf()).asStablePointer()
    private val requestBody = RequestData(byteArrayOf()).asStablePointer()

    fun setupRequest(
        httpMethod: HttpMethod,
        request: HttpRequest,
    ) {
        setupMethod(httpMethod, request.bodySize)
        setupUrl(request.url)
        setupHeaders(request.headers)
        setupRequestTransfer(request.body, request.bodySize)
        setupResponseTransfer()
    }

    fun perform(): HttpResponse {
        curl_easy_perform(curl).verify()

        memScoped {
            val responseCode = alloc<LongVar>()
            curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, responseCode.ptr).verify()
            return HttpResponse(
                HttpStatus(responseCode.value.toInt()),
                responseHeaders.fromCPointer<HeaderData>().body.decodeToString().toHeaders(),
                responseBody.fromCPointer<ResponseData>().body,
            )
        }
    }

    fun dispose() {
        responseHeaders.asStableRef<HeaderData>().dispose()
        responseBody.asStableRef<ResponseData>().dispose()
        requestBody.asStableRef<RequestData>().dispose()
    }

    private fun setupMethod(
        httpMethod: HttpMethod,
        size: Long,
    ) {
        when (httpMethod) {
            HttpMethod.GET -> {
                curl_easy_setopt(curl, CURLOPT_HTTPGET, 1L)
            }
            HttpMethod.POST -> {
                curl_easy_setopt(curl, CURLOPT_POST, 1L)
                curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, size)
            }
            HttpMethod.PUT -> {
                curl_easy_setopt(curl, CURLOPT_PUT, 1L)
            }
            HttpMethod.DELETE -> {
                curl_easy_setopt(curl, CURLOPT_CUSTOMREQUEST, "DELETE")
            }
        }
    }

    private fun setupUrl(url: String) {
        curl_easy_setopt(curl, CURLOPT_URL, url)
    }

    private fun setupHeaders(headers: HttpHeaders) {
        var result: CPointer<curl_slist>? = null

        headers.value.forEach { (key, value) ->
            val header = "$key: $value"
            result = curl_slist_append(result, header)
        }

        result = curl_slist_append(result, "Expect:")
        curl_easy_setopt(curl, CURLOPT_HTTPHEADER, result)
    }

    private fun setupRequestTransfer(
        body: ByteArray?,
        size: Long,
    ) {
        if (body == null) return

        requestBody.fromCPointer<RequestData>().body = body

        curl_easy_setopt(curl, CURLOPT_READDATA, requestBody)
        curl_easy_setopt(curl, CURLOPT_READFUNCTION, staticCFunction(::onRequestTransfer))
        curl_easy_setopt(curl, CURLOPT_INFILESIZE_LARGE, size)
    }

    private fun setupResponseTransfer() {
        curl_easy_setopt(curl, CURLOPT_HEADERDATA, responseHeaders)
        curl_easy_setopt(curl, CURLOPT_HEADERFUNCTION, staticCFunction(::onCurlHeadersReceived))

        curl_easy_setopt(curl, CURLOPT_WRITEDATA, responseBody)
        curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, staticCFunction(::onResponseTransfer))
    }
}

private val HttpRequest.bodySize: Long
    get() = body?.size?.toLong() ?: -1L

private fun CURLcode.verify() {
    if (this != CURLE_OK) {
        throw CurlException("Unexpected curl verify: ${curl_easy_strerror(this)?.toKString()}")
    }
}

class CurlException(message: String) : RuntimeException(message)
