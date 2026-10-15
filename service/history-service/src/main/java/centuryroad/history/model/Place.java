package centuryroad.history.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Where the map should go for an insight. approximate is for the honest cases where the
 * event did not happen at a point of the world map - on the Moon, in orbit - and the pin
 * stands for a related place on Earth (a launch site); the note then says so, and a
 * frontend must show it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A place on Earth for the map to move to.")
public record Place(
        @Schema(description = "The place's name, as it should be shown.", example = "Cosmodromo di Baikonur") String name,
        @Schema(description = "Latitude, -90 to 90, positive north.", example = "45.92") double lat,
        @Schema(description = "Longitude, -180 to 180, positive east.", example = "63.34") double lon,
        @Schema(description = "ISO 3166-1 alpha-2 code of the country the pin falls in today. Absent when unclear.", example = "KZ") String countryCode,
        @Schema(description = "True when the pin is only a stand-in for where the event happened (a launch site for an event "
                + "on the Moon or in orbit). Always comes with a note: show it.") boolean approximate,
        @Schema(description = "Why the pin is where it is. Present exactly when approximate is true.") String note) {
}
