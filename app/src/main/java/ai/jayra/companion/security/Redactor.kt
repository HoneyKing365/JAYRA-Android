package ai.jayra.companion.security

/** Best-effort scrubbing for the optional per-session accessibility text sharing. */
object Redactor {
    private val patterns = listOf(
        Regex("""[\w.+-]+@[\w-]+\.[\w.]+"""),                                  // e-mail addresses
        Regex("""\b\d{4,}\b"""),                                              // OTPs, card/phone numbers
        Regex("""\b(?=[A-Za-z0-9_\-]{20,}\b)(?=\w*\d)(?=\w*[A-Za-z])[A-Za-z0-9_\-]+\b"""), // token-like strings
    )

    fun redact(input: String, max: Int = 256): String {
        var out = input.take(2_000)
        for (p in patterns) out = out.replace(p, "[redacted]")
        return out.take(max)
    }
}
