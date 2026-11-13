package hev.htproxy;

/**
 * Ponte JNI para o hev-socks5-tunnel (tun2socks).
 * O libhev-socks5-tunnel.so registra os métodos nativos nesta classe exata
 * (hev/htproxy/TProxyService) no JNI_OnLoad, então o pacote e o nome não podem mudar.
 */
public final class TProxyService {
    private TProxyService() {
    }

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    public static native boolean TProxyStartService(String config_path, int fd);

    public static native boolean TProxyStopService();

    public static native boolean TProxyIsRunning();

    public static native long[] TProxyGetStats();
}
