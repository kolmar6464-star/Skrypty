package pl.folderlan.app;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** Allows only LAN destinations. This is deliberately not an Internet SMB client. */
public final class HostPolicy {
    private HostPolicy() { }

    public static String cleanHost(String host) {
        if (host == null) throw new IllegalArgumentException("Podaj adres IP lub nazwę komputera.");
        String value = host.trim();
        if (value.length() >= 2 && value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.isEmpty() || value.length() > 253 || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                || value.indexOf('@') >= 0 || value.indexOf('#') >= 0 || value.indexOf('?') >= 0) {
            throw new IllegalArgumentException("Wpisz sam adres komputera, bez smb://, portu i ścieżki.");
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i)) || Character.isISOControl(value.charAt(i))) {
                throw new IllegalArgumentException("Nieprawidłowy adres komputera.");
            }
        }
        // A colon is valid in an IPv6 literal, never in a hostname plus a port.
        if (value.indexOf(':') >= 0 && value.indexOf(':') == value.lastIndexOf(':')) {
            throw new IllegalArgumentException("Nie dopisuj numeru portu. Aplikacja używa SMB 445 tylko w LAN.");
        }
        return value;
    }

    public static boolean isLocalAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 10 || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168) || (first == 169 && second == 254);
        }
        // IPv6 unique-local fc00::/7 or link-local fe80::/10.
        return bytes.length == 16 && (((bytes[0] & 0xfe) == 0xfc)
                || ((bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xc0) == 0x80));
    }

    public static InetAddress resolveLocal(String host) throws IOException {
        String cleaned = cleanHost(host);
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(cleaned);
        } catch (UnknownHostException error) {
            throw new IOException("Nie znaleziono komputera. Sprawdź nazwę lub wpisz jego lokalny adres IP.", error);
        }
        InetAddress result = null;
        for (InetAddress address : addresses) {
            // Reject ambiguous DNS results rather than permitting a public fallback.
            if (!isLocalAddress(address)) throw new IOException("Dozwolony jest wyłącznie lokalny adres LAN komputera.");
            if (result == null || address.getAddress().length == 4) result = address;
        }
        if (result == null) throw new IOException("Nie znaleziono lokalnego adresu komputera.");
        return result;
    }
}
