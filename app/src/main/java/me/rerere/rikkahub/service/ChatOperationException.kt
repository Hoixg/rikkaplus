package me.rerere.rikkahub.service

/** Errors from chat requests against missing or invalid conversation state. */
sealed class ChatOperationException(override val message: String) : RuntimeException(message)

class ChatItemNotFoundException(message: String) : ChatOperationException(message)

class InvalidChatOperationException(message: String) : ChatOperationException(message)
