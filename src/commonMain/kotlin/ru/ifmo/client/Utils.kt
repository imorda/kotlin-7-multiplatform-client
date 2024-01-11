package ru.ifmo.client

val NO_RESPONSE =
    HttpResponse(
        HttpStatus(-1),
        HttpHeaders(emptyMap()),
        byteArrayOf(),
    )

fun String.toHeaders(): HttpHeaders {
    val result = mutableMapOf<String, String>()

    val headersSeq = this.splitToSequence('\n')
    val firstLine = headersSeq.first().split(' ')
    if (firstLine.size != 3) {
        throw IllegalStateException("Unexpected headers status format: $firstLine")
    }

    result[":status"] = firstLine[1]

    headersSeq.drop(1).forEach {
        if (it.trim().isEmpty()) return@forEach
        val keyVal = it.split(": ", limit = 2)
        if (keyVal.size != 2) {
            throw IllegalStateException("Unexpected header line format: $it")
        }
        result[keyVal[0]] = keyVal[1]
    }
    return HttpHeaders(result.toMap())
}
