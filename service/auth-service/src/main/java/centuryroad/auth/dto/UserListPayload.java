package centuryroad.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Every user.")
public record UserListPayload(@Schema(description = "The users, in no particular order.") List<UserSummaryDto> users) {
}
