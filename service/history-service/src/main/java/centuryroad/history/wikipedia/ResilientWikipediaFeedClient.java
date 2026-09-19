package centuryroad.history.wikipedia;

import centuryroad.history.exception.UpstreamRateLimitedException;
import centuryroad.history.exception.UpstreamUnavailableException;
import centuryroad.history.model.DayFeed;
import centuryroad.history.model.Language;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;

import java.time.MonthDay;
import java.util.function.Supplier;

/**
 * Wraps the real client in everything that keeps this service a polite neighbour, in one
 * place so none of it is scattered across callers. From the inside out:
 *
 *   bulkhead        at most a few requests to Wikipedia in flight at once (its own etiquette
 *                   page asks for three or fewer)
 *   retry           a transient failure gets one more try after a short pause - and only a
 *                   transient one: a 429 or a malformed body would answer the same again
 *   circuit breaker after a run of failures, stop asking for a while instead of piling on
 *
 * A 429 is handled outside all three, by the cool-down: it is not a fault to count, it is
 * an instruction to stay away for as long as Wikipedia said.
 *
 * Anything refused locally (open circuit, no free slot) surfaces as UpstreamUnavailable,
 * so callers have three failure types to think about, not five.
 */
public class ResilientWikipediaFeedClient implements WikipediaFeedClient {

    private final WikipediaFeedClient delegate;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final Bulkhead bulkhead;
    private final UpstreamCooldown cooldown;

    public ResilientWikipediaFeedClient(WikipediaFeedClient delegate, CircuitBreaker circuitBreaker,
                                        Retry retry, Bulkhead bulkhead, UpstreamCooldown cooldown) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
        this.bulkhead = bulkhead;
        this.cooldown = cooldown;
    }

    @Override
    public DayFeed fetchDay(Language language, MonthDay day) {
        cooldown.remaining().ifPresent(left -> {
            throw new UpstreamRateLimitedException(left);
        });

        Supplier<DayFeed> call = () -> delegate.fetchDay(language, day);
        call = Bulkhead.decorateSupplier(bulkhead, call);
        call = Retry.decorateSupplier(retry, call);
        call = CircuitBreaker.decorateSupplier(circuitBreaker, call);

        try {
            return call.get();
        } catch (UpstreamRateLimitedException e) {
            cooldown.start(e.getRetryAfter());
            throw e;
        } catch (CallNotPermittedException e) {
            throw new UpstreamUnavailableException("Wikipedia is being given a rest after repeated failures", e);
        } catch (BulkheadFullException e) {
            throw new UpstreamUnavailableException("Too many requests to Wikipedia already in flight", e);
        }
    }
}
