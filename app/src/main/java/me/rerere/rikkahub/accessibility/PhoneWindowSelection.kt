package me.rerere.rikkahub.accessibility

internal enum class PhoneWindowKind { APPLICATION, SYSTEM, KEYBOARD, OTHER }

/** Window metadata only; native windows and nodes never enter snapshot storage. */
internal data class PhoneWindowCandidate(
    val id: Int,
    val index: Int,
    val kind: PhoneWindowKind,
    val layer: Int,
    val active: Boolean,
    val focused: Boolean,
    val bounds: PhoneBounds,
)

internal fun selectPhoneWindow(windows: List<PhoneWindowCandidate>, width: Int, height: Int): PhoneWindowCandidate? =
    windows.filter { window ->
        val bounds = window.bounds
        val visibleWidth = (minOf(bounds.right, width) - maxOf(bounds.left, 0)).coerceAtLeast(0).toLong()
        val visibleHeight = (minOf(bounds.bottom, height) - maxOf(bounds.top, 0)).coerceAtLeast(0).toLong()
        val area = visibleWidth * visibleHeight
        area > 0 && when (window.kind) {
            PhoneWindowKind.APPLICATION -> true
            PhoneWindowKind.SYSTEM -> window.active || window.focused || area >= width.toLong() * height / 2
            PhoneWindowKind.KEYBOARD, PhoneWindowKind.OTHER -> false
        }
    }.sortedWith(compareByDescending<PhoneWindowCandidate> { it.layer }.thenByDescending { it.active || it.focused })
        .firstOrNull()
