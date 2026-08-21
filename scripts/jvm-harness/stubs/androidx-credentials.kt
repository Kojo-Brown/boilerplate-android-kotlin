@file:Suppress("PackageDirectoryMismatch", "unused")

package androidx.credentials.exceptions

/**
 * Stand-ins for the two Credential Manager exception types the sign-in view model
 * distinguishes between, for the offline harness only.
 *
 * The hierarchy is what matters and it is preserved: `GetCredentialCancellationException` is a
 * `GetCredentialException`, so a view model that branches on the cancellation type branches
 * the same way here as on device.
 */
public open class GetCredentialException(
    public val type: String = "",
    override val message: String? = null,
) : Exception(message)

public class GetCredentialCancellationException(
    message: String? = null,
) : GetCredentialException(type = "CANCELLED", message = message)
