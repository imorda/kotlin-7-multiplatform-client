package ru.ifmo.client

actual fun HttpClient(): HttpClient = FetchHttpClient()
