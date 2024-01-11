package ru.ifmo.client

import kotlinx.coroutines.*
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import kotlin.js.Promise

class FetchHttpClient : HttpClient {
    private val ioDispatcher = Dispatchers.Default

    private val coroutineContext = ioDispatcher + CoroutineName("FetchHttpClient")

    override fun close() {
        coroutineContext.cancel()
    }

    override suspend fun request(
        method: HttpMethod,
        request: HttpRequest,
    ): HttpResponse {
        val engineRequest = request.toJs(method)

        return withContext(coroutineContext) {
            val response = commonFetch(request.url, engineRequest)
            val responseBody = readBody(response)
            val headers = readHeaders(response)

            return@withContext HttpResponse(
                HttpStatus(response.status.toInt()),
                headers,
                responseBody,
            )
        }
    }
}

private enum class Platform { Node, Browser }

private val platform: Platform
    get() {
        val hasNodeApi =
            js(
                """
            (typeof process !== 'undefined' 
                && process.versions != null 
                && process.versions.node != null) ||
            (typeof window !== 'undefined' 
                && typeof window.process !== 'undefined' 
                && window.process.versions != null 
                && window.process.versions.node != null)
            """,
            ) as Boolean
        return if (hasNodeApi) Platform.Node else Platform.Browser
    }

// Inspired by ktor
internal suspend fun commonFetch(
    input: String,
    init: RequestInit,
): Response =
    suspendCancellableCoroutine { continuation ->
        val promise: Promise<Response> =
            when (platform) {
                Platform.Browser -> fetch(input, init)
                Platform.Node -> {
                    nodeFetch(input, init.asNodeOptions()) as Promise<Response>
                }
            }

        promise.then(
            onFulfilled = {
                continuation.resumeWith(Result.success(it))
            },
            onRejected = {
                continuation.resumeWith(Result.failure(Error("Fail to fetch", it)))
            },
        )
    }

internal fun HttpRequest.toJs(ktMethod: HttpMethod): RequestInit {
    val jsHeaders = js("({})")

    headers.value.forEach { (key, value) ->
        jsHeaders[key] = value
    }

    return buildObject {
        method = ktMethod.toString()
        headers = jsHeaders

        this@toJs.body?.let { body = Uint8Array(it.toTypedArray()) }
    }
}

internal fun readHeaders(response: Response): HttpHeaders {
    val result = mutableMapOf<String, String>()

    response.headers.asDynamic().forEach { value: String, key: String ->
        result[key] = value
        Unit
    }

    return HttpHeaders(result.toMap())
}

internal suspend fun readBody(response: Response): ByteArray =
    suspendCancellableCoroutine { continuation ->
        response.arrayBuffer().then(
            onFulfilled = {
                continuation.resumeWith(Result.success(Uint8Array(it).asByteArray()))
            },
            onRejected = {
                continuation.resumeWith(Result.failure(Error("Fail to decode body", it)))
            },
        )
    }

internal fun <T> buildObject(block: T.() -> Unit): T = (js("{}") as T).apply(block)

@Suppress("UnsafeCastFromDynamic")
internal fun Uint8Array.asByteArray(): ByteArray {
    return Int8Array(buffer, byteOffset, length).asDynamic()
}

private val nodeFetch: dynamic
    get() = js("eval('require')('node-fetch')")

private fun RequestInit.asNodeOptions(): dynamic = js("Object").assign(js("Object").create(null), this)

external fun fetch(
    input: String,
    init: RequestInit? = definedExternally,
): Promise<Response>
