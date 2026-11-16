package com.courier.modules.customerportal.rates.infrastructure;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Refuses hosts that resolve to the server's own network (loopback, private, link-local, carrier-grade NAT, unique-local IPv6…), so a
 * store address typed by a customer can never make the server call an internal service. Checked before every call (DNS can change).
 */
final class PublicHostGuard {

    private PublicHostGuard() {
    }

    /** @throws IllegalArgumentException with a customer-facing reason */
    static void check(String host) {
        InetAddress[] all;
        try {
            all = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("The store address " + host + " was not found. Check it.");
        }
        for (InetAddress a : all) {
            if (!isPublic(a)) {
                throw new IllegalArgumentException("The store address must be a public website.");
            }
        }
    }

    static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            return !(b0 == 0 || (b0 == 100 && b1 >= 64 && b1 <= 127) || (b0 == 192 && b1 == 0 && (b[2] & 0xff) == 0)
                    || (b0 == 198 && (b1 == 18 || b1 == 19)) || b0 >= 240);
        }
        if (a instanceof Inet6Address) {
            int b0 = b[0] & 0xff;
            if ((b0 & 0xfe) == 0xfc) {
                return false; // fc00::/7 unique local
            }
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                mapped &= b[i] == 0;
            }
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return isPublic(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return false;
                }
            }
        }
        return true;
    }
}
