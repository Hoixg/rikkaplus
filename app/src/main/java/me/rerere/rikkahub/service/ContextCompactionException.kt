package me.rerere.rikkahub.service

internal class ContextCompactionException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(
    listOfNotNull(message, cause?.message?.takeIf { it.isNotBlank() && it != message }).joinToString("\n\n"),
    cause,
)
