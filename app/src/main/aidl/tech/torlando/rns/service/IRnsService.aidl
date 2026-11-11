package tech.torlando.rns.service;

import tech.torlando.rns.service.IRnsCallback;

/** Minimal IPC interface for the RNS service process. */
interface IRnsService {
    /** Start RNS with the given config INI content. */
    void start(String configIni);

    /** Stop RNS and exit the service process. */
    void stop();

    /** Get current state + stats as JSON. */
    String getSnapshot();

    /** Register for push updates. */
    void registerCallback(IRnsCallback callback);

    /** Unregister from push updates. */
    void unregisterCallback(IRnsCallback callback);

    /** Enable interface discovery. */
    void enableDiscovery();

    /** Invoke a messenger (identity / nodes / LXMF) method. Returns {"result": ...} or {"error": "..."}. */
    String messengerCall(String method, String argsJson);
}
