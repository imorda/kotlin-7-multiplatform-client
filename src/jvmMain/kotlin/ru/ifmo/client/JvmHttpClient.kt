package ru.ifmo.client

import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import java.net.URI
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers

class JvmHttpClient : HttpClient {
    private val ioDispatcher = Dispatchers.IO

    private val coroutineContext = ioDispatcher + CoroutineName("JvmHttpClient")

    private val client: java.net.http.HttpClient by lazy {
        java.net.http.HttpClient.newBuilder().apply {
            executor(ioDispatcher.asExecutor())
        }.build()
    }

    override fun close() {
        coroutineContext.cancel()
    }

    override suspend fun request(
        method: HttpMethod,
        request: HttpRequest,
    ): HttpResponse {
        val engineRequest =
            java.net.http.HttpRequest.newBuilder().apply {
                uri(URI.create(request.url))
                request.headers.value.forEach { (name, value) ->
                    header(name, value)
                }
                when (method) {
                    HttpMethod.GET -> GET()
                    HttpMethod.POST -> POST(BodyPublishers.ofByteArray(request.body))
                    HttpMethod.PUT -> PUT(BodyPublishers.ofByteArray(request.body))
                    HttpMethod.DELETE -> DELETE()
                }
            }.build()
        return withContext(coroutineContext) {
            val result =
                client.sendAsync(engineRequest, BodyHandlers.ofByteArray())?.await()
                    ?: return@withContext NO_RESPONSE
            return@withContext HttpResponse(
                HttpStatus(result.statusCode()),
                HttpHeaders(
                    result.headers().map().flatMap { (key, values) ->
                        values.map { value -> (key to value) }
                    }.toMap(),
                ),
                result.body(),
            )
        }
    }
}
