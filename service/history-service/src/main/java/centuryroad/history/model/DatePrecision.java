package centuryroad.history.model;

/**
 * How much of an insight's date is known. Most ancient events have a year and nothing else, and
 * writing a day for them would be inventing one. Declared from the least to the most exact: the
 * order is how two dates are compared "as far as both know".
 */
public enum DatePrecision {

    /** Only the year. Month and day are written as 1 January and mean nothing. */
    YEAR,

    /** The year and the month. The day is written as the 1st and means nothing. */
    MONTH,

    /** The whole day. What a date is when it does not say otherwise. */
    DAY
}
