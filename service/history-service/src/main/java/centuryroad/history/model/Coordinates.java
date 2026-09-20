package centuryroad.history.model;

/** Where an article's subject is, in decimal degrees: latitude north and longitude east
 *  are positive. Only values that are a place on Earth are ever built. */
public record Coordinates(double lat, double lon) {
}
