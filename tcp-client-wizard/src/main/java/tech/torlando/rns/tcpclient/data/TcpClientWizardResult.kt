package tech.torlando.rns.tcpclient.data

/**
 * Output of the TCP Client wizard — a flat, module-independent representation
 * of a configured TCP client interface. The host app maps this to its own
 * persistence model (e.g. InterfaceConfig.TcpClient).
 */
data class TcpClientWizardResult(
    val name: String,
    val targetHost: String,
    val targetPort: Int,
    val bootstrapOnly: Boolean,
    val socksProxyEnabled: Boolean,
    val socksProxyHost: String,
    val socksProxyPort: Int,
    // IFAC (Interface Authentication Code) settings — empty passphrase means no IFAC
    val networkName: String = "",
    val passphrase: String = "",
    /** IFAC size in bits (RNS minimum is 8, must be a multiple of 8); 0 = leave to RNS default */
    val ifacSize: Int = 0,
)
