package centuryroad.history.service;

import centuryroad.history.config.HistoryProperties;
import centuryroad.history.exception.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Keeps "Segnala un errore" from being a way to fill the database: a fixed window per visitor
 * (an IPv4 address, or an IPv6 /64), and one for the whole service so that many addresses
 * together cannot do it either.
 *
 * In memory and per instance on purpose, the same trade-off as auth-service's login limiter:
 * the service runs one replica, and a shared store is the upgrade for the day it runs two.
 * A restart forgets the counts, which costs nothing for a limit that is there to stop floods.
 */
@Component
public class ReportRateLimiter {

    /** An address is never "*", so the service-wide window cannot collide with a visitor's. */
    private static final String SERVICE = "*";
    /** Past this many tracked addresses, the ones whose window is over are dropped. */
    private static final int PRUNE_ABOVE = 10_000;

    private record Window(Instant start, int count) {
    }

    private final HistoryProperties.Reports limits;
    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();

    public ReportRateLimiter(HistoryProperties properties, Clock clock) {
        this.limits = properties.reports();
        this.clock = clock;
    }

    /**
     * Counts one report from this address, or refuses it. A refused attempt is not counted, so a
     * visitor who is over the limit does not eat into everyone else's share of the service's.
     */
    public synchronized void acquire(String address) {
        String client = clientKey(address);
        Instant now = clock.instant();
        if (windows.size() > PRUNE_ABOVE) {
            windows.values().removeIf(window -> expired(window, now));
        }
        Window own = current(client, now);
        Window service = current(SERVICE, now);
        if (own.count() >= limits.maxPerClient()) {
            throw refusal("Too many reports from " + client, own, now);
        }
        if (service.count() >= limits.maxTotal()) {
            throw refusal("Too many reports in total", service, now);
        }
        windows.put(client, new Window(own.start(), own.count() + 1));
        windows.put(SERVICE, new Window(service.start(), service.count() + 1));
    }

    private static final Pattern IPV6_CHARACTERS = Pattern.compile("^[0-9a-fA-F:.]+$");

    /**
     * Who counts as one visitor. An IPv4 address is one. An IPv6 address is not: a household or
     * a phone is handed a whole /64 (2^64 addresses) and can write from any of them, so counting
     * exact addresses would give one visitor a fresh quota per request. Every address of a /64
     * therefore shares one key, the first 64 bits.
     *
     * Anything that is not an IP literal is used as it is. It is parsed only when it looks like
     * one (hex digits, colons, dots): InetAddress.getByName resolves anything else through DNS,
     * and the address has passed through headers on its way here.
     */
    static String clientKey(String address) {
        if (address == null || address.isBlank()) {
            return "unknown";
        }
        if (address.indexOf(':') < 0) {
            return address;
        }
        String literal = address;
        // How a header writes an IPv6 address: "[2001:db8::1]", with the port after the bracket
        // when there is one. Found by a test that sent the real header: without this every
        // address looked like something that was not an IP, and kept a key of its own.
        if (literal.startsWith("[") && literal.indexOf(']') > 0) {
            literal = literal.substring(1, literal.indexOf(']'));
        }
        if (literal.indexOf('%') >= 0) {
            literal = literal.substring(0, literal.indexOf('%'));
        }
        if (!IPV6_CHARACTERS.matcher(literal).matches()) {
            return address;
        }
        try {
            InetAddress parsed = InetAddress.getByName(literal);
            if (!(parsed instanceof Inet6Address)) {
                // ::ffff:203.0.113.5 is IPv4 written in IPv6: the same visitor as 203.0.113.5.
                return parsed.getHostAddress();
            }
            byte[] b = parsed.getAddress();
            return String.format("%02x%02x:%02x%02x:%02x%02x:%02x%02x::/64",
                    b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]);
        } catch (UnknownHostException | RuntimeException e) {
            return address;
        }
    }

    private Window current(String key, Instant now) {
        Window window = windows.get(key);
        return window == null || expired(window, now) ? new Window(now, 0) : window;
    }

    private boolean expired(Window window, Instant now) {
        return !now.isBefore(window.start().plus(limits.window()));
    }

    private TooManyRequestsException refusal(String message, Window window, Instant now) {
        Duration left = Duration.between(now, window.start().plus(limits.window()));
        long seconds = Math.max(1, (left.toMillis() + 999) / 1000);
        return new TooManyRequestsException(message,
                "Hai inviato troppe segnalazioni. Riprova più tardi.", seconds);
    }
}
