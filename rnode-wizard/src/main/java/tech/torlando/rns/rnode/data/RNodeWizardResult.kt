package tech.torlando.rns.rnode.data

/**
 * Output of the RNode wizard — a flat, module-independent representation
 * of a configured RNode interface. The host app maps this to its own
 * persistence model (e.g. InterfaceConfig.RNodeInterface).
 */
data class RNodeWizardResult(
    val name: String,
    /** "classic", "ble", "tcp", or "usb" */
    val connectionMode: String,
    /** BT device name, host:port, or empty for USB */
    val targetDevice: String,
    val frequency: Long,
    val bandwidth: Int,
    val spreadingFactor: Int,
    val codingRate: Int,
    val txPower: Int,
    /** "full", "gateway", "boundary", etc. */
    val interfaceMode: String,
    val stAlock: String,
    val ltAlock: String,
    // IFAC (Interface Authentication Code) settings — empty passphrase means no IFAC
    val networkName: String,
    val passphrase: String,
    /** IFAC size in bits (RNS minimum is 8, must be a multiple of 8); 0 = leave to RNS default */
    val ifacSize: Int,
    /** Radio parameters were entered manually ("Custom") rather than from a region/preset. */
    val customRadio: Boolean = false,
    /** Draw a custom image on the radio's own screen. Off keeps the RNode display. */
    val enableFramebuffer: Boolean = false,
)
